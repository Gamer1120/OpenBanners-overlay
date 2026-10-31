package org.openbanners.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** The restore confirmation on a real Settings screen (no network: the backup is handed in directly). */
@RunWith(AndroidJUnit4.class)
public class RestoreDialogTest {
    private static void screenshot(String name) throws Exception {
        SystemClock.sleep(800);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        File file = new File(context.getExternalFilesDir(null), name + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    @Test
    public void showsChangesAndRestoresAfterConfirming() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().clear()
                .putString("route_orientation", "compass")
                .putString("card_size", "1.0")
                .putBoolean("ingress_only", false)
                .commit();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("route_orientation", "north");
        settings.put("card_size", "1.25");
        settings.put("ingress_only", true);
        settings.put("route_color_me", "#FF1744");
        settings.put("a_setting_from_a_newer_app", true);
        OpenBannersAccount.Backup backup = new OpenBannersAccount.Backup(52, "2026-10-31T12:00:00.000Z", settings);

        try (ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(new Intent(context, SettingsActivity.class))) {
            screenshot("restore-0-settings");
            AtomicReference<AlertDialog> dialog = new AtomicReference<>();
            AtomicReference<String> text = new AtomicReference<>();
            scenario.onActivity(activity -> {
                SettingsActivity.SettingsFragment fragment = (SettingsActivity.SettingsFragment) activity.getSupportFragmentManager().findFragmentById(R.id.settings);
                dialog.set(fragment.showRestoreDialog(backup));
            });
            SystemClock.sleep(500);
            scenario.onActivity(activity -> {
                TextView message = dialog.get().findViewById(android.R.id.message);
                text.set(message == null ? null : message.getText().toString());
            });
            screenshot("restore-1-confirm");
            assertNotNull(text.get());
            assertTrue(text.get(), text.get().contains("Map orientation: Follow compass → North up"));
            assertTrue(text.get(), text.get().contains("Size: Normal → Large"));
            assertTrue(text.get(), text.get().contains("Only show over Ingress: Off → On"));
            assertTrue(text.get(), text.get().contains("1 setting(s) from a newer app version are skipped."));
            assertTrue(text.get(), text.get().contains("app version 52"));
            // Nothing changes before confirming.
            assertEquals("compass", preferences.getString("route_orientation", null));
            scenario.onActivity(activity -> dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            SystemClock.sleep(1500);
            screenshot("restore-2-done");
        }
        assertEquals("north", preferences.getString("route_orientation", null));
        assertEquals("1.25", preferences.getString("card_size", null));
        assertTrue(preferences.getBoolean("ingress_only", false));
        assertEquals("#FF1744", preferences.getString("route_color_me", null));
        assertEquals("two_rows", preferences.getString("card_layout", null)); // missing from the backup: default
    }
}
