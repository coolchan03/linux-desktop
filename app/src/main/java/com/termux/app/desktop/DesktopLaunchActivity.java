package com.termux.app.desktop;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.app.TermuxInstaller;
import com.termux.shared.android.PermissionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's only launcher entry ("Linux Desktop"). Asks once for the permissions the desktop and its
 * apps can use (camera, microphone, location, notifications, Bluetooth, media, all-files access), then:
 * sets up Termux if needed, lets you pick what to install, installs the desktop core while showing a
 * progress bar, starts the X server and XFCE in the background, and opens the built-in display.
 * Apps and tools install in the background afterwards. The plain terminal is available from the
 * icon's long-press shortcuts, as is "Stop desktop".
 */
public class DesktopLaunchActivity extends Activity {

    /** Sent by the "Stop desktop" shortcut. */
    public static final String ACTION_STOP_DESKTOP = "com.termux.app.desktop.STOP_DESKTOP";

    private static final int REQUEST_RUNTIME_PERMISSIONS = 4100;
    private static final int REQUEST_ALL_FILES_ACCESS = 4101;
    private static final String PREFS = "desktop_launcher";
    private static final String KEY_PERMISSIONS_ASKED = "permissions_asked";
    private static final String KEY_KEEP_ALIVE_ASKED = "keep_alive_asked";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mStatus;
    private ProgressBar mBar;
    private Button mRetry;
    private Button mInstall;
    private RadioGroup mPresets;
    private boolean mBegun;
    private boolean mFinished;
    private boolean mResumed;
    private boolean mSetupFinishedInBackground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ACTION_STOP_DESKTOP.equals(getIntent().getAction())) {
            DesktopLauncher.stopDesktop(this);
            Toast.makeText(this, "Desktop stopped", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

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
            requestAllFilesAccessOrBegin();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // "Stop desktop" shortcut while this screen is still open (e.g. during setup).
        if (ACTION_STOP_DESKTOP.equals(intent.getAction())) {
            DesktopLauncher.stopDesktop(this);
            Toast.makeText(this, "Desktop stopped", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        // Setup finished while the app was in the background: the display can only be opened from the foreground.
        if (mSetupFinishedInBackground) {
            mSetupFinishedInBackground = false;
            afterSetup();
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    // ---- Permissions ----

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
        requestAllFilesAccessOrBegin();
    }

    private void requestAllFilesAccessOrBegin() {
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
        if (requestCode == REQUEST_ALL_FILES_ACCESS) begin();
    }

    // ---- UI ----

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * density);
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

        mPresets = new RadioGroup(this);
        mPresets.setVisibility(View.GONE);
        for (DesktopLauncher.Preset preset : DesktopLauncher.Preset.values()) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setTag(preset);
            button.setText(preset.title + ": " + preset.description);
            button.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
            mPresets.addView(button);
            if (preset == DesktopLauncher.Preset.STANDARD) button.setChecked(true);
        }
        root.addView(mPresets);

        mInstall = new Button(this);
        mInstall.setText("Install");
        mInstall.setVisibility(View.GONE);
        mInstall.setOnClickListener(v -> {
            RadioButton checked = findViewById(mPresets.getCheckedRadioButtonId());
            DesktopLauncher.writePreset(checked == null ? DesktopLauncher.Preset.STANDARD : (DesktopLauncher.Preset) checked.getTag());
            mPresets.setVisibility(View.GONE);
            mInstall.setVisibility(View.GONE);
            runSetup();
        });
        root.addView(mInstall);

        mRetry = new Button(this);
        mRetry.setText("Try again");
        mRetry.setVisibility(View.GONE);
        mRetry.setOnClickListener(v -> { mRetry.setVisibility(View.GONE); runSetup(); });
        root.addView(mRetry);

        Button debug = new Button(this, null, android.R.attr.borderlessButtonStyle);
        debug.setText("Copy debug info");
        debug.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Linux Desktop debug info", DesktopLauncher.collectDebugInfo(this)));
            Toast.makeText(this, "Copied. Paste it into your message.", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams debugParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        debugParams.topMargin = pad;
        root.addView(debug, debugParams);

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

    // ---- Flow: bootstrap -> choose + install desktop core -> keep-alive hints -> start desktop ----

    private void begin() {
        if (mBegun) return;
        mBegun = true;
        setStatus("Preparing the desktop environment...", -1);
        // Shows its own dialog while unpacking on first run; calls back immediately if already done.
        TermuxInstaller.setupBootstrapIfNeeded(this, this::afterBootstrap);
    }

    private void afterBootstrap() {
        if (!DesktopLauncher.isBootstrapInstalled()) {
            setStatus("Setup could not finish. Check your internet connection and storage, then reopen the app.", 0);
            return;
        }
        if (DesktopLauncher.isInstalled()) {
            afterSetup();
            return;
        }
        DesktopLauncher.Progress p = DesktopLauncher.readProgress();
        if ("running".equals(p.state) && p.active) {
            runSetup(); // reopened while an earlier setup shell is still running: keep watching it
        } else {
            setStatus("What should be installed on top of the desktop? Apps install in the background, so you can start using the desktop right after the first few minutes.\n\n"
                + "Free storage: " + String.format(java.util.Locale.US, "%.1f", freeStorageGb()) + " GB. Roughly needed: Minimal 3 GB, Standard 5 GB, Full 9 GB.\n"
                + (hasInternet() ? "Wi-Fi is recommended; the download is large." : "No internet connection detected. Connect before installing."), 0);
            mBar.setVisibility(View.GONE);
            mPresets.setVisibility(View.VISIBLE);
            mInstall.setVisibility(View.VISIBLE);
        }
    }

    private double freeStorageGb() {
        try {
            return new StatFs(getFilesDir().getPath()).getAvailableBytes() / 1e9;
        } catch (Exception e) {
            return Double.MAX_VALUE; // unknown: do not block
        }
    }

    private boolean hasInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.getActiveNetwork() == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void runSetup() {
        mBar.setVisibility(View.VISIBLE);
        DesktopLauncher.Progress p = DesktopLauncher.readProgress();
        boolean alreadyRunning = "running".equals(p.state) && p.active;
        if (!alreadyRunning && !hasInternet()) {
            setStatus("No internet connection. Connect to Wi-Fi or mobile data, then tap Try again.", 0);
            mRetry.setVisibility(View.VISIBLE);
            return;
        }
        if (!alreadyRunning && freeStorageGb() < 3.0) {
            setStatus("Not enough free storage (" + String.format(java.util.Locale.US, "%.1f", freeStorageGb())
                + " GB). Free up at least 3 GB, then tap Try again.", 0);
            mRetry.setVisibility(View.VISIBLE);
            return;
        }
        if (!alreadyRunning) {
            setStatus("Installing the desktop. This takes about 5-10 minutes and needs internet.", 0);
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
            if (mResumed) afterSetup();
            else mSetupFinishedInBackground = true;
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

    private void afterSetup() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_KEEP_ALIVE_ASKED, false)) {
            prefs.edit().putBoolean(KEY_KEEP_ALIVE_ASKED, true).apply();
            askKeepAlive();
        } else {
            startDesktop();
        }
    }

    /** Android kills background processes aggressively; a desktop session is a lot of them. */
    private void askKeepAlive() {
        if (Build.VERSION.SDK_INT >= 23 && !PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this);
        }
        if (Build.VERSION.SDK_INT < 31) {
            startDesktop();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Keep the desktop running")
            .setMessage("Android 12 and newer can stop background programs, which ends a desktop session. "
                + "If the desktop closes by itself, open Developer options and turn on \"Disable child process restrictions\" "
                + "(Android 14+), or \"Disable monitoring of phantom processes\" on Android 12-13.")
            .setPositiveButton("Open Developer options", (d, w) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, "Turn on Developer options first (tap Build number 7 times in About phone).", Toast.LENGTH_LONG).show();
                }
                startDesktop();
            })
            .setNegativeButton("Skip", (d, w) -> startDesktop())
            .setOnCancelListener(d -> startDesktop())
            .show();
    }

    private void startDesktop() {
        if (mFinished) return;
        mFinished = true;
        mBar.setVisibility(View.VISIBLE);
        setStatus("Starting desktop...", -1);
        DesktopLauncher.applyDisplayDefaults(this);
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
