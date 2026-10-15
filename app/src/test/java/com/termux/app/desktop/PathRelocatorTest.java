package com.termux.app.desktop;

import com.termux.shared.termux.TermuxConstants;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class PathRelocatorTest {

    @Test
    public void relocatesBothAndroidPrivateDataRootsInText() {
        String source = "a=/data/data/com.termux/files/usr\n"
            + "b=/data/user/0/com.termux/files/home";
        String expected = "a=/data/data/com.lxdesk/files/usr\n"
            + "b=/data/user/0/com.lxdesk/files/home";

        Assert.assertEquals(expected, PathRelocator.relocate(source));
    }

    @Test
    public void binaryRelocationPreservesLengthAndSurroundingBytes() {
        byte[] source = new byte[] {0, 1, 2};
        byte[] path = "/data/data/com.termux/files/usr/bin/bash".getBytes(StandardCharsets.ISO_8859_1);
        byte[] input = new byte[source.length + path.length + 2];
        System.arraycopy(source, 0, input, 0, source.length);
        System.arraycopy(path, 0, input, source.length, path.length);
        input[input.length - 2] = 3;
        input[input.length - 1] = 4;

        int originalLength = input.length;
        byte[] relocated = PathRelocator.relocate(input);

        Assert.assertSame(input, relocated);
        Assert.assertEquals(originalLength, relocated.length);
        String text = new String(relocated, StandardCharsets.ISO_8859_1);
        Assert.assertTrue(text.contains("/data/data/com.lxdesk/files/usr/bin/bash"));
        Assert.assertEquals(0, relocated[0]);
        Assert.assertEquals(4, relocated[relocated.length - 1]);
    }

    @Test
    public void packageNameRemainsSafeForInPlaceRelocation() {
        Assert.assertEquals("com.termux".length(), TermuxConstants.TERMUX_PACKAGE_NAME.length());
        Assert.assertEquals("com.lxdesk", TermuxConstants.TERMUX_PACKAGE_NAME);
    }

    @Test
    public void unrelatedContentIsUnchanged() {
        String source = "/data/data/example.app/files and ordinary text";
        Assert.assertEquals(source, PathRelocator.relocate(source));
    }
}
