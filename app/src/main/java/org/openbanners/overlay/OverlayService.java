package org.openbanners.overlay;

import android.os.Build;

import android.location.LocationRequest;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;

import androidx.preference.PreferenceManager;

import java.util.function.BiConsumer;

public class OverlayService extends Service {
    private OverlayView overlayView;
    private RouteStripView routeStripView;
    // SharedPreferences only keeps a weak reference to change listeners.
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (sharedPreferences, key) -> applyPreferences(sharedPreferences);

    private BiConsumer<State, State> stateNotificationListener;

    private BiConsumer<State, State> stateLocationListener;

    private LocationListener locationListener;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stateNotificationListener = StateManager.addListener(this::handleStateNotification);
        stateLocationListener = StateManager.addListener(this::handleStateLocation);
        addOverlay(intent);
        initPreferences();
        return START_NOT_STICKY;
    }

    private void initPreferences() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        applyPreferences(preferences);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    private void applyPreferences(SharedPreferences preferences) {
        StateManager.updateState(State::locationEnabled);
        boolean showRouteStrip = preferences.getBoolean(getString(R.string.route_strip_enable), true);
        if (showRouteStrip && routeStripView == null) {
            routeStripView = RouteStripView.create(this, overlayView == null ? 0 : overlayView.getCardWidth());
        } else if (!showRouteStrip && routeStripView != null) {
            routeStripView.remove();
            routeStripView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(preferenceListener);
        StateManager.removeListener(stateLocationListener);
        StateManager.removeListener(stateNotificationListener);
        removeOverlay();
    }

    private void addOverlay(Intent intent) {
        removeOverlay();
        overlayView = OverlayView.create(this, intent.getStringExtra(Intent.EXTRA_TEXT));
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
        if (locationListener == null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            LocationManager locationManager = getSystemService(LocationManager.class);
            locationListener = location -> StateManager.updateState(state -> state.location(location));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Walking pace with a 40 m step radius needs GPS; the default (balanced) request may stay on Wi-Fi/cell.
                LocationRequest request = new LocationRequest.Builder(1_000)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .build();
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, getMainExecutor(), locationListener);
            } else {
                locationManager.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 100, 0, locationListener);
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
