package org.openbanners.overlay;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.os.HandlerCompat;
import androidx.preference.PreferenceManager;

import org.openbanners.overlay.api.Banner;
import org.openbanners.overlay.api.BannerApi;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.PlanApi;
import org.openbanners.overlay.api.UserMessageException;
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Function;

@SuppressLint("ViewConstructor")
class OverlayView extends FrameLayout {
    public static final int COOLDOWN_MILLIS = 1_000;

    private static final ExecutorService executorService = Executors.newFixedThreadPool(1);
    private final WindowManager.LayoutParams params;
    private final TextView textMission;
    private final Button buttonMinus;
    private final Button buttonNext;
    private final Button buttonPlus;
    /** "Close" on the last mission (where Start / Next is hidden). */
    private final Button buttonClose;
    /** "Close and mark as done" on the last mission. */
    private final Button buttonDone;
    /** 🔑: copies the current mission's passphrase, or lists them when it has several. */
    private final Button buttonPassphrase;
    /** Below the buttons: the passphrase list, a passphrase's text, or a short message. */
    private final LinearLayout passphrasePanel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Settings → Passphrases. */
    private String passphraseSource = Passphrases.SOURCE_NONE;
    private boolean passphraseFallback;
    private boolean passphraseAutoCopy;
    /** The current mission's passphrases (empty: no 🔑). */
    private Passphrases.ForMission passphrases = Passphrases.NONE;
    /** 🔑 sits in a row: short labels. */
    private boolean passphraseCompact;
    /** The banner and mission shown, to notice a switch to another mission. */
    private Banner shownBanner;
    private int shownMission = Integer.MIN_VALUE;
    /** Waiting for the passphrases of this mission to arrive before copying automatically. */
    private boolean autoCopyPending;
    /** What the panel shows: nothing, the list, one step's text, or a message. */
    private static final int PANEL_NONE = 0, PANEL_LIST = 1, PANEL_TEXT = 2, PANEL_MESSAGE = 3;
    private int panelMode = PANEL_NONE;
    /** Step shown in the text panel. */
    private int panelStep = -1;
    private String panelMessage;
    private final Runnable hideMessage = () -> {
        if (panelMode == PANEL_MESSAGE) setPanel(PANEL_NONE, -1, null);
    };
    private boolean showingCopied;
    private final Runnable restoreButtonLabel = () -> {
        showingCopied = false;
        updatePassphraseButton();
    };
    private final Runnable passphrasesArrived = () -> {
        applyState(StateManager.getState());
        if (autoCopyPending) autoCopy();
        if (panelMode == PANEL_LIST || panelMode == PANEL_TEXT) renderPanel();
    };
    /** OpenBanners site for "Close and mark as done". */
    private final String origin;
    private final LinearLayout cardContent;
    private final float baseButtonTextPx;
    private final float baseCounterTextPx;
    private final int baseButtonMinHeight;
    /** Card settings from the preferences: layout, size and items (see {@link CardItems}). */
    private String cardLayout = CardItems.LAYOUT_TWO_ROWS;
    private String cardSize = "1.0";
    private String cardItems = CardItems.DEFAULT;
    /** On the last mission: Start / Next is replaced by Close. */
    private boolean lastMission;
    /** On the last mission of a banner or plan: "Close and mark as done" can show. */
    private boolean canMarkDone;
    /** Card settings and mission phase currently applied, to skip rebuilding when nothing relevant changed. */
    private String appliedCardConfig;
    /** Called after the card was rebuilt because the mission phase changed (its size may differ). */
    private Runnable cardChangedListener;
    private BiConsumer<State, State> stateListener;
    /** Set when the overlay was opened without a link it can use; the error state then says "Unsupported link". */
    private volatile boolean unsupportedLink;

    /** {@code startMission}: 0-based mission to begin at when {@code autostart} ("Start with Overlay from here"). */
    public OverlayView(Context context, String data, boolean autostart, int startMission, String origin) {
        this(context, data, autostart, startMission, origin, true);
    }

