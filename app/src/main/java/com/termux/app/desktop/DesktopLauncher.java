package com.termux.app.desktop;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.ContextCompat;

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
 * Helpers to install and run the Linux desktop inside this app. The scripts are written into
 * {@code $PREFIX/bin} and run as background Termux shells (no terminal is shown). {@code
 * linux-desktop-setup} installs the desktop on first use and reports progress through files;
 * {@code linux-desktop} starts the built-in X server ({@code com.termux.x11}) and XFCE.
 */
public class DesktopLauncher {

    private static final String LOG_TAG = "DesktopLauncher";
    private static final String ASSET_DIR = "desktop/";
    private static final String[] SCRIPTS = {"termux-x11", "linux-desktop", "linux-desktop-setup", "linux-desktop-extras", "linux-desktop-stop"};

    private static final File STATE_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/linux-desktop");
    private static final File INSTALLED_MARKER = new File(STATE_DIR, "installed-v1");
    private static final File PROGRESS_FILE = new File(STATE_DIR, "progress");
    private static final File STATE_FILE = new File(STATE_DIR, "state");
    private static final File PRESET_FILE = new File(STATE_DIR, "preset");

    /** Setup progress as reported by {@code linux-desktop-setup}. */
    public static class Progress {
        public final int percent;
        public final String message;
        public final String state; // "running", "done", "failed" or "" if setup has not started
        /** True if the progress file was updated recently, i.e. a setup shell is still alive. */
        public final boolean active;

        Progress(int percent, String message, String state, boolean active) {
            this.percent = percent; this.message = message; this.state = state; this.active = active;
        }
    }

    public static boolean isInstalled() {
        return INSTALLED_MARKER.exists();
    }

