package com.termux.app.desktop;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.termux.app.TermuxService;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Starts the Linux desktop inside this app: writes the helper scripts into {@code $PREFIX/bin} and
 * runs {@code linux-desktop} in a terminal session. That script installs the desktop on first use,
 * starts the built-in X server ({@code com.termux.x11}) and XFCE, and brings the display to the front.
 */
public class DesktopLauncher {

    public static final String EXTRA_START_DESKTOP = "com.termux.app.EXTRA_START_DESKTOP";

    private static final String LOG_TAG = "DesktopLauncher";
    private static final String ASSET_DIR = "desktop/";
    private static final String[] SCRIPTS = {"termux-x11", "linux-desktop", "linux-desktop-setup", "linux-desktop-stop"};

    /** Must only be called once the Termux bootstrap is installed, i.e. {@code $PREFIX/bin/bash} exists. */
    public static boolean start(Context context) {
        if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash").exists()) {
            Logger.logError(LOG_TAG, "Bootstrap is not installed yet");
            return false;
        }
        if (!writeScripts(context)) return false;

        String executable = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/linux-desktop";
        Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
            new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executable).build());
        intent.setClass(context, TermuxService.class);
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH);
        intent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, "Linux Desktop");
        intent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
            Integer.toString(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY));
        context.startService(intent);
        return true;
    }

    private static boolean writeScripts(Context context) {
        String apkPath = context.getApplicationInfo().sourceDir;
        for (String name : SCRIPTS) {
            try (InputStream in = context.getAssets().open(ASSET_DIR + name)) {
                String body = new String(readAll(in), StandardCharsets.UTF_8).replace("@APK_PATH@", apkPath);
                File out = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, name);
                // Always rewritten: the APK path embedded in termux-x11 changes on every app update.
                if (out.exists() && !out.delete()) throw new IOException("Cannot replace " + out);
                com.termux.shared.errors.Error error = FileUtils.writeTextToFile(name, out.getAbsolutePath(),
                    StandardCharsets.UTF_8, body, false);
                if (error != null) throw new IOException(error.getMinimalErrorString());
                if (!out.setExecutable(true, false)) throw new IOException("Cannot chmod " + out);
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to install " + name, e);
                return false;
            }
        }
        return true;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