    /** {@code load}: load {@code data}; false for {@link #recreate}, which keeps the banner that is open. */
    private OverlayView(Context context, String data, boolean autostart, int startMission, String origin, boolean load) {
        super(context);
        LayoutInflater inflater = context.getSystemService(LayoutInflater.class);
        inflater.inflate(R.layout.activity_overlay, this, true);
        textMission = findViewById(R.id.textMission);
        buttonMinus = findViewById(R.id.buttonMinus);
        buttonNext = findViewById(R.id.buttonNext);
        buttonPlus = findViewById(R.id.buttonPlus);
        buttonClose = findViewById(R.id.buttonClose);
        buttonDone = findViewById(R.id.buttonDone);
        buttonPassphrase = findViewById(R.id.buttonPassphrase);
        passphrasePanel = findViewById(R.id.passphrasePanel);
        this.origin = origin;
        cardContent = findViewById(R.id.cardContent);
        baseButtonTextPx = buttonNext.getTextSize();
        baseCounterTextPx = textMission.getTextSize();
        baseButtonMinHeight = buttonNext.getMinHeight();
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.RGB_888);
        params.gravity = Gravity.START | Gravity.TOP;
        PassphraseSettings.migrate(PreferenceManager.getDefaultSharedPreferences(context));
        applyCardPreferences(PreferenceManager.getDefaultSharedPreferences(context));
        setupListeners();
        applyState(StateManager.getState());
        if (load) loadData(data, autostart, startMission, context);
    }

    /**
     * The same overlay built again with {@code context}, after Settings → Language changed: keeps the open banner,
     * the progress and where the card was dragged to. Removes {@code old}.
     */
    static OverlayView recreate(Context context, OverlayView old) {
        OverlayView overlayView = new OverlayView(context, null, false, 0, old.origin, false);
        overlayView.unsupportedLink = old.unsupportedLink;
        overlayView.params.x = old.params.x;
        overlayView.params.y = old.params.y;
        overlayView.setVisibility(old.getVisibility());
        old.remove();
        context.getSystemService(WindowManager.class).addView(overlayView, overlayView.params);
        overlayView.applyState(StateManager.getState());
        return overlayView;
    }

    public static OverlayView create(Context context, String data, boolean autostart, int startMission, String origin) {
        OverlayView overlayView = new OverlayView(context, data, autostart, startMission, origin);
        context.getSystemService(WindowManager.class).addView(overlayView, overlayView.params);
        return overlayView;
    }

    /** Width of the card; the route strip starts at its right edge. */
    public int getCardWidth() {
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
        return getMeasuredWidth();
    }

    /** Called on the main thread after the card was rebuilt for the last mission (or back); it may have resized. */
    void setCardChangedListener(Runnable listener) {
        cardChangedListener = listener;
    }

    /**
     * Arranges the card from the settings: which items are shown and in which order ({@link CardItems}),
     * the layout (two rows, one wide row, or one tall column) and the size.
     */
    void applyCardPreferences(SharedPreferences preferences) {
        Context context = getContext();
        cardLayout = preferences.getString(context.getString(R.string.card_layout), CardItems.LAYOUT_TWO_ROWS);
        cardSize = preferences.getString(context.getString(R.string.card_size), "1.0");
        cardItems = preferences.getString(context.getString(R.string.card_items), CardItems.DEFAULT);
        Map<String, ?> stored = preferences.getAll();
        String oldSource = passphraseSource;
        passphraseSource = PassphraseSettings.source(stored);
        passphraseFallback = PassphraseSettings.fallback(stored);
        passphraseAutoCopy = PassphraseSettings.autoCopy(stored);
        State state = StateManager.getState();
        if (Passphrases.SOURCE_OPENBANNERS.equals(passphraseSource) && !passphraseSource.equals(oldSource) && state.banner != null) {
            // Switched to OpenBanners while a banner is open: download its passphrases now.
            PassphraseStore.load(context, state.banner, true);
        }
        updatePassphrases(state);
        rebuildCard();
        updatePassphraseButton();
    }

    /** Builds the card for the settings and the mission phase (last mission or not); returns whether it changed. */
    private boolean rebuildCard() {
        String layout = cardLayout;
        String config = layout + "|" + cardSize + "|" + cardItems + "|" + lastMission + "|" + canMarkDone + "|" + passphrases.show();
        if (config.equals(appliedCardConfig)) return false;
        appliedCardConfig = config;
        float scale;
        try {
            scale = Float.parseFloat(cardSize);
        } catch (NumberFormatException e) {
            scale = 1f;
        }
        float dp = getResources().getDisplayMetrics().density;

        Button[] buttons = {buttonMinus, buttonPlus, buttonNext, buttonClose, buttonDone, buttonPassphrase};
        for (View view : new View[]{buttonMinus, textMission, buttonPlus, buttonNext, buttonClose, buttonDone, buttonPassphrase, passphrasePanel}) {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) parent.removeView(view);
        }
        cardContent.removeAllViews();
        for (Button button : buttons) {
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseButtonTextPx * scale);
            button.setMinHeight(Math.round(baseButtonMinHeight * scale));
            button.setMinimumHeight(Math.round(baseButtonMinHeight * scale));
        }
        textMission.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseCounterTextPx * scale);
        textMission.setGravity(Gravity.CENTER);

        List<CardItems.Line> lines = CardItems.arrange(CardItems.parseConfig(cardItems), layout, lastMission, canMarkDone, passphrases.show());
        passphraseCompact = false;
        for (CardItems.Line line : lines) passphraseCompact |= line.row && line.items.contains(CardItems.PASSPHRASE);
        int fullWidth = Math.round(100 * dp * scale);
        // Width of the card's content, for full-width lines in the two-row and wide layouts ("Close and mark as done";
        // MATCH_PARENT in this wrap_content card would keep the height of a first, wider measure and cut off the
        // wrapped text): the row's width, or a single item's width if there is no row.
        int contentWidth = fullWidth;
        if (!CardItems.LAYOUT_TALL.equals(layout)) {
            for (CardItems.Line line : lines) {
                if (!line.row) continue;
                int width = 0;
                for (String item : line.items) width += naturalWidth(viewFor(item), dp, scale);
                contentWidth = width;
            }
        }
        for (CardItems.Line line : lines) {
            if (line.row) {
                LinearLayout row = row();
                for (String item : line.items) {
                    View view = viewFor(item);
                    row.addView(view, new LinearLayout.LayoutParams(naturalWidth(view, dp, scale), ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                cardContent.addView(row);
            } else {
                View view = viewFor(line.items.get(0));
                int width = CardItems.LAYOUT_TALL.equals(layout) ? fullWidth
                        : view == buttonDone ? contentWidth
                        // Two rows: the last item fills the width below the row (the original card).
                        : lines.size() > 1 ? ViewGroup.LayoutParams.MATCH_PARENT : fullWidth;
                cardContent.addView(view, new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        // The panel takes the card's width (it never widens the card, so the route map next to it stays put).
        panelWidth = CardItems.LAYOUT_TALL.equals(layout) || lines.size() == 1 && !lines.get(0).row ? fullWidth : contentWidth;
        cardScale = scale;
        cardContent.addView(passphrasePanel, new LinearLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (panelMode != PANEL_NONE && !passphrases.show()) setPanel(PANEL_NONE, -1, null);
        if (panelMode != PANEL_NONE) renderPanel();
        if (isAttachedToWindow()) {
            getContext().getSystemService(WindowManager.class).updateViewLayout(this, params);
        }
        return true;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    /** Width of an item when it sits in a row: the original card's widths, scaled. */
    private int naturalWidth(View view, float dp, float scale) {
        int widthDp = view == textMission ? 60 : view == buttonNext || view == buttonClose || view == buttonDone ? 90
                : view == buttonPassphrase ? 52 : 40;
        return Math.round(widthDp * dp * scale);
    }

    private View viewFor(String item) {
        switch (item) {
            case CardItems.MINUS:
                return buttonMinus;
            case CardItems.COUNTER:
                return textMission;
            case CardItems.PLUS:
                return buttonPlus;
            case CardItems.CLOSE:
                return buttonClose;
            case CardItems.DONE:
                return buttonDone;
            case CardItems.PASSPHRASE:
                return buttonPassphrase;
            default:
                return buttonNext;
        }
    }

    public WindowManager.LayoutParams getWindowParams() {
        return params;
    }

    private void setupListeners() {
        stateListener = StateManager.addListener((newState, oldState) -> applyState(newState));
        buttonMinus.setOnClickListener(v -> StateManager.updateState(State::previousMission));
        buttonPlus.setOnClickListener(v -> StateManager.updateState(state -> state.nextMission(false)));
        buttonNext.setOnClickListener(v -> {
            Optional<Mission> optionalNextMission = StateManager.getState().banner.missions.values().stream().skip(StateManager.getState().currentMission + 1).findFirst();
            markCurrentPlanMissionDone();
            if (optionalNextMission.isPresent()) {
                Ingress.tryLaunchMission(getContext(), optionalNextMission.get().id);
                StateManager.updateState(state -> state.nextMission(true));
                new Handler().postDelayed(() -> StateManager.updateState(State::cooldownFinished), COOLDOWN_MILLIS);
            } else {
                stopOverlay();
            }
        });
        // Like Start / Next on the last mission: the plan's last mission counts as done, and the overlay closes.
        buttonClose.setOnClickListener(v -> {
            markCurrentPlanMissionDone();
            stopOverlay();
        });
        buttonDone.setOnClickListener(v -> {
            Banner banner = StateManager.getState().banner;
            if (banner != null && banner.planId != null) {
                // A plan: tick off the last mission and go back to the plan.
                markCurrentPlanMissionDone();
                openInBrowser(banner.planSite + "/planner/" + banner.planId);
            } else if (banner != null && banner.id != null) {
                openInBrowser(DeepLink.markDoneUrl(origin, banner.id));
            }
            stopOverlay();
        });
        buttonPassphrase.setOnClickListener(v -> onPassphraseTap());
        buttonPassphrase.setOnLongClickListener(v -> {
            onPassphraseLongPress();
            return true;
        });
        passphrasePanel.setOnClickListener(v -> {
            // Tap the shown text (or a message) to hide it; the list closes with 🔑.
            if (panelMode == PANEL_TEXT || panelMode == PANEL_MESSAGE) setPanel(PANEL_NONE, -1, null);
        });
        PassphraseStore.addListener(passphrasesArrived);
        setOnTouchListener(new ViewMoveListener(this));
    }

    /** Following a plan: the mission you were on counts as done there once you go on from it. */
    private void markCurrentPlanMissionDone() {
        State state = StateManager.getState();
        Banner banner = state.banner;
        if (banner == null || banner.planId == null || state.currentMission < 0) return;
        Mission mission = banner.missions.values().stream().skip(state.currentMission).findFirst().orElse(null);
        if (mission == null) return;
        String site = banner.planSite;
        String planId = banner.planId;
        executorService.submit(() -> {
            try {
                PlanApi.markDone(site, planId, mission.id);
            } catch (Exception ignored) {
                // Offline: it can still be ticked off on the plan page.
            }
        });
    }

    private void stopOverlay() {
        Intent serviceIntent = new Intent();
        serviceIntent.setComponent(new ComponentName(getContext(), OverlayService.class));
        getContext().stopService(serviceIntent);
    }

    private void openInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), getContext().getString(R.string.open_link_failed, url), Toast.LENGTH_SHORT).show();
        }
    }

    public void remove() {
        StateManager.removeListener(stateListener);
        PassphraseStore.removeListener(passphrasesArrived);
        handler.removeCallbacksAndMessages(null);
        getContext().getSystemService(WindowManager.class).removeView(this);
    }

    private void loadData(String data, boolean autostart, int startMission, Context context) {
        executorService.submit(() -> {
            try {
                SharedDataParser.ParsedData parsedData = SharedDataParser.parse(data);
                Function<State, State> stateFunction;
                switch (parsedData.type) {
                    case mission: {
                        String missionId = parsedData.id;
                        List<Banner> banners = BannerApi.findBanners(missionId);
                        if (banners.size() == 1) {
                            String bannerId = banners.get(0).id;
                            Banner banner = BannerApi.getBanner(bannerId);
                            int currentMission = Iterables.indexOf(banner.missions.values(), mission -> {
                                assert mission != null;
                                return mission.id.equals(missionId);
                            });
                            stateFunction = state -> state.bannerLoaded(banner, currentMission);
                        } else {
                            stateFunction = state -> State.error();
                        }
                        break;
                    }
                    case banner: {
                        String bannerId = parsedData.id;
                        Banner banner = BannerApi.getBanner(bannerId);
                        int start = State.startMissionIndex(banner, startMission);
                        if (autostart && !banner.missions.isEmpty()) {
                            // Opened with "Start with Overlay": begin at mission 1 (1/x), or at the mission chosen with
                            // "Start with Overlay from here" (mission 1 if the banner has no such mission), and open it
                            // in Ingress right away.
                            Mission first = Iterables.get(banner.missions.values(), start);
                            HandlerCompat.createAsync(Looper.getMainLooper()).post(() -> {
                                StateManager.updateState(state -> state.bannerStarted(banner, start));
                                Ingress.tryLaunchMission(getContext(), first.id);
                            });
                            return;
                        }
                        // A mission link without start=1 just shows that mission.
                        stateFunction = start > 0 ? state -> state.bannerLoaded(banner, start) : state -> state.bannerLoaded(banner);
                        break;
                    }
                    case plan: {
                        Banner route = PlanApi.getPlanRoute(parsedData.site, parsedData.id);
                        if (autostart) {
                            Mission first = route.missions.values().iterator().next();
                            HandlerCompat.createAsync(Looper.getMainLooper()).post(() -> {
                                StateManager.updateState(state -> state.bannerStarted(route));
                                Ingress.tryLaunchMission(getContext(), first.id);
                            });
                            return;
                        }
                        stateFunction = state -> state.bannerLoaded(route);
                        break;
                    }
                    case invalid: {
                        // No link we can open (nothing shared, or a link the app doesn't know): say so instead of "Error".
                        unsupportedLink = true;
                        HandlerCompat.createAsync(Looper.getMainLooper()).post(() ->
                                Toast.makeText(context, R.string.overlayUnsupportedLink, Toast.LENGTH_LONG).show());
                        stateFunction = state -> State.error();
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException(parsedData.type.toString());
                    }
                }
                HandlerCompat.createAsync(Looper.getMainLooper()).post(() -> StateManager.updateState(stateFunction));
            } catch (Exception e) {
                // Toasts need the main thread (this runs on the executor).
                HandlerCompat.createAsync(Looper.getMainLooper()).post(() -> {
                    StateManager.updateState(state -> State.error());
                    Toast.makeText(context, context.getString(R.string.overlay_load_error, UserMessageException.messageOf(context, e)), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void applyState(State state) {
        boolean wasLastMission = lastMission;
        boolean couldMarkDone = canMarkDone;
        boolean hadPassphrase = passphrases.show();
        if (state.banner != null && state.banner != shownBanner && Passphrases.SOURCE_OPENBANNERS.equals(passphraseSource)) {
            // A banner (or plan) was loaded: download its passphrases, each banner of a plan once.
            PassphraseStore.load(getContext(), state.banner, false);
        }
        boolean missionSwitched = state.banner != shownBanner || state.currentMission != shownMission;
        shownBanner = state.banner;
        shownMission = state.currentMission;
        updatePassphrases(state);
        if (state.banner == null) {
            if (state.error) {
                textMission.setText(unsupportedLink ? R.string.overlayUnsupportedLink : R.string.overlayError);
            } else {
                textMission.setText(R.string.overlayEllipsis);
            }
            buttonMinus.setEnabled(false);
            buttonPlus.setEnabled(false);
            buttonNext.setEnabled(false);
            buttonNext.setText(R.string.overlayLoading);
            lastMission = false;
            canMarkDone = false;
        } else {
            int numberOfMissions = state.banner.missions.size();

            if (state.currentMission == -1) {
                textMission.setText(String.format(Locale.ROOT, "0/%s", numberOfMissions));
                buttonNext.setText(R.string.overlayStart);
            } else {
                textMission.setText(String.format(Locale.ROOT, "%s/%s", state.currentMission + 1, numberOfMissions));
                if (state.currentMission + 1 == numberOfMissions) {
                    buttonNext.setText(R.string.overlayClose);
                } else {
                    buttonNext.setText(R.string.overlayNext);
                }
            }

            // - on the first mission goes to 0/x (Start), - on 0/x wraps to the last; + on the last wraps to the first
            boolean hasMissions = numberOfMissions > 0;
            buttonMinus.setEnabled(hasMissions);
            buttonPlus.setEnabled(hasMissions);
            buttonNext.setEnabled(!state.cooldown);
            // The last mission: Close (and Close and mark as done) instead of Start / Next, where the user put them.
            lastMission = state.currentMission != -1 && state.currentMission + 1 == numberOfMissions;
            canMarkDone = state.currentMission + 1 == numberOfMissions && (state.banner.id != null || state.banner.planId != null);
            buttonClose.setEnabled(!state.cooldown);
            buttonDone.setEnabled(!state.cooldown);
        }
        if (missionSwitched) {
            // Everything passphrase is about the selected mission: a new one starts with the panel closed.
            handler.removeCallbacks(hideMessage);
            setPanel(PANEL_NONE, -1, null);
            autoCopyPending = false;
            if (passphraseAutoCopy && passphrases.single()) autoCopy();
        }
        if ((wasLastMission != lastMission || couldMarkDone != canMarkDone || hadPassphrase != passphrases.show())
                && rebuildCard() && cardChangedListener != null) {
            cardChangedListener.run();
        }
        updatePassphraseButton();
    }

    /** The selected mission, or null before the start. */
    private static Mission currentMission(State state) {
        if (state.banner == null || state.banner.missions == null || state.currentMission < 0) return null;
        return state.banner.missions.values().stream().skip(state.currentMission).findFirst().orElse(null);
    }

    private void updatePassphrases(State state) {
        Mission mission = currentMission(state);
        if (mission == null) {
            passphrases = Passphrases.NONE;
            return;
        }
        passphrases = Passphrases.forMission(passphraseSource, passphraseFallback, mission, state.currentMission,
                PassphraseStore.get(mission.sourceBannerId != null ? mission.sourceBannerId : state.banner.id));
    }

    /**
     * Auto-copy on switching to a mission with one passphrase (Settings → Passphrases). If its banner's passphrases
     * are still downloading, it waits for them (only while you stay on that mission).
     */
    private void autoCopy() {
        autoCopyPending = false;
        if (!passphraseAutoCopy || !passphrases.single()) return;
        Mission mission = currentMission(StateManager.getState());
        if (mission != null && Passphrases.SOURCE_OPENBANNERS.equals(passphraseSource)
                && PassphraseStore.get(mission.sourceBannerId) == null && PassphraseStore.loading(mission.sourceBannerId)) {
            autoCopyPending = true;
            return;
        }
        Passphrases.Copy copy = passphrases.autoCopy();
        if (copy == null) return;
        copyToClipboard(copy.text);
        flashCopied();
    }

    // --- 🔑 Passphrase -------------------------------------------------------------------------------------------

    private int panelWidth;
    private float cardScale = 1f;

    private void updatePassphraseButton() {
        if (!passphrases.show() || showingCopied) return;
        String label;
        if (passphrases.single()) {
            boolean known = passphrases.steps.get(0).known();
            label = known ? getContext().getString(passphraseCompact ? R.string.passphrase_button_short : R.string.passphrase_button)
                    : getContext().getString(passphraseCompact ? R.string.passphrase_button_unknown_short : R.string.passphrase_button_unknown);
        } else {
            label = getContext().getString(passphraseCompact ? R.string.passphrase_button_several_short : R.string.passphrase_button_several,
                    passphrases.steps.size());
        }
        buttonPassphrase.setText(label);
        // Grey while nothing can be copied (no answer known and no mission-number fallback).
        buttonPassphrase.setAlpha(passphrases.unknown() ? 0.5f : 1f);
    }

    /** The button shows "✓ Copied" for a moment. */
    private void flashCopied() {
        showingCopied = true;
        buttonPassphrase.setText(passphraseCompact ? R.string.passphrase_copied_short : R.string.passphrase_copied);
        buttonPassphrase.setAlpha(1f);
        handler.removeCallbacks(restoreButtonLabel);
        handler.postDelayed(restoreButtonLabel, 1_500);
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(getContext().getString(R.string.passphrase_clip_label), text));
    }

    private void onPassphraseTap() {
        if (!passphrases.show()) return;
        if (!passphrases.single()) {
            setPanel(panelMode == PANEL_LIST ? PANEL_NONE : PANEL_LIST, -1, null);
            return;
        }
        Passphrases.Copy copy = passphrases.copy(passphrases.steps.get(0));
        if (copy == null) {
            showMessage(getContext().getString(R.string.passphrase_unknown));
            return;
        }
        copyToClipboard(copy.text);
        flashCopied();
        if (copy.missionNumber) {
            showMessage(getContext().getString(R.string.passphrase_copied_mission_number));
        } else if (passphraseCompact) {
            showMessage(getContext().getString(R.string.passphrase_copied));
        } else if (panelMode == PANEL_MESSAGE) {
            setPanel(PANEL_NONE, -1, null);
        }
    }

    private void onPassphraseLongPress() {
        if (!passphrases.show()) return;
        if (passphrases.single()) {
            setPanel(panelMode == PANEL_TEXT ? PANEL_NONE : PANEL_TEXT, 0, null);
        } else {
            setPanel(panelMode == PANEL_LIST ? PANEL_NONE : PANEL_LIST, -1, null);
        }
    }

    /** A short message below the buttons for a few seconds. */
    private void showMessage(String message) {
        setPanel(PANEL_MESSAGE, -1, message);
        handler.removeCallbacks(hideMessage);
        handler.postDelayed(hideMessage, 2_500);
    }

    private void setPanel(int mode, int step, String message) {
        panelMode = mode;
        panelStep = step;
        panelMessage = message;
        renderPanel();
    }

    private void renderPanel() {
        passphrasePanel.removeAllViews();
        if (panelMode == PANEL_NONE || !passphrases.show() && panelMode != PANEL_MESSAGE) {
            panelMode = PANEL_NONE;
            passphrasePanel.setVisibility(View.GONE);
            return;
        }
        passphrasePanel.setVisibility(View.VISIBLE);
        float dp = getResources().getDisplayMetrics().density;
        int pad = Math.round(4 * dp * cardScale);
        passphrasePanel.setPadding(pad, pad, pad, pad);
        if (panelMode == PANEL_MESSAGE) {
            passphrasePanel.addView(text(panelMessage, 1f, false));
        } else if (panelMode == PANEL_TEXT) {
            if (panelStep < 0 || panelStep >= passphrases.steps.size()) {
                setPanel(PANEL_NONE, -1, null);
                return;
            }
            renderStepText(passphrases.steps.get(panelStep));
        } else {
            renderList();
        }
    }

    /** The passphrase itself (large), and other answers with their ✓ counts. */
    private void renderStepText(Passphrases.Step step) {
        if (!passphrases.single()) passphrasePanel.addView(text(describe(step, false), 0.8f, true));
        if (!step.known()) {
            passphrasePanel.addView(text(getContext().getString(R.string.passphrase_unknown), 1f, true));
            if (passphrases.fallback) {
                passphrasePanel.addView(text(getContext().getString(R.string.passphrase_fallback_note, passphrases.missionNumber), 0.8f, true));
            }
            return;
        }
        passphrasePanel.addView(text(step.best(), 1.5f, false));
        if (step.candidates.size() > 1) {
            passphrasePanel.addView(text(getContext().getString(R.string.passphrase_alternatives), 0.8f, true));
            for (Passphrases.Candidate candidate : step.candidates) {
                passphrasePanel.addView(text(getContext().getString(R.string.passphrase_candidate, candidate.text, candidate.confirms), 0.9f, false));
            }
        }
    }

    /** One row per passphrase step: label · portal · [Copy]. */
    private void renderList() {
        float dp = getResources().getDisplayMetrics().density;
        boolean anyUnknown = false;
        for (int i = 0; i < passphrases.steps.size(); i++) {
            Passphrases.Step step = passphrases.steps.get(i);
            int index = i;
            LinearLayout row = new LinearLayout(buttonNext.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            anyUnknown |= !step.known();
            TextView label = text(describe(step, !step.known()), 0.8f, !step.known());
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (step.known()) {
                Button copy = smallButton(getContext().getString(R.string.passphrase_copy));
                copy.setOnClickListener(v -> copyStep(step));
                copy.setOnLongClickListener(v -> {
                    setPanel(PANEL_TEXT, index, null);
                    return true;
                });
                row.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(36 * dp * cardScale)));
            }
            // Long-press a row: show its passphrase (and other answers).
            row.setOnLongClickListener(v -> {
                setPanel(PANEL_TEXT, index, null);
                return true;
            });
            row.setMinimumHeight(Math.round(36 * dp * cardScale));
            passphrasePanel.addView(row);
        }
        if (anyUnknown && passphrases.fallback) {
            Button missionNumber = smallButton(getContext().getString(R.string.passphrase_copy_mission_number));
            missionNumber.setOnClickListener(v -> {
                copyToClipboard(passphrases.missionNumber);
                flashCopied();
                showMessage(getContext().getString(R.string.passphrase_copied_mission_number));
            });
            passphrasePanel.addView(missionNumber, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(40 * dp * cardScale)));
        }
    }

    /** "5c · Portal name" (without the portal if it has no name), plus "· not known yet". */
    private String describe(Passphrases.Step step, boolean notKnown) {
        StringBuilder text = new StringBuilder(step.label);
        if (step.portal != null && !step.portal.trim().isEmpty()) text.append(" · ").append(step.portal.trim());
        if (notKnown) text.append(" · ").append(getContext().getString(R.string.passphrase_not_known_row));
        return text.toString();
    }

    private void copyStep(Passphrases.Step step) {
        Passphrases.Copy copy = passphrases.copy(step);
        if (copy == null) return;
        copyToClipboard(copy.text);
        flashCopied();
        showMessage(getContext().getString(R.string.passphrase_step_copied, step.label));
    }

    private TextView text(String value, float size, boolean grey) {
        TextView view = new TextView(buttonNext.getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseCounterTextPx * cardScale * size * 0.85f);
        view.setTextColor(textMission.getTextColors());
        if (grey) view.setAlpha(0.6f);
        return view;
    }

    private Button smallButton(String label) {
        Button button = new Button(buttonNext.getContext(), null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, baseButtonTextPx * cardScale * 0.9f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        button.setPadding(pad, 0, pad, 0);
        return button;
    }
}
