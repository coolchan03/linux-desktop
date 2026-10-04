package com.termux.app.desktop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.os.Environment;
import android.provider.Settings;

import com.termux.app.TermuxInstaller;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's only launcher entry ("Linux Desktop"). Asks once for the permissions the desktop and its
 * apps can use (camera, microphone, location, notifications, Bluetooth, media, all-files access), then:
 * sets up Termux if needed, installs the desktop on first use while showing a progress bar, starts the
 * X server and XFCE in the background, and opens the built-in display. The plain terminal is available
 * from the icon's long-press shortcuts.
 */
public class DesktopLaunchActivity extends Activity {

    private static final int REQUEST_RUNTIME_PERMISSIONS = 4100;
    private static final int REQUEST_ALL_FILES_ACCESS = 4101;
    private static final String PREFS = "desktop_launcher";
    private static final String KEY_PERMISSIONS_ASKED = "permissions_asked";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PERMISSIONS_ASKED, false)) {
            begin();
            return;
        }
        prefs.edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply();

        List<String> missing = new ArrayList<>();
        for (String permission : runtimePermissions())
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                missing.add(permission);

        if (!missing.isEmpty())
            requestPermissions(missing.toArray(new String[0]), REQUEST_RUNTIME_PERMISSIONS);
        else
            requestAllFilesAccessOrLaunch();
    }

    private static List<String> runtimePermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        return permissions;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Denied permissions are not fatal; the desktop works without them.
        requestAllFilesAccessOrLaunch();
    }

    private void requestAllFilesAccessOrLaunch() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQUEST_ALL_FILES_ACCESS);
                return;
            } catch (Exception e) {
                // No such settings screen on this device; carry on without it.
            }
        }
        begin();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        begin();
    }

    // ---- UI ----

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private ProgressBar mBar;
    private Button mRetry;
    private boolean mBegun;
    private boolean mFinished;

    private void buildUi() {
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Linux Desktop");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        mStatus = new TextView(this);
        mStatus.setText("Starting...");
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setPadding(0, pad, 0, pad);
        root.addView(mStatus);

        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setIndeterminate(true);
        mBar.setMax(100);
        root.addView(mBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mRetry = new Button(this);
        mRetry.setText("Try again");
        mRetry.setVisibility(View.GONE);
        mRetry.setOnClickListener(v -> { mRetry.setVisibility(View.GONE); runSetup(); });
        root.addView(mRetry);

        setContentView(root);
    }

    private void setStatus(String text, int percent) {
        mStatus.setText(text);
        if (percent < 0) {
            mBar.setIndeterminate(true);
        } else {
            mBar.setIndeterminate(false);
            mBar.setProgress(percent);
        }
    }

    // ---- Flow: bootstrap -> install desktop -> start desktop ----

    private void begin() {
        if (mBegun) return;
        mBegun = true;
        setStatus("Preparing Termux...", -1);
        // Shows its own dialog while unpacking on first run; calls back immediately if already done.
        TermuxInstaller.setupBootstrapIfNeeded(this, this::afterBootstrap);
    }

    private void afterBootstrap() {
        if (!DesktopLauncher.isBootstrapInstalled()) {
            setStatus("Termux could not be set up. Check your internet connection and storage, then reopen the app.", 0);
            return;
        }
        if (DesktopLauncher.isInstalled()) startDesktop();
        else runSetup();
    }

    private void runSetup() {
        DesktopLauncher.Progress p = DesktopLauncher.readProgress();
        // Reopened while an earlier setup shell is still running: just keep watching it.
        boolean alreadyRunning = "running".equals(p.state) && p.active;
        if (!alreadyRunning) {
            setStatus("Installing your Linux desktop. This takes 15-30 minutes and needs internet.", 0);
            DesktopLauncher.resetProgress();
            if (!DesktopLauncher.startSetup(this)) {
                setStatus("Could not write the setup scripts.", 0);
                return;
            }
        }
        pollSetup();
    }

    private void pollSetup() {
        if (isFinishing() || isDestroyed() || mFinished) return;
        if (DesktopLauncher.isInstalled()) {
            startDesktop();
            return;
        }
        DesktopLauncher.Progress p = DesktopLauncher.readProgress();
        if ("failed".equals(p.state)) {
            setStatus(p.message, 0);
            mRetry.setVisibility(View.VISIBLE);
            return;
        }
        if (!p.message.isEmpty()) setStatus(p.message + "\nYou can leave the app; installation continues.", p.percent);
        mHandler.postDelayed(this::pollSetup, 700);
    }

    private void startDesktop() {
        if (mFinished) return;
        mFinished = true;
        setStatus("Starting desktop...", -1);
        if (!DesktopLauncher.startDesktop(this)) {
            setStatus("Could not start the desktop.", 0);
            mFinished = false;
            return;
        }
        // The display waits for the X server to connect, so it can open right away.
        mHandler.postDelayed(() -> {
            Intent display = new Intent().setClassName(getPackageName(), "com.termux.x11.MainActivity");
            display.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(display);
            finish();
        }, 1500);
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
