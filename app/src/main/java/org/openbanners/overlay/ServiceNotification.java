package org.openbanners.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import org.openbanners.overlay.api.MissionStep;
import org.openbanners.overlay.api.Objective;
import org.openbanners.overlay.api.POIType;

import java.util.Map;

final class ServiceNotification {
    static final String DEFAULT_CHANNEL_ID = "default";
    static final String STEP_IN_RANGE_CHANNEL_ID = "step";

    private static final int DEFAULT_NOTIFICATION_ID = 1;
    private static final int STEP_IN_RANGE_NOTIFICATION_ID_BASE = 2;
    private static final int STEP_IN_RANGE_NOTIFICATION_ID_MULTIPLIER = 100;
    private static final int REQUEST_ROTATE_LEFT = 1;
    private static final int REQUEST_ROTATE_RIGHT = 2;

    static void createNotificationChannels(Context context) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);

        NotificationChannel channelDefault = new NotificationChannel(DEFAULT_CHANNEL_ID, context.getString(R.string.notificationChannelDefaultTitle), NotificationManager.IMPORTANCE_DEFAULT);
        channelDefault.setImportance(NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channelDefault);

        NotificationChannel channelStepInRange = new NotificationChannel(STEP_IN_RANGE_CHANNEL_ID, context.getString(R.string.notificationChannelStepInRangeTitle), NotificationManager.IMPORTANCE_DEFAULT);
        channelStepInRange.setImportance(NotificationManager.IMPORTANCE_NONE);
        notificationManager.createNotificationChannel(channelStepInRange);
    }

    static void updateDefaultNotification(Service context, State state) {
        Intent deleteIntent = new Intent(context, StopDetectionReceiver.class);
        PendingIntent pendingIntentCancel = PendingIntent.getBroadcast(
                context,
                0,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                R.drawable.ic_notification,
                context.getString(R.string.notificationAction),
                pendingIntentCancel
        ).build();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context.getApplicationContext(), DEFAULT_CHANNEL_ID);
        boolean compassCorrection = state.banner != null && compassInUse(context);
        if (state.banner == null) {
            builder
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notificationTitle))
                    .setProgress(0, 0, false);
        } else {
            double totalDistance = DistanceCalculation.getTotalDistance(state.banner);
            double remainingDistance = DistanceCalculation.getRemainingDistance(state.banner, state.currentMission, state.currentMissionVisitedStepIndexes, state.currentLocation);
            String text = context.getString(R.string.notificationRemaining, remainingDistance / 1_000, totalDistance / 1_000);
            if (compassCorrection) {
                text += "\n" + compassCorrectionText(context, state.headingOffset);
            }
            builder
                    .setContentTitle(state.banner.title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setProgress((int) totalDistance, Math.max((int) (totalDistance - remainingDistance), 0), false);
        }
        if (compassCorrection) {
            int step = HeadingOffsetReceiver.stepDegrees(context);
            builder.addAction(rotateAction(context, REQUEST_ROTATE_LEFT, 1, context.getString(R.string.notificationRotateLeft, step)));
            builder.addAction(rotateAction(context, REQUEST_ROTATE_RIGHT, -1, context.getString(R.string.notificationRotateRight, step)));
        }
        // Tapping the notification opens Ingress, and with it the overlay (screach 10-24)
        Ingress.createLaunchIngressIntent(context).ifPresent(builder::setContentIntent);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_notification)
                .addAction(action)
                .setOnlyAlertOnce(true)
                .setAllowSystemGeneratedContextualActions(false)
                .setOngoing(true)
                .build();

        context.startForeground(DEFAULT_NOTIFICATION_ID, notification);
    }

    /** The map follows the compass, or the needle shows your heading: only then is a correction useful. */
    private static boolean compassInUse(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!RouteDisclaimer.isRouteStripEnabled(context, preferences)) return false;
        boolean followCompass = "compass".equals(preferences.getString(context.getString(R.string.route_orientation), "north"));
        boolean needleHeading = preferences.getBoolean(context.getString(R.string.route_compass_always), false)
                && "heading".equals(preferences.getString(context.getString(R.string.route_compass_needle), "north"));
        return followCompass || needleHeading;
    }

    private static String compassCorrectionText(Context context, int offset) {
        if (offset > 0) return context.getString(R.string.notificationCompassLeft, offset);
        if (offset < 0) return context.getString(R.string.notificationCompassRight, -offset);
        return context.getString(R.string.notificationCompassNone);
    }

    private static NotificationCompat.Action rotateAction(Context context, int requestCode, int direction, String title) {
        Intent intent = new Intent(context, HeadingOffsetReceiver.class)
                .putExtra(HeadingOffsetReceiver.EXTRA_DIRECTION, direction);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        return new NotificationCompat.Action.Builder(R.drawable.ic_notification, title, pendingIntent).build();
    }

    static void updateStepReachedNotification(Context context, State newState, State oldState) {
        Map<Integer, MissionStep> newStepsInRange = State.getNewStepsInRange(newState, oldState);
        for (Map.Entry<Integer, MissionStep> entry : newStepsInRange.entrySet()) {
            int stepIndex = entry.getKey();
            MissionStep step = entry.getValue();
            if (step.poi != null && step.objective != null && step.poi.type != POIType.unavailable) {
                NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
                NotificationCompat.Builder builder = new NotificationCompat.Builder(context.getApplicationContext(), STEP_IN_RANGE_CHANNEL_ID)
                        .setContentTitle(context.getString(getObjectiveName(step.objective)))
                        .setContentText(step.poi.title)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setAllowSystemGeneratedContextualActions(false)
                        .setTimeoutAfter(8_000);
                Ingress.createLaunchIngressIntent(context).ifPresent(builder::setContentIntent);
                int notificationId = STEP_IN_RANGE_NOTIFICATION_ID_BASE
                        + STEP_IN_RANGE_NOTIFICATION_ID_MULTIPLIER * newState.currentMission
                        + stepIndex;
                notificationManager.notify(notificationId, builder.build());
            }
        }
    }

    private static @StringRes
    int getObjectiveName(Objective objective) {
        switch (objective) {
            case hack:
                return R.string.objectiveHack;
            case captureOrUpgrade:
                return R.string.objectiveCaptureOrUpgrade;
            case createLink:
                return R.string.objectiveCreateLink;
            case createField:
                return R.string.objectiveCreateField;
            case installMod:
                return R.string.objectiveInstallMod;
            case takePhoto:
                return R.string.objectiveTakePhoto;
            case viewWaypoint:
                return R.string.objectiveViewWaypoint;
            case enterPassphrase:
                return R.string.objectiveEnterPassphrase;
            default:
                throw new IllegalArgumentException(objective.toString());
        }
    }
}