    public static boolean isBootstrapInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash").exists();
    }

    public static Progress readProgress() {
        int percent = 0;
        String message = "";
        String[] parts = readFirstLine(PROGRESS_FILE).split("\\|", 2);
        if (parts.length == 2) {
            try { percent = Integer.parseInt(parts[0].trim()); } catch (NumberFormatException ignored) {}
            message = parts[1];
        }
        boolean active = PROGRESS_FILE.exists() && System.currentTimeMillis() - PROGRESS_FILE.lastModified() < 30 * 60 * 1000;
        return new Progress(percent, message, readFirstLine(STATE_FILE).trim(), active);
    }


    /** What to install in the background once the desktop is running. */
    public enum Preset {
        MINIMAL("minimal", "Minimal", "Desktop only. Add apps later with pkg."),
        STANDARD("standard", "Standard", "Desktop + Firefox, VS Code, git."),
        FULL("full", "Full", "Standard + security tools (nmap, hydra, sqlmap...) and Windows apps (Wine).");

        public final String id, title, description;
        Preset(String id, String title, String description) { this.id = id; this.title = title; this.description = description; }
    }

    public static void writePreset(Preset preset) {
        writeStateFile(PRESET_FILE, preset.id + "\n");
    }

    private static void writeStateFile(File file, String text) {
        try {
            if (!STATE_DIR.isDirectory() && !STATE_DIR.mkdirs()) return;
            writeFile(file, text);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write " + file, e);
        }
    }

    /** Asks the running Termux to stop the X server, XFCE and audio. */
    public static void stopDesktop(Context context) {
        if (isBootstrapInstalled() && writeScripts(context)) runInBackground(context, "linux-desktop-stop");
    }

    /**
     * Preferences for a phone: scaled-down resolution (so XFCE is readable), fullscreen, cutout hidden.
     * Only written once, so changes made in the display settings later are kept.
     */
    @SuppressWarnings("deprecation")
    public static void applyDisplayDefaults(Context context) {
        android.content.SharedPreferences flags = context.getSharedPreferences("desktop_launcher", Context.MODE_PRIVATE);
        if (flags.getBoolean("display_defaults_applied", false)) return;
        android.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("displayResolutionMode", "scaled")
            .putInt("displayScale", 60)
            .putBoolean("fullscreen", true)
            .putBoolean("hideCutout", true)
            .putString("screenIdleTimeout", "never")
            .apply();
        flags.edit().putBoolean("display_defaults_applied", true).apply();
    }

    /** Everything useful for diagnosing a failed install or a black display, as plain text. */
    public static String collectDebugInfo(Context context) {
        StringBuilder out = new StringBuilder();
        String version = "?";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        out.append("Linux Desktop ").append(version).append(" (").append(context.getPackageName()).append(")\n")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ").append(Build.SUPPORTED_ABIS[0]).append('\n')
            .append("bootstrap installed: ").append(isBootstrapInstalled())
            .append(", desktop installed: ").append(isInstalled()).append('\n');
        Progress p = readProgress();
        out.append("setup state: ").append(p.state).append(" ").append(p.percent).append("% ").append(p.message).append('\n');
        out.append("preset: ").append(readFirstLine(PRESET_FILE)).append('\n');
        out.append("extras: ").append(readFirstLine(new File(STATE_DIR, "extras-state"))).append(" / ")
            .append(readFirstLine(new File(STATE_DIR, "extras-progress"))).append('\n');
        appendTail(out, "desktop.log", new File(STATE_DIR, "desktop.log"), 40);
        File[] failed = STATE_DIR.listFiles((dir, name) -> name.startsWith("failed-") && name.endsWith(".log"));
        if (failed != null && failed.length > 0) {
            java.util.Arrays.sort(failed, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            appendTail(out, failed[0].getName(), failed[0], 40);
        }
        return out.toString();
    }

    private static void appendTail(StringBuilder out, String title, File file, int lines) {
        out.append("\n--- ").append(title).append(" (last ").append(lines).append(" lines) ---\n");
        java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.addLast(line);
                if (tail.size() > lines) tail.removeFirst();
            }
        } catch (IOException e) {
            out.append("(not found)\n");
            return;
        }
        for (String line : tail) out.append(line).append('\n');
    }

    /** Forgets the result of an earlier setup attempt so a retry does not read stale "failed" state. */
    public static void resetProgress() {
        PROGRESS_FILE.delete();
        STATE_FILE.delete();
    }

    /** Runs {@code linux-desktop-setup} in a background shell. Returns false if scripts could not be written. */
    public static boolean startSetup(Context context) {
        return writeScripts(context) && runInBackground(context, "linux-desktop-setup");
    }

    /** Runs {@code linux-desktop} (X server + XFCE) in a background shell. */
    public static boolean startDesktop(Context context) {
        return writeScripts(context) && runInBackground(context, "linux-desktop");
    }

    private static boolean runInBackground(Context context, String script) {
        String executable = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + script;
        Intent intent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE,
            new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executable).build());
        intent.setClass(context, TermuxService.class);
        intent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH);
        intent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND, true);
        intent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, script);
        ContextCompat.startForegroundService(context, intent);
        return true;
    }

    private static String readFirstLine(File file) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Installs the apt hook that rewrites Termux's prebuilt packages for this app's package name (see
     * {@link PathRelocator}) into {@code stagingPrefix}, which becomes {@code $PREFIX} once bootstrap is done.
     */
    public static void installRelocationHook(Context context, String stagingPrefix) throws IOException {
        String script;
        try (InputStream in = context.getAssets().open(ASSET_DIR + "lxdesk-relocate-debs")) {
            script = new String(readAll(in), StandardCharsets.UTF_8)
                .replace("@PREFIX@", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
                .replace("@NEW_PKG@", TermuxConstants.TERMUX_PACKAGE_NAME);
        }
        File bin = new File(stagingPrefix, "bin/lxdesk-relocate-debs");
        writeFile(bin, script);
        if (!bin.setExecutable(true, false)) throw new IOException("Cannot chmod " + bin);

        File conf = new File(stagingPrefix, "etc/apt/apt.conf.d/99-lxdesk-relocate");
        if (!conf.getParentFile().isDirectory() && !conf.getParentFile().mkdirs())
            throw new IOException("Cannot create " + conf.getParentFile());
        writeFile(conf, "// Rewrites Termux packages for this app's package name before dpkg unpacks them.\n"
            + "DPkg::Pre-Install-Pkgs { \"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/bin/lxdesk-relocate-debs\"; };\n");
    }

    private static void writeFile(File file, String text) throws IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean writeScripts(Context context) {
        String apkPath = context.getApplicationInfo().sourceDir;
        for (String name : SCRIPTS) {
            try (InputStream in = context.getAssets().open(ASSET_DIR + name)) {
                String body = PathRelocator.relocate(new String(readAll(in), StandardCharsets.UTF_8))
                    .replace("@APK_PATH@", apkPath)
                    .replace("@PKG@", context.getPackageName());
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
