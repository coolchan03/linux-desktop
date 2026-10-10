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
    private static final String[] SCRIPTS = {"termux-x11", "linux-desktop", "linux-desktop-setup", "linux-desktop-extras", "linux-desktop-data", "linux-desktop-stop", "linux-desktop-verify"};

    private static final File STATE_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/linux-desktop");
    private static final File INSTALLED_MARKER = new File(STATE_DIR, "installed-v1");
    private static final File PROGRESS_FILE = new File(STATE_DIR, "progress");
    private static final File STATE_FILE = new File(STATE_DIR, "state");
    private static final File SETUP_PID_FILE = new File(STATE_DIR, "setup.pid");
    private static final File PRESET_FILE = new File(STATE_DIR, "preset");
    private static final File DATA_DIR_FILE = new File(STATE_DIR, "datadir");
    private static final File DATA_APPLIED_FILE = new File(STATE_DIR, "datadir-applied");
    private static final File DATA_STATE_FILE = new File(STATE_DIR, "data-state");

    /** Setup progress as reported by {@code linux-desktop-setup}. */
    public static class Progress {
        public final int percent;
        public final String message;
        public final String state; // "running", "done", "failed" or "" if setup has not started
        /** True if the setup shell is still alive. */
        public final boolean active;

        Progress(int percent, String message, String state, boolean active) {
            this.percent = percent; this.message = message; this.state = state; this.active = active;
        }
    }

    public static String readStartState() {
        return readFirstLine(new File(STATE_DIR, "start-state"));
    }

    public static boolean isInstalled() {
        if (!INSTALLED_MARKER.exists()) return false;
        String[] required = {"startxfce4", "xfce4-session", "xfce4-panel", "xfwm4", "xfdesktop", "dbus-launch"};
        for (String executable : required) {
            if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, executable).canExecute()) return false;
        }
        return true;
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
        boolean active = false;
        String pidText = readFirstLine(SETUP_PID_FILE).trim();
        if (!pidText.isEmpty()) {
            try {
                long pid = Long.parseLong(pidText);
                File procDir = new File("/proc/" + pid);
                String cmdline = readFirstLine(new File(procDir, "cmdline")).replace('\0', ' ');
                active = pid > 0 && procDir.isDirectory() && cmdline.contains("linux-desktop-setup");
            } catch (NumberFormatException ignored) {}
        } else if (!SETUP_PID_FILE.exists()) {
            // Compatibility with an install started by an older build that did not write setup.pid.
            active = PROGRESS_FILE.exists() && System.currentTimeMillis() - PROGRESS_FILE.lastModified() < 30 * 60 * 1000;
        }
        return new Progress(percent, message, readFirstLine(STATE_FILE).trim(), active);
    }


    /** What to install in the background once the desktop is running. */
    public enum Preset {
        MINIMAL("minimal", "Minimal", "Desktop only. Add apps later with pkg."),
        STANDARD("standard", "Standard", "Desktop + Firefox, VS Code, git."),
        FULL("full", "Full", "Standard + available security/network tools and Windows apps (Wine on arm64).");

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
        // Apply the extra-key bar preference separately for existing installations.
        // Do not overwrite a user choice already saved in X11 preferences.
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        if (!flags.getBoolean("lxdesk_extra_keys_default_v1", false)) {
            if (!prefs.contains("showAdditionalKbd"))
                prefs.edit().putBoolean("showAdditionalKbd", true).putBoolean("additionalKbdVisible", false).apply();
            flags.edit().putBoolean("lxdesk_extra_keys_default_v1", true).apply();
        }
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


    // ---- Where your own files are stored ----

    /** A mounted SD card or other removable volume. */
    public static class Volume {
        public final String label, path;
        Volume(String label, String path) { this.label = label; this.path = path; }
    }

    /** Folder for Documents, Downloads, Pictures... or "" for internal storage. */
    public static String readDataDir() {
        return readFirstLine(DATA_DIR_FILE).trim();
    }

    public static void writeDataDir(String path) {
        writeStateFile(DATA_DIR_FILE, path.trim() + "\n");
    }

    /** True when the chosen folder has not been applied yet, i.e. linux-desktop-data still has to run. */
    public static boolean dataDirNeedsApplying() {
        String applied = DATA_APPLIED_FILE.exists() ? readFirstLine(DATA_APPLIED_FILE).trim() : "";
        return !readDataDir().equals(applied); // no applied file means internal storage, the default
    }

    /**
     * A mounted card is not necessarily writable, and an unused destination
     * folder may not exist yet. Probe access rather than treating a missing
     * directory as a missing SD card.
     *
     * @return null when writable, otherwise a useful user-facing explanation.
     */
    public static String dataDirProblem(Context context) {
        String path = readDataDir();
        if (path.isEmpty()) return null;
        File folder = new File(path);
        try {
            String volumeState = android.os.Environment.getExternalStorageState(folder);
            if (android.os.Environment.MEDIA_MOUNTED_READ_ONLY.equals(volumeState))
                return "Storage is mounted read-only: " + path;
            if (!android.os.Environment.MEDIA_MOUNTED.equals(volumeState)
                    && !android.os.Environment.MEDIA_UNKNOWN.equals(volumeState))
                return "Selected storage is not mounted (" + volumeState + "): " + path;
        } catch (IllegalArgumentException ignored) {
            // Nonstandard volume: let a real filesystem write test decide.
        }
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            return storageAccessHint(context, path, "Cannot create the selected folder.");
        }
        if (!folder.isDirectory()) return "Selected storage is not a directory: " + path;
        try {
            File probe = File.createTempFile(".lxdesk-test-", ".tmp", folder);
            if (!probe.delete()) probe.deleteOnExit();
            return null;
        } catch (IOException | SecurityException ex) {
            return storageAccessHint(context, path, "Cannot write to the selected folder: " + ex.getMessage());
        }
    }

    private static String storageAccessHint(Context context, String path, String reason) {
        if (Build.VERSION.SDK_INT >= 30
                && !android.os.Environment.isExternalStorageManager()
                && !isAppOwnedStorage(context, path))
            return reason + " Android may require All-files access for public folders. "
                + "You can also choose LXDesk's SD app folder, which needs no extra storage permission.";
        return reason + " Check that the card is writable and has free space.";
    }

    /** App-specific Android storage avoids restricted raw SD-card root access. */
    public static boolean isAppOwnedStorage(Context context, String path) {
        if (path == null || path.isEmpty()) return false;
        File[] roots = context.getExternalFilesDirs(null);
        if (roots == null) return false;
        for (File root : roots) {
            if (root == null) continue;
            String base = root.getAbsolutePath();
            if (path.equals(base) || path.startsWith(base + File.separator)) return true;
        }
        return false;
    }

    /** "done|message" or "failed|message" from the last run of linux-desktop-data, or "". */
    public static String readDataState() {
        return readFirstLine(DATA_STATE_FILE);
    }

    public static void resetDataState() {
        DATA_STATE_FILE.delete();
    }

    /** Runs linux-desktop-data in a background shell. */
    public static boolean applyDataDir(Context context) {
        resetDataState();
        return isBootstrapInstalled() && writeScripts(context) && runInBackground(context, "linux-desktop-data");
    }

    /**
     * Select Android-provided app directories on removable volumes.
     * Raw /storage/<UUID>/LinuxDesktop cannot be assumed writable under
     * Android scoped-storage rules even while the card is mounted.
     */
    public static java.util.List<Volume> removableVolumes(Context context) {
        java.util.List<Volume> out = new java.util.ArrayList<>();
        File[] roots = context.getExternalFilesDirs(null);
        if (roots == null) return out;
        android.os.storage.StorageManager manager =
            (android.os.storage.StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        for (File root : roots) {
            if (root == null) continue;
            if (!android.os.Environment.MEDIA_MOUNTED.equals(
                    android.os.Environment.getExternalStorageState(root))) continue;
            if (!android.os.Environment.isExternalStorageRemovable(root)) continue;
            android.os.storage.StorageVolume volume =
                manager == null ? null : manager.getStorageVolume(root);
            String label = volume == null ? "Removable storage" : volume.getDescription(context);
            out.add(new Volume(label + " (LXDesk app folder)",
                new File(root, "LXDesk").getAbsolutePath()));
        }
        return out;
    }

    /**
     * Converts a folder picked with the system file picker into a real path, e.g.
     * {@code primary:Documents/Linux} -> {@code /storage/emulated/0/Documents/Linux} and
     * {@code 1A2B-3C4D:Stuff} -> {@code /storage/1A2B-3C4D/Stuff}. A whole card/volume is not used as is: a
     * {@code LinuxDesktop} folder is added so your files do not mix with everything else. Returns null for
     * pickers that are not plain storage (cloud drives and so on).
     */
    public static String pathFromTreeUri(Uri uri) {
        if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
        String id = android.provider.DocumentsContract.getTreeDocumentId(uri);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon), relative = id.substring(colon + 1);
        String root = "primary".equals(volume) ? "/storage/emulated/0" : "/storage/" + volume;
        return relative.isEmpty() ? root + "/LinuxDesktop" : root + "/" + relative;
    }

    /** Everything useful for diagnosing a failed install or a black display, as plain text. */
    public static String collectDebugInfo(Context context) {
        StringBuilder out = new StringBuilder();
        String version = "?";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        out.append("LXDesk ").append(version).append(" (").append(context.getPackageName()).append(")\n")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ").append(Build.SUPPORTED_ABIS[0]).append('\n')
            .append("bootstrap installed: ").append(isBootstrapInstalled())
            .append(", desktop installed: ").append(isInstalled()).append('\n');
        Progress p = readProgress();
        out.append("setup state: ").append(p.state).append(" ").append(p.percent).append("% ").append(p.message)
            .append(", process active: ").append(p.active).append('\n');
        out.append("preset: ").append(readFirstLine(PRESET_FILE)).append('\n');
        String dataDir = readDataDir();
        String appliedDataDir = DATA_APPLIED_FILE.exists() ? readFirstLine(DATA_APPLIED_FILE).trim() : "";
        out.append("data folder requested: ").append(dataDir.isEmpty() ? "internal" : dataDir)
            .append(", applied: ").append(appliedDataDir.isEmpty() ? "internal" : appliedDataDir)
            .append(", state: ").append(readDataState()).append('\n');
        out.append("all-files access: ")
            .append(Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager())
            .append('\n');
        String dataProblem = dataDirProblem(context);
        out.append("selected storage check: ")
            .append(dataProblem == null ? "writable" : dataProblem).append('\n');
        for (Volume card : removableVolumes(context)) {
            out.append("removable storage: ").append(card.label).append(" = ")
                .append(card.path).append('\n');
        }
        out.append("extras: ").append(readFirstLine(new File(STATE_DIR, "extras-state"))).append(" / ")
            .append(readFirstLine(new File(STATE_DIR, "extras-progress"))).append('\n');
        appendTail(out, "extras-skipped.log", new File(STATE_DIR, "extras-skipped.log"), 20);
        out.append("bootstrap second stage done: ")
            .append(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh.lock").exists()).append('\n');
        appendTail(out, "bootstrap-error.log", new File(context.getFilesDir(), "bootstrap-error.log"), 40);
        appendTail(out, "setup.log", new File(STATE_DIR, "setup.log"), 60);
        appendTail(out, "verify.log", new File(STATE_DIR, "verify.log"), 30);
        appendTail(out, "last-pkg.log", new File(STATE_DIR, "last-pkg.log"), 35);
        appendTail(out, "missing-components.log", new File(STATE_DIR, "missing-components.log"), 30);
        appendTail(out, "verify.log", new File(STATE_DIR, "verify.log"), 30);
        appendTail(out, "repair.log", new File(STATE_DIR, "repair.log"), 30);
        appendTail(out, "update-initial.log", new File(STATE_DIR, "update-initial.log"), 30);
        appendTail(out, "update-final.log", new File(STATE_DIR, "update-final.log"), 30);
        appendTail(out, "repo-x11.log", new File(STATE_DIR, "repo-x11.log"), 30);
        appendTail(out, "repo-tur.log", new File(STATE_DIR, "repo-tur.log"), 20);
        appendTail(out, "relocate.log", new File(STATE_DIR, "relocate.log"), 30);
        appendTail(out, "gpu.log", new File(STATE_DIR, "gpu.log"), 30);
        out.append("X11 startup: ").append(readStartState()).append('\n');
        appendTail(out, "x11.log", new File(STATE_DIR, "x11.log"), 40);
        appendTail(out, "desktop.log", new File(STATE_DIR, "desktop.log"), 40);
        appendTail(out, "desktop-apps.log", new File(STATE_DIR, "desktop-apps.log"), 40);
        // The FIRST failed package is usually the root cause of the later ones, so show it first.
        File[] failed = STATE_DIR.listFiles((dir, name) -> name.startsWith("failed-") && name.endsWith(".log"));
        if (failed != null && failed.length > 0) {
            java.util.Arrays.sort(failed, (a, b) -> a.getName().compareTo(b.getName()));
            StringBuilder names = new StringBuilder();
            for (File f : failed) names.append(f.getName()).append(' ');
            out.append("failed package logs: ").append(names).append('\n');
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

    /** Keeps the last bootstrap error so "Copy debug info" can include it. */
    public static void saveBootstrapError(Context context, String message) {
        try {
            writeFile(new File(context.getFilesDir(), "bootstrap-error.log"), message);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save bootstrap error", e);
        }
    }

    /** Forgets the result of an earlier setup attempt so a retry does not read stale "failed" state. */
    public static void resetProgress() {
        PROGRESS_FILE.delete();
        STATE_FILE.delete();
        SETUP_PID_FILE.delete();
    }

    /** Runs {@code linux-desktop-setup} in a background shell. Returns false if scripts could not be written. */
    public static boolean startSetup(Context context) {
        return writeScripts(context) && runInBackground(context, "linux-desktop-setup");
    }

    /** Runs {@code linux-desktop} (X server + XFCE) in a background shell. */
    public static boolean startDesktop(Context context) {
        new File(STATE_DIR, "start-state").delete();
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
        try {
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start background script " + script, e);
            return false;
        }
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
                    .replace("@NATIVE_LIB_DIR@", context.getApplicationInfo().nativeLibraryDir)
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
