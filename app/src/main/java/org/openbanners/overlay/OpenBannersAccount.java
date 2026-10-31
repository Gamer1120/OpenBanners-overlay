package org.openbanners.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Settings backup on openbanners.org, signed in as the user's Bannergress account without the app ever holding a
 * Bannergress token (server/overlay-backup.mjs in the OpenBanners repo):
 * <ol>
 * <li>The app makes a random verifier and state, keeps them here (a private, not backed-up file) and opens
 * {@code https://openbanners.org/overlay/connect?action=…&challenge=S256(verifier)&state=…} in the browser.</li>
 * <li>The user signs in there and taps Allow; the site sends back
 * {@code openbanners-overlay://link?code=…&state=…} (a one-time code, valid 5 minutes).</li>
 * <li>{@link AccountLinkActivity} hands it to Settings, which checks the state and trades code + verifier for a
 * token that only opens this account's backup, for 15 minutes ({@link #exchange}).</li>
 * </ol>
 * Nothing is kept afterwards: each backup or restore asks again.
 */
final class OpenBannersAccount {
    static final String SITE = DeepLink.DEFAULT_ORIGIN;
    static final String API = SITE + "/_openbanners/passphrases/v1";
    static final String ACTION_BACKUP = "backup";
    static final String ACTION_RESTORE = "restore";
    /** How long a started sign-in may take in the browser. */
    static final long PENDING_TTL_MS = 10 * 60 * 1000;
    /** Private and excluded from Android backups (res/xml/backup_rules.xml, data_extraction_rules.xml). */
    static final String PREFS_FILE = "openbanners_account";

    private static final SecureRandom random = new SecureRandom();
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private OpenBannersAccount() {
    }

    static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** PKCE S256 (RFC 7636): base64url(sha256(verifier)) without padding. */
    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String connectUrl(String action, String challenge, String state) {
        return SITE + "/overlay/connect?action=" + action + "&challenge=" + challenge + "&state=" + state;
    }

    /** A sign-in the app started and waits for. */
    static final class Pending {
        final String action;
        final String verifier;
        final String state;
        final long startedAt;

        Pending(String action, String verifier, String state, long startedAt) {
            this.action = action;
            this.verifier = verifier;
            this.state = state;
            this.startedAt = startedAt;
        }

        /** Whether the returned link belongs to this sign-in. */
        boolean matches(String returnedState, long now) {
            return state != null && state.equals(returnedState) && now - startedAt >= 0 && now - startedAt < PENDING_TTL_MS;
        }
    }

    /** Starts a sign-in: remembers it and returns the page to open. */
    static Uri start(Context context, String action) {
        String verifier = randomToken(48);
        String state = randomToken(24);
        prefs(context).edit()
                .putString("action", action)
                .putString("verifier", verifier)
                .putString("state", state)
                .putLong("started_at", System.currentTimeMillis())
                .apply();
        return Uri.parse(connectUrl(action, challenge(verifier), state));
    }

    /** The waiting sign-in, removed so a link can be used only once (null if there is none). */
    static Pending takePending(Context context) {
        SharedPreferences prefs = prefs(context);
        String action = prefs.getString("action", null);
        String verifier = prefs.getString("verifier", null);
        String state = prefs.getString("state", null);
        long startedAt = prefs.getLong("started_at", 0);
        prefs.edit().clear().apply();
        return action == null || verifier == null || state == null ? null : new Pending(action, verifier, state, startedAt);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** A backup as openbanners.org returns it. */
    static final class Backup {
        final int appVersion;
        final String createdAt;
        final Map<String, Object> settings;

        Backup(int appVersion, String createdAt, Map<String, Object> settings) {
            this.appVersion = appVersion;
            this.createdAt = createdAt;
            this.settings = settings;
        }
    }

    /** Error with a message for the user (the server's, when it gave one). */
    static final class ApiException extends IOException {
        ApiException(String message) {
            super(message);
        }
    }

    /** Trades the one-time code for a short-lived token for the backup. */
    static String exchange(String code, String verifier) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("verifier", verifier);
        JsonNode result = call(new Request.Builder().url(API + "/overlay/token")
                .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON)));
        String token = result.path("token").asText(null);
        if (token == null) throw new ApiException("OpenBanners didn't answer as expected.");
        return token;
    }

    static Backup getBackup(String token) throws IOException {
        JsonNode result = call(new Request.Builder().url(API + "/overlay/backup").header("Authorization", "Overlay " + token).get());
        return parseBackup(result.path("backup"));
    }

    static Backup putBackup(String token, int appVersion, Map<String, Object> settings) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", SettingsBackup.FORMAT);
        body.put("appVersion", appVersion);
        body.put("settings", settings);
        JsonNode result = call(new Request.Builder().url(API + "/overlay/backup").header("Authorization", "Overlay " + token)
                .put(RequestBody.create(objectMapper.writeValueAsString(body), JSON)));
        return parseBackup(result.path("backup"));
    }

    @SuppressWarnings("unchecked")
    static Backup parseBackup(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        Map<String, Object> settings = node.path("settings").isObject()
                ? objectMapper.convertValue(node.path("settings"), LinkedHashMap.class)
                : new LinkedHashMap<>();
        return new Backup(node.path("appVersion").asInt(0), node.path("createdAt").asText(""), settings);
    }

    static Backup parseBackupJson(String json) throws IOException {
        return parseBackup(objectMapper.readTree(json).path("backup"));
    }

    private static JsonNode call(Request.Builder request) throws IOException {
        try (Response response = client.newCall(request.header("Accept", "application/json").build()).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            JsonNode json;
            try {
                json = text.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(text);
            } catch (IOException e) {
                json = objectMapper.createObjectNode();
            }
            if (!response.isSuccessful()) {
                String message = json.path("message").asText("");
                throw new ApiException(message.isEmpty() ? "OpenBanners answered with error " + response.code() + "." : message);
            }
            return json;
        }
    }
}
