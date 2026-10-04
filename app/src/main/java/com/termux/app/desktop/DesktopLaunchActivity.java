package com.termux.app.desktop;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import com.termux.app.TermuxActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point of the "Linux Desktop" launcher icon. Has no UI: it asks once for the permissions the
 * desktop and its apps can use (camera, microphone, location, notifications, Bluetooth, media and
 * all-files access), then opens {@link TermuxActivity} with {@link DesktopLauncher#EXTRA_START_DESKTOP}.
 */
public class DesktopLaunchActivity extends Activity {

    private static final int REQUEST_RUNTIME_PERMISSIONS = 4100;
    private static final int REQUEST_ALL_FILES_ACCESS = 4101;
    private static final String PREFS = "desktop_launcher";
    private static final String KEY_PERMISSIONS_ASKED = "permissions_asked";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PERMISSIONS_ASKED, false)) {
            launchDesktop();
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
        launchDesktop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        launchDesktop();
    }

    private void launchDesktop() {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.putExtra(DesktopLauncher.EXTRA_START_DESKTOP, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
