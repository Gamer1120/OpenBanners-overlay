package org.openbanners.overlay;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Settings → About → Version: the installed version and where that build comes from, told apart by its signing
 * certificate. Google Play signs with its own key (Play App Signing), GitHub and F-Droid ship the APK signed with the
 * OpenBanners key, so the two can't update each other: switching means uninstalling, which erases the settings.
 */
final class AppVersion {
    /** SHA-256 of the OpenBanners release certificate: the APKs on GitHub releases and F-Droid. */
    static final String OPENBANNERS_CERT = "35ed7171439ea00b47e556005dc6dafcef71fdf53843bcf1ea5a3319cf2f7d58";
    /** SHA-256 of the Play App Signing certificate: installs from Google Play (checked on Play's v59 APK). */
    static final String PLAY_CERT = "8f4496038388a0157397e70ceb23030eef1e78c2dcd0f23958f0456e37dd9bbb";

    static final String SOURCE_PLAY = "Google Play";
    static final String SOURCE_APK = "GitHub / F-Droid";

    private AppVersion() {
    }

    /** "60 · Google Play", "60 · GitHub / F-Droid", or just "60" for other builds (debug, own key). */
    static String label(String versionName, String certSha256) {
        String source = source(certSha256);
        return source == null ? versionName : versionName + " \u00B7 " + source;
    }

    static String source(String certSha256) {
        if (certSha256 == null) return null;
        String cert = certSha256.replace(":", "").toLowerCase(Locale.ROOT);
        if (PLAY_CERT.equals(cert)) return SOURCE_PLAY;
        if (OPENBANNERS_CERT.equals(cert)) return SOURCE_APK;
        return null;
    }

    static String label(Context context) {
        PackageManager pm = context.getPackageManager();
        String name = context.getPackageName();
        try {
            PackageInfo info;
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo == null ? null
                        : info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
            } else {
                info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            String cert = null;
            if (signatures != null && signatures.length > 0) {
                // The current signer is the last one in the rotation history.
                cert = sha256(signatures[signatures.length - 1].toByteArray());
            }
            return label(info.versionName, cert);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
