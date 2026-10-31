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
import android.content.DialogInterface;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
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
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class SettingsActivity extends AppCompatActivity {
    private final ActivityResultLauncher<String> locationEnabler = registerSwitchEnabler(R.string.location_enable, Manifest.permission.ACCESS_FINE_LOCATION);
    private final ActivityResultLauncher<String> notificationsEnabler = registerSwitchEnabler(R.string.notifications_enable, Manifest.permission.POST_NOTIFICATIONS);

    private ActivityResultLauncher<String> registerSwitchEnabler(@StringRes int key, String permission) {
        return registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            SettingsFragment settingsFragment = ((SettingsFragment) getSupportFragmentManager().findFragmentById(R.id.settings));
            boolean granted = isGranted || (key == R.string.location_enable && LocationPermission.granted(this));
            if (granted) {
                assert settingsFragment != null;
                SwitchPreferenceCompat preference = settingsFragment.findPreference(getString(key));
                assert preference != null;
                preference.setChecked(true);
                preference.setEnabled(false);
                // A running overlay picks the permission up now (location foreground service, location updates).
                StateManager.updateState(state -> state);
            } else if (!shouldShowRequestPermissionRationale(permission)) {
                // Denied for good (or "Don't allow" twice): Android no longer asks, so point to the app's settings.
                Toast.makeText(this, R.string.permission_denied_open_settings, Toast.LENGTH_LONG).show();
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (android.content.ActivityNotFoundException ignored) {
                    // The toast says where to go.
                }
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
    /** Set after importing a layout that wanted the route map on: confirm the safety points first. */
    static final String EXTRA_SHOW_ROUTE_DISCLAIMER = "org.openbanners.overlay.SHOW_ROUTE_DISCLAIMER";

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            ServiceNotification.createNotificationChannels(requireContext());
            // Before the switches are bound: a route map that's on without confirmation shows as off.
            RouteDisclaimer.enforce(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()));
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            wrapTitles(getPreferenceScreen());

            updatePreferences();
        }

        @Override
        public void onResume() {
            super.onResume();
            updatePermissionSwitches();
            updateIngressOnlySummary();
            reconcileBannergressLinks();
        }

        /**
         * The switch is what the user wants; make the link handler's enabled state match it (e.g. after a backup
         * restore) and refresh the Android 12+ status line, which can change in Android settings meanwhile.
         */
        private void reconcileBannergressLinks() {
            SwitchPreferenceCompat preference = findPreference(getString(R.string.bannergress_links));
            Preference status = findPreference(getString(R.string.bannergress_links_status));
            if (preference == null || status == null) return;
            try {
                BannergressLinkActivity.setEnabled(requireContext(), preference.isChecked());
            } catch (RuntimeException ignored) {
                // Leave it; the switch still shows the user's choice.
            }
            updateBannergressLinksStatus(preference.isChecked(), status);
        }

        private void updateBannergressLinksStatus(boolean on, Preference status) {
            boolean show = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            status.setVisible(show);
            if (show) {
                status.setSummary(BannergressLinkActivity.isLinkHandlingApproved(requireContext())
                        ? R.string.links_status_active : R.string.links_status_not_set_up);
            }
        }

        /** Android 12+ only lets an app open web links after the user allows it under "Open by default". */
        private void showLinkSetupDialog() {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.links_setup_title)
                    .setMessage(R.string.links_setup_message)
                    .setPositiveButton(R.string.links_setup_open, (d, w) -> BannergressLinkActivity.openLinkSettings(requireContext()))
                    .setNegativeButton(R.string.links_setup_later, null)
                    .show();
        }

        /**
         * Straight to this app's Usage access switch (Android 10+ settings accept the package, screach 10-24),
         * else the list of all apps.
         */
        private void openUsageAccessSettings() {
            Intent intent = new Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS);
            try {
                startActivity(new Intent(intent).setData(android.net.Uri.parse("package:" + requireContext().getPackageName())));
            } catch (android.content.ActivityNotFoundException e) {
                startActivity(intent);
            }
        }

        /** "Only show over Ingress" needs Usage access; without it the overlay stays visible, so say so. */
        private void updateIngressOnlySummary() {
            SwitchPreferenceCompat preference = findPreference(getString(R.string.ingress_only));
            if (preference == null) return;
            boolean missing = preference.isChecked() && !ForegroundApp.hasAccess(requireContext());
            preference.setSummary(missing ? R.string.preferences_ingress_only_no_access : R.string.preferences_ingress_only_summary);
        }

        private void updatePreferences() {
            SwitchPreferenceCompat locationEnablePreference = findPreference(getString(R.string.location_enable));
            assert locationEnablePreference != null;
            addPermissionPreference(locationEnablePreference, Manifest.permission.ACCESS_FINE_LOCATION, z -> z.locationEnabler);
            updatePermissionSwitches();

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

            SwitchPreferenceCompat ingressOnlyPreference = findPreference(getString(R.string.ingress_only));
            assert ingressOnlyPreference != null;
            ingressOnlyPreference.setOnPreferenceChangeListener((p, newValue) -> {
                if (Boolean.TRUE.equals(newValue) && !ForegroundApp.hasAccess(requireContext())) {
                    Toast.makeText(requireContext(), R.string.preferences_ingress_only_grant, Toast.LENGTH_LONG).show();
                    openUsageAccessSettings();
                }
                p.getPreferenceManager().getSharedPreferences().edit().putBoolean(p.getKey(), Boolean.TRUE.equals(newValue)).apply();
                ((SwitchPreferenceCompat) p).setChecked(Boolean.TRUE.equals(newValue));
                updateIngressOnlySummary();
                return false;
            });

            SwitchPreferenceCompat routeStripPreference = findPreference(getString(R.string.route_strip_enable));
            assert routeStripPreference != null;
            routeStripPreference.setOnPreferenceChangeListener((p, newValue) -> {
                if (!Boolean.TRUE.equals(newValue)
                        || RouteDisclaimer.isAccepted(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()))) {
                    return true;
                }
                showRouteDisclaimerDialog(routeStripPreference);
                return false; // stays off until confirmed
            });
            if (requireActivity().getIntent().getBooleanExtra(EXTRA_SHOW_ROUTE_DISCLAIMER, false)) {
                requireActivity().getIntent().removeExtra(EXTRA_SHOW_ROUTE_DISCLAIMER);
                showRouteDisclaimerDialog(routeStripPreference);
            }

            SwitchPreferenceCompat bannergressLinksPreference = findPreference(getString(R.string.bannergress_links));
            Preference bannergressLinksStatus = findPreference(getString(R.string.bannergress_links_status));
            assert bannergressLinksPreference != null && bannergressLinksStatus != null;
            bannergressLinksPreference.setOnPreferenceChangeListener((p, newValue) -> {
                boolean on = Boolean.TRUE.equals(newValue);
                try {
                    BannergressLinkActivity.setEnabled(requireContext(), on);
                } catch (RuntimeException e) {
                    return false;
                }
                updateBannergressLinksStatus(on, bannergressLinksStatus);
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && !BannergressLinkActivity.isLinkHandlingApproved(requireContext())) {
                    showLinkSetupDialog();
                }
                return true;
            });
            bannergressLinksStatus.setOnPreferenceClickListener(p -> {
                BannergressLinkActivity.openLinkSettings(requireContext());
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
            Preference stepPreference = findPreference(getString(R.string.route_compass_step));
            assert orientationPreference != null && alwaysPreference != null && needlePreference != null && intervalPreference != null && stepPreference != null;
            boolean hasCompass = RouteOrientation.hasCompassSensor(requireContext());
            if (!hasCompass) {
                // Without a compass sensor the map is always north up; say so instead of the chosen value.
                orientationPreference.setSummaryProvider(null);
                orientationPreference.setSummary(R.string.preferences_route_orientation_no_sensor);
            }
            Runnable updateCompassVisibility = () -> {
                needlePreference.setVisible(alwaysPreference.isChecked());
                boolean compassRead = hasCompass && (RouteOrientation.COMPASS.equals(orientationPreference.getValue())
                        || (alwaysPreference.isChecked() && "heading".equals(needlePreference.getValue())));
                intervalPreference.setVisible(compassRead);
                stepPreference.setVisible(compassRead);
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
                            // A layout can't switch the route map on without the safety confirmation: ask for it.
                            if (RouteDisclaimer.enforce(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()))) {
                                requireActivity().getIntent().putExtra(EXTRA_SHOW_ROUTE_DISCLAIMER, true);
                            }
                            requireActivity().recreate(); // show the imported values
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        /**
         * Safety points the user has to tick before the route map turns on. Cancel, Back or tapping outside
         * leaves it off.
         */
        private void showRouteDisclaimerDialog(SwitchPreferenceCompat preference) {
            float density = getResources().getDisplayMetrics().density;
            LinearLayout content = new LinearLayout(requireContext());
            content.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(20 * density);
            content.setPadding(padding, padding / 2, padding, 0);
            TextView intro = new TextView(requireContext());
            intro.setText(R.string.route_disclaimer_intro);
            intro.setTextAppearance(android.R.style.TextAppearance_Material_Subhead);
            intro.setPadding(0, 0, 0, padding / 2);
            content.addView(intro);
            int[] points = {R.string.route_disclaimer_attention, R.string.route_disclaimer_when_safe,
                    R.string.route_disclaimer_responsible, R.string.route_disclaimer_own_risk};
            List<CheckBox> checkBoxes = new ArrayList<>();
            for (int point : points) {
                CheckBox checkBox = new CheckBox(requireContext());
                checkBox.setText(point);
                checkBox.setGravity(Gravity.TOP);
                checkBox.setPadding(checkBox.getPaddingLeft(), Math.round(6 * density), 0, Math.round(6 * density));
                checkBoxes.add(checkBox);
                content.addView(checkBox);
            }
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(content);
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.route_disclaimer_title)
                    .setView(scroll)
                    .setPositiveButton(R.string.route_disclaimer_accept, (d, w) -> {
                        RouteDisclaimer.accept(requireContext(), PreferenceManager.getDefaultSharedPreferences(requireContext()));
                        preference.setChecked(true);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            Button accept = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            accept.setEnabled(false);
            for (CheckBox checkBox : checkBoxes) {
                checkBox.setOnCheckedChangeListener((b, checked) -> {
                    boolean all = true;
                    for (CheckBox c : checkBoxes) all &= c.isChecked();
                    accept.setEnabled(all);
                });
            }
        }

        /** Long titles wrap onto a new line instead of being cut off with "…" on narrow screens. */
        private static void wrapTitles(PreferenceGroup group) {
            for (int i = 0; i < group.getPreferenceCount(); i++) {
                Preference preference = group.getPreference(i);
                preference.setSingleLineTitle(false);
                if (preference instanceof PreferenceGroup) wrapTitles((PreferenceGroup) preference);
            }
        }

        private String cardItemName(String item) {
            switch (item) {
                case CardItems.MINUS:
                    return getString(R.string.card_item_minus);
                case CardItems.COUNTER:
                    return getString(R.string.card_item_counter);
                case CardItems.PLUS:
                    return getString(R.string.card_item_plus);
                case CardItems.CLOSE:
                    return getString(R.string.card_item_close);
                case CardItems.DONE:
                    return getString(R.string.card_item_done);
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

        /**
         * Dialog with a checkbox per card item (shown or not) and arrows to move it up or down. The Close items are
         * marked as last-mission-only, with a note on how they replace Start / Next there.
         */
        private void showCardItemsDialog(Preference preference) {
            CardItems.Config config = CardItems.parseConfig(storedCardItems());
            List<String> order = new ArrayList<>(config.order);
            Set<String> shown = new HashSet<>(config.shown);
            LinearLayout list = new LinearLayout(requireContext());
            list.setOrientation(LinearLayout.VERTICAL);
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            list.setPadding(padding, padding / 2, padding / 2, 0);
            TextView note = new TextView(requireContext());
            note.setText(R.string.card_items_last_mission_note);
            note.setPadding(padding / 4, 0, padding / 2, padding / 2);
            LinearLayout rows = new LinearLayout(requireContext());
            rows.setOrientation(LinearLayout.VERTICAL);
            list.addView(rows);
            list.addView(note);
            Runnable[] render = new Runnable[1];
            render[0] = () -> {
                rows.removeAllViews();
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
                        } else if (!CardItems.leavesCardEmpty(without(shown, item))) {
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
                    rows.addView(row);
                }
            };
            render[0].run();
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(list);
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.preferences_card_items)
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                                .putString(getString(R.string.card_items), new CardItems.Config(order, shown).format()).apply();
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

        private static Set<String> without(Set<String> items, String item) {
            Set<String> result = new HashSet<>(items);
            result.remove(item);
            return result;
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
                // Android shows the dialog again after one "Don't allow"; once it stops asking, the result
                // handler points to the app's settings.
                launcherFunction.apply((SettingsActivity) getActivity()).launch(permission);
                return false;
            });
        }

        /**
         * Location may be granted (precise or approximate) or revoked in Android settings meanwhile: show it, and
         * let a running overlay pick a new permission up.
         */
        private void updatePermissionSwitches() {
            SwitchPreferenceCompat location = findPreference(getString(R.string.location_enable));
            if (location == null) return;
            boolean granted = LocationPermission.granted(requireContext());
            boolean changed = location.isChecked() != granted;
            location.setChecked(granted);
            location.setEnabled(!granted);
            if (changed && granted) StateManager.updateState(state -> state);
        }
    }
}
