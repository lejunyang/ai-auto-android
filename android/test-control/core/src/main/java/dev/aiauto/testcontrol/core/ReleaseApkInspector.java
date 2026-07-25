package dev.aiauto.testcontrol.core;

/**
 * 功能用途：扫描 release APK 的 ZIP 条目、DEX 与二进制 Manifest，确保 N32 测试入口为零。
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ReleaseApkInspector {
    private static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_APK_BYTES = 512L * 1024L * 1024L;
    private static final Set<String> FORBIDDEN_ENTRY_FRAGMENTS = Set.of(
        "dev/aiauto/testcontrol",
        "dev/aiauto/android/testcontrol",
        "test-control",
        "test_control"
    );
    private static final Set<String> FORBIDDEN_TEXT = Set.of(
        "AI_AUTO_TEST_ONLY_V1",
        "dev.aiauto.testcontrol",
        "dev.aiauto.android.testcontrol",
        "dev/aiauto/testcontrol",
        "dev/aiauto/android/testcontrol",
        "dev.aiauto.android.accessibility.ScreenshotTestActivity",
        "dev.aiauto.android.accessibility.ScreenshotTestAccessibilityService",
        "dev/aiauto/android/accessibility/ScreenshotTestActivity",
        "dev/aiauto/android/accessibility/ScreenshotTestAccessibilityService",
        "Accessibility service used only by device tests.",
        "TEST_CONTROL_",
        "android.permission.WRITE_SECURE_SETTINGS"
    );

    private ReleaseApkInspector() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one absolute release APK path");
        }
        List<String> findings = inspect(Path.of(args[0]));
        if (!findings.isEmpty()) {
            throw new IllegalStateException(
                "Release APK contains test-control material: " + String.join(", ", findings)
            );
        }
        System.out.println("Release APK test-control surface: 0 findings");
    }

    public static List<String> inspect(Path apk) throws IOException {
        Path absolute = apk.toAbsolutePath().normalize();
        if (!absolute.isAbsolute()
            || !Files.isRegularFile(absolute)
            || !absolute.getFileName().toString().endsWith(".apk")
            || Files.size(absolute) <= 0
            || Files.size(absolute) > MAX_APK_BYTES) {
            throw new IllegalArgumentException("Release APK path is missing, empty, or oversized");
        }
        List<String> findings = new ArrayList<>();
        try (InputStream input = Files.newInputStream(absolute);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                inspectEntryName(entry.getName(), findings);
                if (!entry.isDirectory()) {
                    byte[] content = readBounded(zip, entry.getName());
                    inspectContent(entry.getName(), content, findings);
                }
                zip.closeEntry();
            }
        }
        return List.copyOf(findings);
    }

    private static void inspectEntryName(String name, List<String> findings) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (String fragment : FORBIDDEN_ENTRY_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                findings.add("entry:" + name);
            }
        }
    }

    private static void inspectContent(String name, byte[] content, List<String> findings) {
        for (String forbidden : FORBIDDEN_TEXT) {
            byte[] ascii = forbidden.getBytes(StandardCharsets.UTF_8);
            byte[] utf16 = forbidden.getBytes(StandardCharsets.UTF_16LE);
            if (contains(content, ascii) || contains(content, utf16)) {
                findings.add("content:" + name + ":" + forbidden);
            }
        }
    }

    private static byte[] readBounded(InputStream input, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("APK entry exceeds scan budget: " + name);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start += 1) {
            for (int offset = 0; offset < needle.length; offset += 1) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
