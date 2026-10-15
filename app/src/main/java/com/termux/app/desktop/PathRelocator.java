package com.termux.app.desktop;

import com.termux.shared.termux.TermuxConstants;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Termux's official bootstrap and packages have {@code /data/data/com.termux} compiled into binaries,
 * scripts and config files. This app has a different package name, so those paths are rewritten
 * in place. The replacement must be exactly the same length so ELF files, string tables and shebang
 * lines stay valid without moving any bytes; that is why the package name is also 10 characters.
 */
public final class PathRelocator {

    private static final String OLD_PACKAGE = "com.termux";

    private static final byte[][] OLD;
    private static final byte[][] NEW;

    static {
        String newPackage = TermuxConstants.TERMUX_PACKAGE_NAME;
        if (newPackage.length() != OLD_PACKAGE.length())
            throw new IllegalStateException("Package name \"" + newPackage + "\" must be exactly "
                + OLD_PACKAGE.length() + " characters long so Termux's prebuilt files can be relocated in place");
        String[] roots = {"/data/data/", "/data/user/0/"};
        OLD = new byte[roots.length][];
        NEW = new byte[roots.length][];
        for (int i = 0; i < roots.length; i++) {
            OLD[i] = (roots[i] + OLD_PACKAGE).getBytes(StandardCharsets.ISO_8859_1);
            NEW[i] = (roots[i] + newPackage).getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    private PathRelocator() {}

    /** Rewrites every occurrence in {@code data} in place and returns it. */
    public static byte[] relocate(byte[] data) {
        for (int p = 0; p < OLD.length; p++) {
            byte[] from = OLD[p], to = NEW[p];
            if (Arrays.equals(from, to)) continue;
            int last = data.length - from.length;
            for (int i = 0; i <= last; i++) {
                if (data[i] != from[0]) continue;
                int j = 1;
                while (j < from.length && data[i + j] == from[j]) j++;
                if (j == from.length) {
                    System.arraycopy(to, 0, data, i, to.length);
                    i += from.length - 1;
                }
            }
        }
        return data;
    }

    public static String relocate(String text) {
        for (int p = 0; p < OLD.length; p++)
            text = text.replace(new String(OLD[p], StandardCharsets.ISO_8859_1), new String(NEW[p], StandardCharsets.ISO_8859_1));
        return text;
    }
}
