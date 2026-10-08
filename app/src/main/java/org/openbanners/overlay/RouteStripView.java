package org.openbanners.overlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.location.Location;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.openbanners.overlay.api.Banner;
import org.openbanners.overlay.api.Mission;
import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.MissionType;
import org.openbanners.overlay.api.POI;
import org.openbanners.overlay.api.POIType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Touch-through route strip at the top of the screen, right of the control card (ported from Machina Path's overlay).
 * <p>
 * Draws the current mission's steps as labelled dots ("5c" = mission 5, step 3) with legs
 * in step order, plus the next mission's first step so you can see where to go afterwards.
 * No basemap: an equirectangular projection centred on your location, zoomed to at least
 * {@link #VISIBLE_METERS} across and out just far enough to show the
 * {@link #NEAREST_OPEN_IN_VIEW} nearest open steps. Yellow = open, grey = done, cyan = you.
 * Ingress's map is almost grayscale, so colours are saturated and drawn on a dark halo.
 */
@SuppressLint("ViewConstructor")
class RouteStripView extends View {
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final double VISIBLE_METERS = 150;
    private static final int NEAREST_OPEN_IN_VIEW = 3;
    private static final double MAX_VISIBLE_METERS = 2_000;
    private static final double EASING = 0.18;
    private static final float HEIGHT_FRACTION = 0.25f;
    /** Steps closer than this are treated as the same portal. */
    private static final double SAME_PORTAL_METERS = 5;
    /**
     * Android 12+ drops touches that pass through another app's overlay unless it is at most
     * this opaque (InputManager's default maximum obscuring opacity).
     */
    private static final float MAX_TOUCH_THROUGH_ALPHA = 0.8f;

    private static final int COLOR_HALO = Color.argb(200, 0, 0, 0);
    private static final int COLOR_OPEN = Color.rgb(255, 234, 0);
    private static final int COLOR_ME = Color.rgb(0, 229, 255);
    private static final int COLOR_DONE = Color.argb(170, 150, 150, 150);

    /** One drawable step: position, label, and whether it still has to be done. */
    private static final class Point {
        final double lat;
        final double lng;
        final String label;
        final boolean done;

        Point(double lat, double lng, String label, boolean done) {
            this.lat = lat;
            this.lng = lng;
            this.label = label;
            this.done = done;
        }
    }

    private final WindowManager.LayoutParams params;
    private final float dp;
    private final float statusBarHeightPx;
    private final Paint haloLegPaint;
    private final Paint openLegPaint;
    private final Paint doneLegPaint;
    private final Paint haloFill = fill(COLOR_HALO);
    private final Paint whiteFill = fill(Color.WHITE);
    private final Paint openFill = fill(COLOR_OPEN);
    private final Paint doneFill = fill(COLOR_DONE);
    private final Paint meFill = fill(COLOR_ME);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BiConsumer<State, State> stateListener;

    /** Steps in drawing order: the route is drawn through them in this order. */
    private List<Point> points = Collections.emptyList();
    /** Whether consecutive points are joined by legs (not for any-order missions). */
    private List<Boolean> legBefore = Collections.emptyList();
    private Location location;
    private Banner banner;

    private double viewLat = Double.NaN;
    private double viewLng = Double.NaN;
    private double viewMpp = Double.NaN;

    private RouteStripView(Context context, int leftPx) {
        super(context);
        dp = context.getResources().getDisplayMetrics().density;
        haloLegPaint = stroke(COLOR_HALO, 6.5f);
        openLegPaint = stroke(COLOR_OPEN, 3.5f);
        doneLegPaint = stroke(COLOR_DONE, 3f);
        labelPaint.setColor(Color.BLACK);
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        Resources res = context.getResources();
        @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
        int statusBarId = res.getIdentifier("status_bar_height", "dimen", "android");
        statusBarHeightPx = statusBarId > 0 ? res.getDimensionPixelSize(statusBarId) : 24 * dp;

        DisplayMetrics metrics = res.getDisplayMetrics();
        params = new WindowManager.LayoutParams(
                Math.max(metrics.widthPixels - leftPx, 1),
                heightPx(context),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = leftPx;
        params.alpha = MAX_TOUCH_THROUGH_ALPHA;
    }

    private static int heightPx(Context context) {
        return (int) (context.getResources().getDisplayMetrics().heightPixels * HEIGHT_FRACTION);
    }

    /** Creates the strip spanning from {@code leftPx} (right edge of the control card) to the right edge of the screen. */
    static RouteStripView create(Context context, int leftPx) {
        RouteStripView view = new RouteStripView(context, leftPx);
        context.getSystemService(WindowManager.class).addView(view, view.params);
        view.stateListener = StateManager.addListener((newState, oldState) -> view.applyState(newState));
        return view;
    }

    void remove() {
        StateManager.removeListener(stateListener);
        getContext().getSystemService(WindowManager.class).removeView(this);
    }

    private Paint stroke(int color, float widthDp) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStrokeWidth(widthDp * dp);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        return paint;
    }

    private static Paint fill(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    /** "5c" = mission 5, step 3. */
    static String label(int missionIndex, int stepIndex) {
        String step = stepIndex < 26 ? String.valueOf((char) ('a' + stepIndex)) : "." + (stepIndex + 1);
        return (missionIndex + 1) + step;
    }

    private static boolean hasPosition(MissionStep step) {
        POI poi = step.poi;
        return poi != null && poi.type != POIType.unavailable && poi.latitude != null && poi.longitude != null;
    }

    private void applyState(State state) {
        location = state.currentLocation;
        List<Point> newPoints = new ArrayList<>();
        List<Boolean> newLegs = new ArrayList<>();
        if (state.banner != null) {
            List<Mission> missions = new ArrayList<>(state.banner.missions.values());
            // Before "Start", the first mission is the one you're heading for.
            int active = Math.max(state.currentMission, 0);
            if (active < missions.size()) {
                Mission mission = missions.get(active);
                boolean joined = mission.type != MissionType.anyOrder;
                List<MissionStep> steps = mission.steps == null ? Collections.emptyList() : mission.steps;
                for (int i = 0; i < steps.size(); i++) {
                    MissionStep step = steps.get(i);
                    if (!hasPosition(step)) continue;
                    boolean done = state.currentMission == active && state.currentMissionVisitedStepIndexes.contains(i);
                    newLegs.add(joined && !newPoints.isEmpty());
                    newPoints.add(new Point(step.poi.latitude, step.poi.longitude, label(active, i), done));
                }
            }
            if (active + 1 < missions.size()) {
                Mission next = missions.get(active + 1);
                if (next.steps != null) {
                    for (int i = 0; i < next.steps.size(); i++) {
                        MissionStep step = next.steps.get(i);
                        if (hasPosition(step)) {
                            String nextLabel = label(active + 1, i);
                            Point last = newPoints.isEmpty() ? null : newPoints.get(newPoints.size() - 1);
                            if (last != null && DistanceCalculation.distanceMeters(last.lat, last.lng, step.poi.latitude, step.poi.longitude) < SAME_PORTAL_METERS) {
                                // Missions often start where the previous one ends: label that portal "1f/2a" instead of hiding 1f.
                                newPoints.set(newPoints.size() - 1, new Point(last.lat, last.lng, last.label + "/" + nextLabel, last.done));
                            } else {
                                newLegs.add(!newPoints.isEmpty());
                                newPoints.add(new Point(step.poi.latitude, step.poi.longitude, nextLabel, false));
                            }
                            break;
                        }
                    }
                }
            }
        }
        if (state.banner != banner) {
            banner = state.banner;
            viewMpp = Double.NaN; // new banner: frame from scratch
        }
        points = newPoints;
        legBefore = newLegs;
        setVisibility(points.isEmpty() ? GONE : VISIBLE);
        invalidate();
    }

    /** Desired framing {lat, lng, metersPerPixel}: centred on you, or on the first open step before a fix. */
    private double[] desiredFraming() {
        double cLat;
        double cLng;
        if (location != null) {
            cLat = location.getLatitude();
            cLng = location.getLongitude();
        } else {
            Point first = points.get(0);
            for (Point p : points) {
                if (!p.done) {
                    first = p;
                    break;
                }
            }
            cLat = first.lat;
            cLng = first.lng;
        }
        double cosLat = Math.cos(Math.toRadians(cLat));
        List<double[]> offsets = new ArrayList<>();
        for (Point p : points) {
            if (p.done) continue;
            offsets.add(new double[]{
                    Math.toRadians(p.lng - cLng) * EARTH_RADIUS_METERS * cosLat,
                    Math.toRadians(p.lat - cLat) * EARTH_RADIUS_METERS});
        }
        offsets.sort((a, b) -> Double.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        double w = Math.max(getWidth(), 1);
        double h = Math.max(getHeight(), 1);
        double margin = 14 * dp;
        double roomX = Math.max(w / 2 - margin, 1);
        // The system usually places overlay windows below the status bar already; only reserve what still overlaps it.
        int[] onScreen = new int[2];
        getLocationOnScreen(onScreen);
        double hiddenTop = Math.max(statusBarHeightPx - onScreen[1], 0);
        double roomUp = Math.max(h / 2 - hiddenTop - margin, 1);
        double roomDown = Math.max(h / 2 - margin, 1);
        double mpp = VISIBLE_METERS / w;
        for (int i = 0; i < Math.min(NEAREST_OPEN_IN_VIEW, offsets.size()); i++) {
            double dx = offsets.get(i)[0];
            double dy = offsets.get(i)[1];
            mpp = Math.max(mpp, Math.abs(dx) / roomX);
            mpp = Math.max(mpp, dy > 0 ? dy / roomUp : -dy / roomDown);
        }
        mpp = Math.min(mpp, MAX_VISIBLE_METERS / w);
        return new double[]{cLat, cLng, mpp};
    }

    /** Moves the shown framing toward the desired one; returns true while still moving. */
    private boolean easeFraming(double[] d) {
        if (Double.isNaN(viewMpp)) {
            viewLat = d[0];
            viewLng = d[1];
            viewMpp = d[2];
            return false;
        }
        viewLat += (d[0] - viewLat) * EASING;
        viewLng += (d[1] - viewLng) * EASING;
        viewMpp = Math.exp(Math.log(viewMpp) + (Math.log(d[2]) - Math.log(viewMpp)) * EASING);
        double movedPx = Math.max(Math.abs((d[0] - viewLat) * 111_000 / viewMpp), Math.abs((d[1] - viewLng) * 70_000 / viewMpp));
        return movedPx > 0.5 || Math.abs(Math.log(d[2] / viewMpp)) > 0.005;
    }

    private float[] project(double lat, double lng) {
        double dx = Math.toRadians(lng - viewLng) * EARTH_RADIUS_METERS * Math.cos(Math.toRadians(viewLat));
        double dy = Math.toRadians(lat - viewLat) * EARTH_RADIUS_METERS;
        return new float[]{getWidth() / 2f + (float) (dx / viewMpp), getHeight() / 2f - (float) (dy / viewMpp)};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.isEmpty()) return;
        boolean stillMoving = easeFraming(desiredFraming());

        // Legs: done ones first, open ones on top.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 1; i < points.size(); i++) {
                if (!legBefore.get(i)) continue;
                boolean done = points.get(i).done;
                if (done != (pass == 0)) continue;
                float[] a = project(points.get(i - 1).lat, points.get(i - 1).lng);
                float[] b = project(points.get(i).lat, points.get(i).lng);
                Path path = new Path();
                path.moveTo(a[0], a[1]);
                path.lineTo(b[0], b[1]);
                if (!done) canvas.drawPath(path, haloLegPaint);
                canvas.drawPath(path, done ? doneLegPaint : openLegPaint);
            }
        }

        // Steps: small grey dots when done, labelled yellow dots when open.
        for (Point p : points) {
            if (!p.done) continue;
            float[] xy = project(p.lat, p.lng);
            canvas.drawCircle(xy[0], xy[1], 3 * dp, doneFill);
        }
        float radius = 7.5f * dp;
        for (Point p : points) {
            if (p.done) continue;
            float[] xy = project(p.lat, p.lng);
            labelPaint.setTextSize((p.label.length() >= 3 ? 7.5f : 9.5f) * dp);
            // Round dot for short labels; a pill wide enough for longer ones such as "1f/2a".
            float halfWidth = Math.max(radius, labelPaint.measureText(p.label) / 2f + 2.5f * dp);
            float halo = 1.6f * dp;
            canvas.drawRoundRect(xy[0] - halfWidth - halo, xy[1] - radius - halo, xy[0] + halfWidth + halo, xy[1] + radius + halo, radius + halo, radius + halo, haloFill);
            canvas.drawRoundRect(xy[0] - halfWidth, xy[1] - radius, xy[0] + halfWidth, xy[1] + radius, radius, radius, openFill);
            canvas.drawText(p.label, xy[0], xy[1] - (labelPaint.descent() + labelPaint.ascent()) / 2f, labelPaint);
        }

        if (location != null) {
            float[] me = project(location.getLatitude(), location.getLongitude());
            canvas.drawCircle(me[0], me[1], 9 * dp, haloFill);
            canvas.drawCircle(me[0], me[1], 7.5f * dp, whiteFill);
            canvas.drawCircle(me[0], me[1], 5.5f * dp, meFill);
        }
        if (stillMoving) postInvalidateOnAnimation();
    }
}
