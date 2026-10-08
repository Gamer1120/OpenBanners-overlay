package org.openbanners.overlay;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import java.util.function.Function;

public class SettingsActivity extends AppCompatActivity {
    private final ActivityResultLauncher<String> locationEnabler = registerSwitchEnabler(R.string.location_enable);
    private final ActivityResultLauncher<String> notificationsEnabler = registerSwitchEnabler(R.string.notifications_enable);

    private ActivityResultLauncher<String> registerSwitchEnabler(@StringRes int key) {
        return registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                SettingsFragment settingsFragment = ((SettingsFragment) getSupportFragmentManager().findFragmentById(R.id.settings));
                assert settingsFragment != null;
                SwitchPreferenceCompat preference = settingsFragment.findPreference(getString(key));
                assert preference != null;
                preference.setChecked(true);
                preference.setEnabled(false);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        EdgeToEdgeInsets.apply(this);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(R.id.settings, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            ServiceNotification.createNotificationChannels(requireContext());
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            updatePreferences();
        }

        @Override
        public void onResume() {
            super.onResume();
        }

        private void updatePreferences() {
            SwitchPreferenceCompat locationEnablePreference = findPreference(getString(R.string.location_enable));
            assert locationEnablePreference != null;
            addPermissionPreference(locationEnablePreference, Manifest.permission.ACCESS_FINE_LOCATION, z -> z.locationEnabler);

            SwitchPreferenceCompat notificationsEnablePreference = findPreference(getString(R.string.notifications_enable));
            assert notificationsEnablePreference != null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addPermissionPreference(notificationsEnablePreference, Manifest.permission.POST_NOTIFICATIONS, z -> z.notificationsEnabler);
            } else {
                notificationsEnablePreference.setChecked(true);
                notificationsEnablePreference.setEnabled(false);
            }

            Preference notificationProgressPreference = findPreference(getString(R.string.notification_progress));
            assert notificationProgressPreference != null;
            addChannelPreference(notificationProgressPreference, ServiceNotification.DEFAULT_CHANNEL_ID);

            Preference notificationStepInRangePreference = findPreference(getString(R.string.notification_step_in_range));
            assert notificationStepInRangePreference != null;
            addChannelPreference(notificationStepInRangePreference, ServiceNotification.STEP_IN_RANGE_CHANNEL_ID);

            for (int key : new int[]{R.string.route_color_open, R.string.route_color_other, R.string.route_color_done, R.string.route_color_me}) {
                ListPreference colorPreference = findPreference(getString(key));
                assert colorPreference != null;
                addColorPreference(colorPreference);
            }

            // "Compass points to" only shows when the compass is always visible; the update interval only when
            // the compass is actually read (map follows the compass, or the needle shows your heading).
            ListPreference orientationPreference = findPreference(getString(R.string.route_orientation));
            SwitchPreferenceCompat alwaysPreference = findPreference(getString(R.string.route_compass_always));
            ListPreference needlePreference = findPreference(getString(R.string.route_compass_needle));
            Preference intervalPreference = findPreference(getString(R.string.route_compass_interval));
            assert orientationPreference != null && alwaysPreference != null && needlePreference != null && intervalPreference != null;
            Runnable updateCompassVisibility = () -> {
                needlePreference.setVisible(alwaysPreference.isChecked());
                intervalPreference.setVisible("compass".equals(orientationPreference.getValue())
                        || (alwaysPreference.isChecked() && "heading".equals(needlePreference.getValue())));
            };
            updateCompassVisibility.run();
            // Change listeners run before the new value is stored, so re-check afterwards.
            Preference.OnPreferenceChangeListener recheck = (p, newValue) -> {
                getListView().post(updateCompassVisibility);
                return true;
            };
            orientationPreference.setOnPreferenceChangeListener(recheck);
            alwaysPreference.setOnPreferenceChangeListener(recheck);
            needlePreference.setOnPreferenceChangeListener(recheck);
        }

        /** Shows each colour as a coloured dot plus its hex value, and the selected colour as the preference icon. */
        private void addColorPreference(ListPreference preference) {
            CharSequence[] names = preference.getEntries();
            CharSequence[] values = preference.getEntryValues();
            CharSequence[] entries = new CharSequence[names.length];
            for (int i = 0; i < names.length; i++) {
                SpannableString entry = new SpannableString("\u25CF  " + names[i] + "  " + values[i]);
                GradientDrawable dot = swatch(values[i].toString(), 16);
                if (dot != null) {
                    dot.setBounds(0, 0, dot.getIntrinsicWidth(), dot.getIntrinsicHeight());
                    entry.setSpan(new ImageSpan(dot, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                entries[i] = entry;
            }
            preference.setEntries(entries);
            setColorIcon(preference, preference.getValue());
            preference.setOnPreferenceChangeListener((p, newValue) -> {
                setColorIcon(preference, (String) newValue);
                return true;
            });
        }

        private void setColorIcon(Preference preference, String value) {
            GradientDrawable icon = swatch(value, 24);
            if (icon != null) preference.setIcon(icon);
        }

        /** A round colour swatch of {@code sizeDp}, or null if {@code value} isn't a colour. */
        private GradientDrawable swatch(String value, int sizeDp) {
            int color;
            try {
                color = Color.parseColor(value);
            } catch (IllegalArgumentException | NullPointerException e) {
                return null;
            }
            float density = getResources().getDisplayMetrics().density;
            GradientDrawable swatch = new GradientDrawable();
            swatch.setShape(GradientDrawable.OVAL);
            swatch.setColor(color);
            // Outline so white and grey stay visible on light and dark themes.
            swatch.setStroke(Math.round(1.5f * density), Color.argb(140, 128, 128, 128));
            int size = Math.round(sizeDp * density);
            swatch.setSize(size, size);
            return swatch;
        }

        private void addChannelPreference(Preference preference, String channelId) {
            preference.setOnPreferenceClickListener(p -> {
                Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
                startActivity(intent);
                return true;
            });
        }

        private void addPermissionPreference(SwitchPreferenceCompat preference, String permission, Function<SettingsActivity, ActivityResultLauncher<String>> launcherFunction) {
            boolean hasPermission = requireContext().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
            preference.setChecked(hasPermission);
            preference.setEnabled(!hasPermission);
            preference.setOnPreferenceChangeListener((x, newValue) -> {
                if (shouldShowRequestPermissionRationale(permission)) {
                    Toast.makeText(getContext(), "Permission has been permanently denied.", Toast.LENGTH_SHORT).show();
                } else {
                    launcherFunction.apply((SettingsActivity) getActivity()).launch(permission);
                }
                return false;
            });
        }
    }
}
