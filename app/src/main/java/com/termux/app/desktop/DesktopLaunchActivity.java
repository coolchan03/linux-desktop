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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.shared.android.PermissionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The app's only launcher entry ("Linux Desktop"). Asks once for the permissions the desktop and its
 * apps can use (camera, microphone, location, notifications, Bluetooth, media, all-files access), then
 * shows a branded loading screen while it sets up the Linux system, lets you pick what to install,
 * installs the desktop core, starts the X server and XFCE in the background, and opens the built-in
 * display. Apps and tools install in the background afterwards. The plain terminal is available from
 * the icon's long-press shortcuts, as is "Stop desktop".
 */
public class DesktopLaunchActivity extends Activity {

    /** Sent by the "Stop desktop" shortcut. */
    public static final String ACTION_STOP_DESKTOP = "com.termux.app.desktop.STOP_DESKTOP";
    /** Sent by the "Storage location" shortcut. */
    public static final String ACTION_DATA_FOLDER = "com.termux.app.desktop.DATA_FOLDER";

    private static final int REQUEST_RUNTIME_PERMISSIONS = 4100;
    private static final int REQUEST_ALL_FILES_ACCESS = 4101;
    private static final int REQUEST_PICK_FOLDER = 4102;
    private static final String PREFS = "desktop_launcher";
    private static final String KEY_PERMISSIONS_ASKED = "permissions_asked";
    private static final String KEY_KEEP_ALIVE_ASKED = "keep_alive_asked";

    // Same palette as the app icon.
    private static final int COLOR_BACKGROUND = 0xFF141C33;
    private static final int COLOR_CARD = 0xFF1E2A4A;
    private static final int COLOR_ACCENT = 0xFF4FC3F7;
    private static final int COLOR_DONE = 0xFF81C784;
    private static final int COLOR_ERROR = 0xFFFF8A80;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFF8A94A6;

    private enum Step {
        PREPARE("Prepare the Linux system"),
        INSTALL("Install the desktop"),
        START("Start the desktop");

        final String label;
        Step(String label) { this.label = label; }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final TextView[] mStepViews = new TextView[Step.values().length];
    private TextView mHeadline;
    private TextView mDetail;
    private ProgressBar mBar;
    private Button mRetry;
    private Button mInstall;
    private RadioGroup mPresets;
    private boolean mBegun;
    private boolean mFinished;
    private boolean mResumed;
    private boolean mSetupFinishedInBackground;
    private Step mStep = Step.PREPARE;
    private Runnable mRetryAction;
    private LinearLayout mSteps;
    private LinearLayout mStorageBox;
    private TextView mStorageText;
    private boolean mSettingsMode;

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

        if (ACTION_DATA_FOLDER.equals(getIntent().getAction())) {
            showStorageSettings();
            return;
        }

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

