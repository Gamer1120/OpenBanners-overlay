package org.openbanners.overlay;

import android.annotation.SuppressLint;
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
import com.google.common.collect.Iterables;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public OverlayView(Context context, String data, boolean autostart, String origin) {
        super(context);
        LayoutInflater inflater = context.getSystemService(LayoutInflater.class);
        inflater.inflate(R.layout.activity_overlay, this, true);
        textMission = findViewById(R.id.textMission);
        buttonMinus = findViewById(R.id.buttonMinus);
        buttonNext = findViewById(R.id.buttonNext);
        buttonPlus = findViewById(R.id.buttonPlus);
        buttonClose = findViewById(R.id.buttonClose);
        buttonDone = findViewById(R.id.buttonDone);
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
        applyCardPreferences(PreferenceManager.getDefaultSharedPreferences(context));
        setupListeners();
        applyState(StateManager.getState());
        loadData(data, autostart, context);
    }

    public static OverlayView create(Context context, String data, boolean autostart, String origin) {
        OverlayView overlayView = new OverlayView(context, data, autostart, origin);
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
        rebuildCard();
    }

    /** Builds the card for the settings and the mission phase (last mission or not); returns whether it changed. */
    private boolean rebuildCard() {
        String layout = cardLayout;
        String config = layout + "|" + cardSize + "|" + cardItems + "|" + lastMission + "|" + canMarkDone;
        if (config.equals(appliedCardConfig)) return false;
        appliedCardConfig = config;
        float scale;
        try {
            scale = Float.parseFloat(cardSize);
        } catch (NumberFormatException e) {
            scale = 1f;
        }
        float dp = getResources().getDisplayMetrics().density;

        Button[] buttons = {buttonMinus, buttonPlus, buttonNext, buttonClose, buttonDone};
        for (View view : new View[]{buttonMinus, textMission, buttonPlus, buttonNext, buttonClose, buttonDone}) {
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

        List<CardItems.Line> lines = CardItems.arrange(CardItems.parseConfig(cardItems), layout, lastMission, canMarkDone);
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
        int widthDp = view == textMission ? 60 : view == buttonNext || view == buttonClose || view == buttonDone ? 90 : 40;
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
            Toast.makeText(getContext(), "Couldn't open " + url, Toast.LENGTH_SHORT).show();
        }
    }

    public void remove() {
        StateManager.removeListener(stateListener);
        getContext().getSystemService(WindowManager.class).removeView(this);
    }

    private void loadData(String data, boolean autostart, Context context) {
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
                        if (autostart && !banner.missions.isEmpty()) {
                            // Opened with "Start with Overlay": begin at mission 1 (1/x) and open it in Ingress right away.
                            Mission first = banner.missions.values().iterator().next();
                            HandlerCompat.createAsync(Looper.getMainLooper()).post(() -> {
                                StateManager.updateState(state -> state.bannerStarted(banner));
                                Ingress.tryLaunchMission(getContext(), first.id);
                            });
                            return;
                        }
                        stateFunction = state -> state.bannerLoaded(banner);
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
                    Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void applyState(State state) {
        boolean wasLastMission = lastMission;
        boolean couldMarkDone = canMarkDone;
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
                textMission.setText(String.format(Locale.ROOT, "—/%s", numberOfMissions));
                buttonNext.setText(R.string.overlayStart);
            } else {
                textMission.setText(String.format(Locale.ROOT, "%s/%s", state.currentMission + 1, numberOfMissions));
                if (state.currentMission + 1 == numberOfMissions) {
                    buttonNext.setText(R.string.overlayClose);
                } else {
                    buttonNext.setText(R.string.overlayNext);
                }
            }

            // +/- wrap around (- on the first mission goes to the last, + on the last to the first)
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
        if ((wasLastMission != lastMission || couldMarkDone != canMarkDone) && rebuildCard() && cardChangedListener != null) {
            cardChangedListener.run();
        }
    }
}
