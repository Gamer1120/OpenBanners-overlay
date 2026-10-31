package org.openbanners.overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Receives {@code openbanners-overlay://link?code=…&state=…} from openbanners.org/overlay/connect after the user
 * allowed a settings backup or restore, and hands it to Settings (which checks the state against the sign-in it
 * started, see {@link OpenBannersAccount}). No UI of its own. Its own host, so app versions without this feature
 * don't match the link and the site shows its "install or update" text instead.
 */
public class AccountLinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri data = getIntent() == null ? null : getIntent().getData();
        String code = null;
        String state = null;
        try {
            if (data != null && DeepLink.SCHEME.equals(data.getScheme()) && "link".equals(data.getHost())) {
                code = data.getQueryParameter("code");
                state = data.getQueryParameter("state");
            }
        } catch (RuntimeException ignored) {
            // A malformed link: ignore it.
        }
        if (code != null && state != null) {
            Intent intent = new Intent(this, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(SettingsActivity.EXTRA_LINK_CODE, code)
                    .putExtra(SettingsActivity.EXTRA_LINK_STATE, state);
            startActivity(intent);
        }
        finish();
    }
}
