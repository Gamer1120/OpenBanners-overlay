package org.openbanners.overlay.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Crowd-sourced passphrases from openbanners.org (Settings → Passphrases → OpenBanners). Public and without sign-in:
 * the request only contains the banner id. Only used when the user chose that source.
 */
public class PassphraseApi {
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build();
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final String SITE = "https://openbanners.org";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Answer {
        public String missionId;
        public Integer missionIndex;
        public Integer stepIndex;
        public String poiId;
        /** "text" or "number" (the mission number). */
        public String kind;
        /** Only "live" answers are used; others are still in review. */
        public String status;
        public String answer;
        public int confirms;
        public int wrongs;
    }

    /** The answers for one banner. {@link #blocked} is set ("banner" or "author") when the author opted out. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BannerAnswers {
        public String bannerId;
        public String blocked;
        public List<Answer> answers = new ArrayList<>();
    }

    public static HttpUrl url(String bannerId) {
        return Objects.requireNonNull(HttpUrl.parse(SITE)).newBuilder()
                .addPathSegments("_openbanners/passphrases/v1/banners")
                .addPathSegment(bannerId)
                .build();
    }

    /** The raw JSON for a banner (kept as is in the offline cache). */
    public static String fetchJson(String bannerId) throws IOException {
        Request request = new Request.Builder().url(url(bannerId)).build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            return body.string();
        }
    }

    public static BannerAnswers parse(String json) throws IOException {
        BannerAnswers answers = objectMapper.readValue(json, BannerAnswers.class);
        if (answers.answers == null) answers.answers = new ArrayList<>();
        return answers;
    }
}
