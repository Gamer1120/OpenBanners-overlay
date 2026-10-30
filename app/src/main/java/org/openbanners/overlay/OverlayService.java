package org.openbanners.overlay;

import android.os.Build;

import android.location.LocationRequest;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import java.util.function.BiConsumer;

public class OverlayService extends Service {
    private OverlayView overlayView;
    private RouteStripView routeStripView;
    /** Left edge the route strip was created with (the card's width). */
    private int routeStripLeft;
    // SharedPreferences only keeps a weak reference to change listeners.
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (sharedPreferences, key) -> applyPreferences(sharedPreferences);

    private BiConsumer<State, State> stateNotificationListener;

    private BiConsumer<State, State> stateLocationListener;

    private LocationListener locationListener;

    /** "Only show over Ingress": checks which app is in front once a second. */
    private static final long FOREGROUND_CHECK_MILLIS = 1_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ForegroundApp foregroundApp;
    private boolean overlayHidden;
    private final Runnable foregroundCheck = new Runnable() {
        @Override
        public void run() {
            setOverlayHidden(foregroundApp != null && !foregroundApp.overlayWanted());
            handler.postDelayed(this, FOREGROUND_CHECK_MILLIS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceNotification.resetForegroundFailure();
        // A new share or link while the overlay runs starts the service again: keep one listener of each.
        if (stateNotificationListener == null) stateNotificationListener = StateManager.addListener(this::handleStateNotification);
        if (stateLocationListener == null) stateLocationListener = StateManager.addListener(this::handleStateLocation);
        if (intent == null) {
            // Not restarted by the system (START_NOT_STICKY), but don't crash if it happens.
            stopSelf();
            return START_NOT_STICKY;
        }
        addOverlay(intent);
        showLocationHintOnce();
        initPreferences();
        return START_NOT_STICKY;
    }

    /** Key of the flag that the "location is off" hint was shown. */
    private static final String LOCATION_HINT_SHOWN = "location_hint_shown";

    /**
     * Without location the overlay still works, but distances and your position stay empty: say so once,
     * without asking for the permission here (Settings → Use location does that).
     */
    private void showLocationHintOnce() {
        if (LocationPermission.granted(this)) return;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getBoolean(LOCATION_HINT_SHOWN, false)) return;
        preferences.edit().putBoolean(LOCATION_HINT_SHOWN, true).apply();
        Toast.makeText(getApplicationContext(), R.string.location_off_hint, Toast.LENGTH_LONG).show();
    }

    private void initPreferences() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        RouteDisclaimer.enforce(this, preferences);
        applyPreferences(preferences);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    private void applyPreferences(SharedPreferences preferences) {
        StateManager.updateState(State::locationEnabled);
        if (overlayView != null) {
            overlayView.applyCardPreferences(preferences);
        }
        applyRouteStrip(preferences);
        boolean ingressOnly = preferences.getBoolean(getString(R.string.ingress_only), false);  // without Usage access the check finds nothing and the overlay stays
        handler.removeCallbacks(foregroundCheck);
        if (ingressOnly) {
            if (foregroundApp == null) foregroundApp = new ForegroundApp(this);
            handler.post(foregroundCheck);
        } else {
            foregroundApp = null;
            setOverlayHidden(false);
        }
        // The compass correction buttons depend on the map orientation and the step setting.
        ServiceNotification.createNotificationChannels(this);
        ServiceNotification.updateDefaultNotification(this, StateManager.getState());
    }

    /** Shows, hides or moves the route strip; it starts at the card's right edge. */
    private void applyRouteStrip(SharedPreferences preferences) {
        int cardWidth = overlayView == null ? 0 : overlayView.getCardWidth();
        boolean showRouteStrip = RouteDisclaimer.isRouteStripEnabled(this, preferences);
        if (routeStripView != null && cardWidth != routeStripLeft) {
            // The card changed size: the strip starts at its right edge.
            routeStripView.remove();
            routeStripView = null;
        }
        if (showRouteStrip && routeStripView == null) {
            routeStripView = RouteStripView.create(this, cardWidth);
            routeStripLeft = cardWidth;
        } else if (!showRouteStrip && routeStripView != null) {
            routeStripView.remove();
            routeStripView = null;
        }
        if (routeStripView != null) {
            routeStripView.applyPreferences(preferences);
            routeStripView.setHidden(overlayHidden);
        }
    }

    private void setOverlayHidden(boolean hidden) {
        // Every time, also for views added since the last check; both skip unchanged values.
        overlayHidden = hidden;
        int visibility = hidden ? View.GONE : View.VISIBLE;
        if (overlayView != null && overlayView.getVisibility() != visibility) overlayView.setVisibility(visibility);
        if (routeStripView != null) routeStripView.setHidden(hidden);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(foregroundCheck);
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(preferenceListener);
        StateManager.removeListener(stateLocationListener);
        StateManager.removeListener(stateNotificationListener);
        stateLocationListener = null;
        stateNotificationListener = null;
        removeOverlay();
        removeLocationListening();
        ServiceNotification.cancelDefaultNotification(this);
    }

    private void addOverlay(Intent intent) {
        removeOverlay();
        overlayView = OverlayView.create(this, intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getBooleanExtra(MainActivity.EXTRA_AUTOSTART, false),
                DeepLink.safeOrigin(intent.getStringExtra(MainActivity.EXTRA_ORIGIN)));
        // On the last mission the card can change size (Close items instead of Start / Next): keep the strip next to it.
        overlayView.setCardChangedListener(() -> handler.post(() -> {
            if (overlayView != null) applyRouteStrip(PreferenceManager.getDefaultSharedPreferences(this));
        }));
    }

    private void removeOverlay() {
        if (routeStripView != null) {
            routeStripView.remove();
            routeStripView = null;
        }
        if (overlayView != null) {
            overlayView.remove();
            overlayView = null;
        }
    }

    private void handleStateNotification(State newState, State oldState) {
        ServiceNotification.createNotificationChannels(this);
        ServiceNotification.updateDefaultNotification(this, newState);
        ServiceNotification.updateStepReachedNotification(this, newState, oldState);
        ClipboardHandler.updateClipboard(this, newState, oldState);
    }

    private void handleStateLocation(State newState, State oldState) {
        if (newState.locationEnabled) {
            addLocationListening();
        } else {
            removeLocationListening();
        }
    }

    private void addLocationListening() {
        // Without permission nothing is requested: distances and the route strip's position just stay empty.
        if (locationListener == null && LocationPermission.granted(this)) {
            LocationManager locationManager = getSystemService(LocationManager.class);
            locationListener = location -> StateManager.updateState(state -> state.location(location));
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Walking pace with a 40 m step radius needs GPS; the default (balanced) request may stay on Wi-Fi/cell.
                    LocationRequest request = new LocationRequest.Builder(1_000)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .build();
                    locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, getMainExecutor(), locationListener);
                } else {
                    locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 100, 0, locationListener);
                }
            } catch (RuntimeException e) {
                // Permission revoked in between, or no fused provider: try again on the next state change.
                locationListener = null;
            }
        }
    }

    private void removeLocationListening() {
        if (locationListener != null) {
            LocationManager locationManager = getSystemService(LocationManager.class);
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
    }
}
