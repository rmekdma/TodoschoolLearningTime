package com.example.notificationtoneprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final String CHANNEL_ID = "bundled-tone-diagnostic-v1";
    private static final int NOTIFICATION_ID = 9100;
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String EXTRA_ACTION = "action";
    private static final String ACTION_POST = "post_bundled_tone";

    private TextView statusView;
    private Button postButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContent());
        refreshPermissionState();

        if (ACTION_POST.equals(getIntent().getStringExtra(EXTRA_ACTION))) {
            postOneTest();
        }
    }

    private LinearLayout createContent() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView description = new TextView(this);
        description.setText(
                "Standalone notification audio diagnostic. It uses one short tone bundled in "
                        + "this app. It does not use Accessibility, learning data, network access, "
                        + "or the system default sound.");
        layout.addView(description, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button permissionButton = new Button(this);
        permissionButton.setText("Allow notifications");
        permissionButton.setOnClickListener(view -> requestNotificationPermission());
        layout.addView(permissionButton, buttonParams());

        postButton = new Button(this);
        postButton.setText("Post one bundled-tone test");
        postButton.setOnClickListener(view -> postOneTest());
        layout.addView(postButton, buttonParams());

        statusView = new TextView(this);
        layout.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    private LinearLayout.LayoutParams buttonParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshPermissionState() {
        boolean granted = hasNotificationPermission();
        postButton.setEnabled(granted);
        statusView.setText(granted
                ? "Notifications are allowed. Posting still requires an explicit test action."
                : "Tap Allow notifications, then approve the Android permission prompt.");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSION_REQUEST_CODE);
        } else {
            refreshPermissionState();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            refreshPermissionState();
        }
    }

    private void postOneTest() {
        if (!hasNotificationPermission()) {
            statusView.setText("No test posted. Allow notifications first.");
            refreshPermissionState();
            return;
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            statusView.setText("NotificationManager is unavailable; no test posted.");
            return;
        }

        createChannel(manager);
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 1, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Bundled notification tone test")
                .setContentText("A short app-bundled tone was requested.")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setShowWhen(false)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
        statusView.setText("One test notification was posted. No test posts automatically.");
    }

    private void createChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Bundled tone diagnostic", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("One explicit test using an app-bundled notification tone.");
        channel.setSound(bundledToneUri(), new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private Uri bundledToneUri() {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(getPackageName())
                .appendPath(Integer.toString(R.raw.notification_tone))
                .build();
    }
}
