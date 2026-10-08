package org.openbanners.overlay;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** Layout text shared to the app (see MainActivity): ask before importing it. */
    static final String EXTRA_IMPORT_LAYOUT = "org.openbanners.overlay.IMPORT_LAYOUT";

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

            Preference cardItemsPreference = findPreference(getString(R.string.card_items));
            assert cardItemsPreference != null;
            updateCardItemsSummary(cardItemsPreference);
            cardItemsPreference.setOnPreferenceClickListener(p -> {
                showCardItemsDialog(p);
                return true;
            });

            Preference exportPreference = findPreference(getString(R.string.layout_export));
            Preference importPreference = findPreference(getString(R.string.layout_import));
            assert exportPreference != null && importPreference != null;
            exportPreference.setOnPreferenceClickListener(p -> {
                exportLayout();
                return true;
            });
            importPreference.setOnPreferenceClickListener(p -> {
                showImportDialog(null);
                return true;
            });
            String shared = requireActivity().getIntent().getStringExtra(SettingsActivity.EXTRA_IMPORT_LAYOUT);
            if (shared != null) {
                requireActivity().getIntent().removeExtra(SettingsActivity.EXTRA_IMPORT_LAYOUT);
                showImportDialog(shared);
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

        private void exportLayout() {
            String layout = LayoutCodec.export(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()));
            ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.preferences_layout_export), layout));
            Toast.makeText(getContext(), R.string.layout_copied, Toast.LENGTH_SHORT).show();
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, layout);
            startActivity(Intent.createChooser(send, getString(R.string.layout_export_chooser)));
        }

        /** Asks for (or confirms) a layout and imports it. {@code text} pre-fills the field, else the clipboard does. */
        private void showImportDialog(String text) {
            EditText input = new EditText(requireContext());
            input.setHint(R.string.layout_import_hint);
            input.setMinLines(3);
            input.setMaxLines(8);
            if (text == null) {
                ClipboardManager clipboard = requireContext().getSystemService(ClipboardManager.class);
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence clipText = clip.getItemAt(0).coerceToText(requireContext());
                    if (clipText != null && LayoutCodec.looksLikeLayout(clipText.toString())) text = clipText.toString();
                }
            }
            if (text != null) input.setText(text);
            FrameLayout container = new FrameLayout(requireContext());
            int padding = Math.round(20 * getResources().getDisplayMetrics().density);
            container.setPadding(padding, padding / 2, padding, 0);
            container.addView(input);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.preferences_layout_import)
                    .setMessage(R.string.layout_import_confirm)
                    .setView(container)
                    .setPositiveButton(R.string.layout_import_action, (d, w) -> {
                        try {
                            LayoutCodec.importLayout(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()), input.getText().toString());
                            Toast.makeText(getContext(), R.string.layout_imported, Toast.LENGTH_SHORT).show();
                            requireActivity().recreate(); // show the imported values
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private String cardItemName(String item) {
            switch (item) {
                case CardItems.MINUS:
                    return getString(R.string.card_item_minus);
                case CardItems.COUNTER:
                    return getString(R.string.card_item_counter);
                case CardItems.PLUS:
                    return getString(R.string.card_item_plus);
                default:
                    return getString(R.string.card_item_next);
            }
        }

        private String storedCardItems() {
            return PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(getString(R.string.card_items), CardItems.DEFAULT);
        }

        private void updateCardItemsSummary(Preference preference) {
            List<String> names = new ArrayList<>();
            for (String item : CardItems.parse(storedCardItems())) names.add(cardItemName(item));
            preference.setSummary(String.join(", ", names));
        }

        /** Dialog with a checkbox per card item (shown or not) and arrows to move it up or down. */
        private void showCardItemsDialog(Preference preference) {
            List<String> order = new ArrayList<>(CardItems.parse(storedCardItems()));
            Set<String> shown = new HashSet<>(order);
            for (String item : CardItems.ALL) {
                if (!order.contains(item)) order.add(item);
            }
            LinearLayout list = new LinearLayout(requireContext());
            list.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            list.setPadding(padding, padding / 2, padding / 2, 0);
            Runnable[] render = new Runnable[1];
            render[0] = () -> {
                list.removeAllViews();
                for (int i = 0; i < order.size(); i++) {
                    String item = order.get(i);
                    int index = i;
                    LinearLayout row = new LinearLayout(requireContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    CheckBox checkBox = new CheckBox(requireContext());
                    checkBox.setText(cardItemName(item));
                    checkBox.setChecked(shown.contains(item));
                    checkBox.setOnCheckedChangeListener((b, checked) -> {
                        if (checked) {
                            shown.add(item);
                        } else if (shown.size() > 1) {
                            shown.remove(item);
                        } else {
                            b.setChecked(true);
                            Toast.makeText(getContext(), R.string.card_items_keep_one, Toast.LENGTH_SHORT).show();
                        }
                    });
                    row.addView(checkBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    row.addView(arrowButton("\u25B2", index > 0, () -> {
                        Collections.swap(order, index, index - 1);
                        render[0].run();
                    }));
                    row.addView(arrowButton("\u25BC", index < order.size() - 1, () -> {
                        Collections.swap(order, index, index + 1);
                        render[0].run();
                    }));
                    list.addView(row);
                }
            };
            render[0].run();
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.preferences_card_items)
                    .setView(list)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        List<String> result = new ArrayList<>();
                        for (String item : order) {
                            if (shown.contains(item)) result.add(item);
                        }
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                                .putString(getString(R.string.card_items), String.join(",", result)).apply();
                        updateCardItemsSummary(preference);
                    })
                    .setNeutralButton(R.string.card_items_reset, (d, w) -> {
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                                .putString(getString(R.string.card_items), CardItems.DEFAULT).apply();
                        updateCardItemsSummary(preference);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private Button arrowButton(String text, boolean enabled, Runnable action) {
            Button button = new Button(requireContext(), null, android.R.attr.borderlessButtonStyle);
            button.setText(text);
            button.setEnabled(enabled);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            int size = Math.round(44 * getResources().getDisplayMetrics().density);
            button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            button.setOnClickListener(v -> action.run());
            return button;
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
