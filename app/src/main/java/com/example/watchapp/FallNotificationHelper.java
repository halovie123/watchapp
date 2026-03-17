package com.example.watchapp;

// FallNotificationHelper.java
// Hiện system notification khi ESP32 phát hiện ngã
// Cần thêm vào AndroidManifest.xml:
//   <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
//   <uses-permission android:name="android.permission.VIBRATE"/>

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.app.NotificationCompat;

public class FallNotificationHelper {

    private static final String CHANNEL_ID   = "fall_alert_channel";
    private static final String CHANNEL_NAME = "Cảnh báo ngã";
    private static final int    NOTIF_ID     = 9001;

    // Gọi từ BLEService khi nhận được fall event qua BLE
    public static void showFallNotification(Context context, float magnitude) {
        createNotificationChannel(context);

        // Mở MainActivity khi bấm notification
        Intent openAppIntent = new Intent(context, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openAppIntent.putExtra("from_fall_alert", true);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Nội dung notification
        String title   = "⚠️ Phát hiện ngã!";
        String message = String.format(
                "Có thể đã xảy ra té ngã (%.1fg). Nhấn để kiểm tra.", magnitude);

        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_MAX) // Heads-up notification
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setSound(soundUri)
                .setVibrate(new long[]{0, 500, 200, 500, 200, 500}) // Rung 3 lần
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true) // Hiện ngay cả khi màn hình tắt
                .setLights(0xFFFF0000, 500, 500) // Đèn LED đỏ nháy
                .build();

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, notification);

        // Rung thêm qua Vibrator API (đảm bảo rung dù điện thoại im lặng)
        vibrate(context);
    }

    // Tạo notification channel (bắt buộc Android 8+)
    private static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Kiểm tra nếu đã tạo rồi thì bỏ qua
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        AudioAttributes audioAttr = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH  // Heads-up
        );
        channel.setDescription("Cảnh báo khi phát hiện ngã từ smartwatch");
        channel.enableLights(true);
        channel.setLightColor(0xFFFF0000); // Đỏ
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
        channel.setSound(soundUri, audioAttr);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        nm.createNotificationChannel(channel);
    }

    // Rung thiết bị
    private static void vibrate(Context context) {
        long[] pattern = {0, 600, 200, 600, 200, 600};

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vm.getDefaultVibrator().vibrate(
                        VibrationEffect.createWaveform(pattern, -1));
            }
        } else {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            }
        }
    }
}