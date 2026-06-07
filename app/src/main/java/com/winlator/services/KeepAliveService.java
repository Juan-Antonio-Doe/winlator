package com.winlator.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import android.content.SharedPreferences;

import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.core.AppUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that keeps the Winlator container process alive while a wine
 * session is in the background. Without it, Android can kill the app process when the screen is
 * locked or in the background, taking the wine container with it.
 */
public class KeepAliveService extends Service {
    private static final String TAG = "ContainerKeepAlive";

    private static final String CHANNEL_ID = "winlator_container_keepalive";

    private static final String ACTION_SESSION_STOP = "com.winlator.action.SESSION_STOP";
    private static final String ACTION_SESSION_PAUSE = "com.winlator.action.SESSION_PAUSE";
    private static final String ACTION_SESSION_RESUME = "com.winlator.action.SESSION_RESUME";

    private static final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private static final AtomicBoolean serviceRunning = new AtomicBoolean(false);

    private static boolean isContainerPaused = false;

    private PowerManager.WakeLock wakeLock;

    public static void startSession(Context ctx) {
        if (ctx == null) return;
        sessionActive.set(true);
    }

    public static void stopSession(Context ctx) {
        if (ctx == null) return;
        if (sessionActive.compareAndSet(true, false)) {
            sendCommand(ctx, ACTION_SESSION_STOP, null);
        }
    }

    public static void onPauseSession(Context ctx) {
        if (ctx == null) return;
        sendCommand(ctx, ACTION_SESSION_PAUSE, null);
    }

    public static void onResumeSession(Context ctx) {
        if (ctx == null) return;
        sendCommand(ctx, ACTION_SESSION_RESUME, null);
    }

    private static boolean hasReason() {
        return sessionActive.get() && isContainerPaused;
    }

    private static void sendCommand(Context ctx, String action, @Nullable String tag) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(ctx);
        if (!preferences.getBoolean("enable_background_service", false)
                && !ACTION_SESSION_STOP.equals(action)) return;

        Context app = ctx.getApplicationContext();
        Intent intent = new Intent(app, KeepAliveService.class);
        intent.setAction(action);
        if (tag != null) intent.putExtra("tag", tag);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ACTION_SESSION_PAUSE.equals(action)) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception e) {
            // If starting the service fails, try starting it as a foreground service as a fallback.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            }
            Log.w(TAG, "Failed to send command " + action, e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Keep the CPU alive to prevent OS from killing the process when the device is locked
        // or the app is in the background.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Winlator:KeepAlive");
        }

        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_SESSION_PAUSE.equals(action)) {
            if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
            isContainerPaused = true;
        } else if (ACTION_SESSION_RESUME.equals(action)) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            isContainerPaused = false;
        }
        else if (ACTION_SESSION_STOP.equals(action)) {
            sessionActive.set(false);
            isContainerPaused = false;
        }

        // Always promote to foreground first so Android does not consider
        // the start a violation (and so the notification reflects current
        // reasons), even if the command immediately tells us to stop.
        ensureForeground();
        serviceRunning.set(true);

        if (!hasReason()) {
            Log.d(TAG, "No active reason; stopping keep-alive service");
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);
        }
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        Notification n = buildNotification();
        int notificationId = AppUtils.generateNotificationId(this, "winlator.keepAlive");
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            }
            else {
                startForeground(notificationId, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to startForeground", e);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stopForeground", e);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Winlator session keep-alive",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                getString(R.string.keep_alive_service_notification_channel_desc));
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        boolean container = sessionActive.get();
        String content;
        if (container) {
            if (isContainerPaused)
                content = getString(R.string.keep_alive_service_notification_content_container_p);
            else
                content = getString(R.string.keep_alive_service_notification_content_container_r);
        } else {
            content = getString(R.string.keep_alive_service_notification_content_else);
        }

        Intent openIntent = new Intent(this, XServerDisplayActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Winlator")
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed (user swipe). Tearing down session and exiting process.");
        sessionActive.set(false);

        // Teardown the service and terminate the process to ensure
        // no background notification remains after the app is closed.
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);

            // Match the previous swipe behaviour: actually exit the process.
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 1500L);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        serviceRunning.set(false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