        setProgress(Step.PREPARE, "Getting ready", "Android will ask for a few permissions. You can deny any of them.", -1);
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
        } else if (ACTION_DATA_FOLDER.equals(intent.getAction())) {
            showStorageSettings();
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
        if (requestCode == REQUEST_ALL_FILES_ACCESS) {
            if (mSettingsMode) renderStorage(); else begin();
        } else if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String path = DesktopLauncher.pathFromTreeUri(data.getData());
            if (path == null) {
                Toast.makeText(this, "Please pick a folder on your phone or SD card, not a cloud drive.", Toast.LENGTH_LONG).show();
            } else {
                chooseDataDir(path);
            }
        }
    }

    // ---- UI ----

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, boolean filled) {
        Button button = new Button(this, null, filled ? android.R.attr.buttonStyle : android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(filled ? COLOR_BACKGROUND : COLOR_MUTED);
        if (filled) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(COLOR_ACCENT);
            shape.setCornerRadius(dp(24));
            button.setBackground(shape);
        }
        return button;
    }

    private void buildUi() {
        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(28), dp(48), dp(28), dp(28));

        ImageView icon = new ImageView(this);
        GradientDrawable iconBackground = new GradientDrawable();
        iconBackground.setColor(COLOR_CARD);
        iconBackground.setCornerRadius(dp(28));
        icon.setBackground(iconBackground);
        icon.setImageResource(R.drawable.ic_desktop_foreground);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(icon, new LinearLayout.LayoutParams(dp(112), dp(112)));

        TextView title = text("Linux Desktop", 26, COLOR_TEXT);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, dp(4));
        content.addView(title);

        TextView subtitle = text("Setting up your desktop", 14, COLOR_MUTED);
        subtitle.setGravity(Gravity.CENTER);
        content.addView(subtitle);

        LinearLayout steps = new LinearLayout(this);
        mSteps = steps;
        steps.setOrientation(LinearLayout.VERTICAL);
        steps.setPadding(dp(8), dp(28), dp(8), dp(20));
        for (Step step : Step.values()) {
            TextView row = text("", 16, COLOR_MUTED);
            row.setPadding(0, dp(5), 0, dp(5));
            mStepViews[step.ordinal()] = row;
            steps.addView(row);
        }
        content.addView(steps, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mBar.setIndeterminate(true);
        mBar.setMax(100);
        mBar.setProgressTintList(ColorStateList.valueOf(COLOR_ACCENT));
        mBar.setIndeterminateTintList(ColorStateList.valueOf(COLOR_ACCENT));
        mBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_CARD));
        content.addView(mBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)));

        mHeadline = text("Starting...", 17, COLOR_TEXT);
        mHeadline.setGravity(Gravity.CENTER);
        mHeadline.setPadding(0, dp(18), 0, dp(4));
        content.addView(mHeadline);

        mDetail = text("", 14, COLOR_MUTED);
        mDetail.setGravity(Gravity.CENTER);
        content.addView(mDetail);

        mPresets = new RadioGroup(this);
        mPresets.setVisibility(View.GONE);
        mPresets.setPadding(0, dp(16), 0, 0);
        for (DesktopLauncher.Preset preset : DesktopLauncher.Preset.values()) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setTag(preset);
            choice.setText(preset.title + "\n" + preset.description);
            choice.setTextColor(COLOR_TEXT);
            choice.setButtonTintList(ColorStateList.valueOf(COLOR_ACCENT));
            choice.setPadding(dp(8), dp(8), 0, dp(8));
            mPresets.addView(choice);
            if (preset == DesktopLauncher.Preset.STANDARD) choice.setChecked(true);
        }
        content.addView(mPresets, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mStorageBox = new LinearLayout(this);
        mStorageBox.setOrientation(LinearLayout.VERTICAL);
        mStorageBox.setVisibility(View.GONE);
        mStorageBox.setPadding(0, dp(20), 0, 0);
        TextView storageTitle = text("Where your files are stored", 15, COLOR_TEXT);
        storageTitle.setTypeface(storageTitle.getTypeface(), android.graphics.Typeface.BOLD);
        mStorageBox.addView(storageTitle);
        mStorageText = text("", 14, COLOR_MUTED);
        mStorageText.setPadding(0, dp(4), 0, dp(8));
        mStorageBox.addView(mStorageText);
        TextView storageNote = text("The Linux system itself always stays on internal storage (programs cannot run from an SD card). "
            + "Your own files (Documents, Downloads, Pictures, Music, Videos, Projects) can live on the card.", 12, COLOR_MUTED);
        storageNote.setPadding(0, 0, 0, dp(8));
        mStorageBox.addView(storageNote);
        LinearLayout storageButtons = new LinearLayout(this);
        storageButtons.setOrientation(LinearLayout.VERTICAL);
        Button internal = button("Internal storage", false);
        internal.setOnClickListener(v -> chooseDataDir(""));
        Button sd = button("SD card", false);
        sd.setTag("sd");
        sd.setOnClickListener(v -> {
            java.util.List<DesktopLauncher.Volume> volumes = DesktopLauncher.removableVolumes(this);
            if (volumes.isEmpty()) {
                Toast.makeText(this, "No SD card found. Use \"Choose folder\" instead.", Toast.LENGTH_LONG).show();
            } else {
                chooseDataDir(volumes.get(0).path + "/LinuxDesktop");
            }
        });
        Button pick = button("Choose folder...", false);
        pick.setOnClickListener(v -> {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PICK_FOLDER);
            } catch (Exception e) {
                Toast.makeText(this, "This phone has no folder picker.", Toast.LENGTH_LONG).show();
            }
        });
        storageButtons.addView(internal);
        storageButtons.addView(sd);
        storageButtons.addView(pick);
        mStorageBox.addView(storageButtons);
        content.addView(mStorageBox, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mInstall = button("Install", true);
        mInstall.setVisibility(View.GONE);
        mInstall.setOnClickListener(v -> {
            RadioButton checked = findViewById(mPresets.getCheckedRadioButtonId());
            DesktopLauncher.writePreset(checked == null ? DesktopLauncher.Preset.STANDARD : (DesktopLauncher.Preset) checked.getTag());
            mPresets.setVisibility(View.GONE);
            mInstall.setVisibility(View.GONE);
            runSetup();
        });
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        wide.topMargin = dp(16);
        content.addView(mInstall, wide);

        mRetry = button("Try again", true);
        mRetry.setVisibility(View.GONE);
        mRetry.setOnClickListener(v -> {
            mRetry.setVisibility(View.GONE);
            if (mRetryAction != null) mRetryAction.run();
        });
        LinearLayout.LayoutParams wide2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        wide2.topMargin = dp(16);
        content.addView(mRetry, wide2);

        Button debug = button("Copy debug info", false);
        debug.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Linux Desktop debug info", DesktopLauncher.collectDebugInfo(this)));
            Toast.makeText(this, "Copied. Paste it into your message.", Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams debugParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        debugParams.topMargin = dp(24);
        content.addView(debug, debugParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);

        renderSteps(Step.PREPARE, false);
    }

    /** Shows which of the three steps is current, done, or still to come. */
    private void renderSteps(Step current, boolean failed) {
        mStep = current;
        for (Step step : Step.values()) {
            TextView row = mStepViews[step.ordinal()];
            String mark;
            int color;
            if (step.ordinal() < current.ordinal()) { mark = "✓"; color = COLOR_DONE; }
            else if (step == current) { mark = failed ? "✕" : "●"; color = failed ? COLOR_ERROR : COLOR_ACCENT; }
            else { mark = "○"; color = COLOR_MUTED; }
            row.setText(mark + "   " + step.label);
            row.setTextColor(color);
        }
    }

    /** headline: what is happening now. detail: the fine print. percent < 0 means "no exact number". */
    private void setProgress(Step step, String headline, String detail, int percent) {
        renderSteps(step, false);
        mHeadline.setTextColor(COLOR_TEXT);
        mHeadline.setText(percent >= 0 ? headline + "  " + percent + "%" : headline);
        mDetail.setText(detail);
        mBar.setVisibility(View.VISIBLE);
        if (percent < 0) {
            mBar.setIndeterminate(true);
        } else {
            mBar.setIndeterminate(false);
            mBar.setProgress(percent);
        }
    }

    private void showError(Step step, String headline, String detail, Runnable retry) {
        renderSteps(step, true);
        mHeadline.setTextColor(COLOR_ERROR);
        mHeadline.setText(headline);
        mDetail.setText(detail);
        mBar.setVisibility(View.GONE);
        mRetryAction = retry;
        mRetry.setVisibility(retry == null ? View.GONE : View.VISIBLE);
    }

    // ---- Flow: bootstrap -> choose + install desktop core -> keep-alive hints -> start desktop ----

    private void begin() {
        if (mBegun) return;
        mBegun = true;
        setupSystem();
    }

    private void setupSystem() {
        setProgress(Step.PREPARE, "Preparing the Linux system", "First time only. This takes a minute.", -1);
        // Own progress and error display instead of Termux's dialogs; calls back immediately if already set up.
        TermuxInstaller.setupBootstrapIfNeeded(this, this::afterBootstrap, new TermuxInstaller.Listener() {
            @Override
            public void onProgress(String message) {
                setProgress(Step.PREPARE, message, "First time only. Please keep this screen open.", -1);
            }

            @Override
            public void onError(String message) {
                DesktopLauncher.saveBootstrapError(DesktopLaunchActivity.this, message);
                showError(Step.PREPARE, "The Linux system could not be set up",
                    "Check that you have enough free storage and try again. If it keeps failing, tap \"Copy debug info\" and send it to the developer.",
                    DesktopLaunchActivity.this::setupSystem);
            }
        });
    }

    private void afterBootstrap() {
        if (!DesktopLauncher.isBootstrapInstalled()) {
            showError(Step.PREPARE, "Setup could not finish",
                "Check your free storage and try again.", this::setupSystem);
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
            showChooser();
        }
    }

    private void showChooser() {
        renderSteps(Step.INSTALL, false);
        mHeadline.setTextColor(COLOR_TEXT);
        mHeadline.setText("What should be installed?");
        mDetail.setText("The desktop installs first (about 5-10 minutes). Apps install in the background so you can start using it right away.\n\n"
            + "Free storage: " + String.format(Locale.US, "%.1f", freeStorageGb()) + " GB. Roughly needed: Minimal 3 GB, Standard 5 GB, Full 9 GB.\n"
            + (hasInternet() ? "Wi-Fi is recommended; the download is large." : "No internet connection detected. Connect before installing."));
        mBar.setVisibility(View.GONE);
        mPresets.setVisibility(View.VISIBLE);
        renderStorage();
        mStorageBox.setVisibility(View.VISIBLE);
        mInstall.setVisibility(View.VISIBLE);
    }

    // ---- Where your files are stored ----

    private void renderStorage() {
        String dir = DesktopLauncher.readDataDir();
        mStorageText.setText(dir.isEmpty() ? "Internal storage (default)" : dir);
    }

    /** "" means internal storage. The files are moved once the desktop is installed (or right away from the shortcut). */
    private void chooseDataDir(String path) {
        DesktopLauncher.writeDataDir(path);
        renderStorage();
        if (!path.isEmpty() && Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                .setTitle("All-files access needed")
                .setMessage("To store files on the SD card or in another folder, this app needs \"All files access\". Turn it on on the next screen.")
                .setPositiveButton("Continue", (d, w) -> {
                    try {
                        startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())), REQUEST_ALL_FILES_ACCESS);
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Not now", null)
                .show();
        }
    }

    /** Opened from the "Storage location" shortcut: change the folder after the desktop is set up. */
    private void showStorageSettings() {
        mSettingsMode = true;
        mBegun = true; // never start the install flow from here
        mSteps.setVisibility(View.GONE);
        mBar.setVisibility(View.GONE);
        mPresets.setVisibility(View.GONE);
        mRetry.setVisibility(View.GONE);
        mHeadline.setTextColor(COLOR_TEXT);
        mHeadline.setText("Storage location");
        mDetail.setText("Choose where Documents, Downloads, Pictures, Music, Videos and Projects are kept. Your files are copied over; nothing is deleted from the old place.");
        renderStorage();
        mStorageBox.setVisibility(View.VISIBLE);
        mInstall.setText("Save");
        mInstall.setVisibility(View.VISIBLE);
        mInstall.setOnClickListener(v -> {
            if (!DesktopLauncher.isBootstrapInstalled()) {
                Toast.makeText(this, "Finish the desktop setup first.", Toast.LENGTH_LONG).show();
            } else if (!DesktopLauncher.dataDirNeedsApplying()) {
                finish();
            } else if (DesktopLauncher.applyDataDir(this)) {
                mInstall.setVisibility(View.GONE);
                mHeadline.setText("Moving your files");
                mDetail.setText("Please wait. Large folders can take a while; you can leave this screen and it continues.");
                mBar.setVisibility(View.VISIBLE);
                mBar.setIndeterminate(true);
                pollDataMove();
            }
        });
    }

    private void pollDataMove() {
        if (isFinishing() || isDestroyed()) return;
        String state = DesktopLauncher.readDataState();
        if (state.startsWith("done|")) {
            mBar.setVisibility(View.GONE);
            mHeadline.setText("Done");
            mDetail.setText(state.substring(5));
            mHandler.postDelayed(this::finish, 1800);
        } else if (state.startsWith("failed|")) {
            mBar.setVisibility(View.GONE);
            mHeadline.setTextColor(COLOR_ERROR);
            mHeadline.setText("Could not move your files");
            mDetail.setText(state.substring(7));
            mInstall.setText("Try again");
            mInstall.setVisibility(View.VISIBLE);
        } else {
            mHandler.postDelayed(this::pollDataMove, 700);
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
        DesktopLauncher.Progress p = DesktopLauncher.readProgress();
        boolean alreadyRunning = "running".equals(p.state) && p.active;
        if (!alreadyRunning && !hasInternet()) {
            showError(Step.INSTALL, "No internet connection",
                "Connect to Wi-Fi or mobile data, then try again.", this::runSetup);
            return;
        }
        if (!alreadyRunning && freeStorageGb() < 3.0) {
            showError(Step.INSTALL, "Not enough free storage",
                String.format(Locale.US, "%.1f", freeStorageGb()) + " GB free. Free up at least 3 GB, then try again.", this::runSetup);
            return;
        }
        if (!alreadyRunning) {
            setProgress(Step.INSTALL, "Installing the desktop", "This takes about 5-10 minutes and needs internet.", 0);
            DesktopLauncher.resetProgress();
            if (!DesktopLauncher.startSetup(this)) {
                showError(Step.INSTALL, "Could not start the installer", "Try again.", this::runSetup);
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
            showError(Step.INSTALL, "The installation hit a problem", p.message, this::runSetup);
            return;
        }
        if ("running".equals(p.state) && !p.active) {
            showError(Step.INSTALL, "The installer stopped responding",
                "The background installer stopped updating before setup finished. Tap Try again to resume the repair/install process.",
                this::runSetup);
            return;
        }
        if (!p.message.isEmpty())
            setProgress(Step.INSTALL, "Installing the desktop", p.message + "\nYou can leave the app; installation continues.", p.percent);
        mHandler.postDelayed(this::pollSetup, 700);
    }

    private void afterSetup() {
        if (DesktopLauncher.dataDirUnavailable()) {
            // The chosen folder (for example an SD card) cannot be reached right now.
            new AlertDialog.Builder(this)
                .setTitle("Storage folder not available")
                .setMessage("Your files are set to be stored in " + DesktopLauncher.readDataDir()
                    + ", but it cannot be reached. Insert the SD card, or use internal storage for now.")
                .setPositiveButton("Use internal storage", (d, w) -> {
                    DesktopLauncher.writeDataDir("");
                    DesktopLauncher.applyDataDir(this);
                    continueAfterSetup();
                })
                .setNegativeButton("Start anyway", (d, w) -> continueAfterSetup())
                .setOnCancelListener(d -> continueAfterSetup())
                .show();
            return;
        }
        if (DesktopLauncher.dataDirNeedsApplying()) DesktopLauncher.applyDataDir(this);
        continueAfterSetup();
    }

    private void continueAfterSetup() {
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
        setProgress(Step.START, "Starting the desktop", "Opening the display...", -1);
        DesktopLauncher.applyDisplayDefaults(this);
        if (!DesktopLauncher.startDesktop(this)) {
            mFinished = false;
            showError(Step.START, "Could not start the desktop", "Try again.", this::startDesktop);
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
