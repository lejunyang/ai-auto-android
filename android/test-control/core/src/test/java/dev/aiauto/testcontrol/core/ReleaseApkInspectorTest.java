package dev.aiauto.testcontrol.core;

/**
 * 测试用途：验证 release APK 扫描会拒绝测试类、marker、权限和二进制 Manifest 字符串。
 */

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ReleaseApkInspectorTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void cleanReleaseApkHasZeroFindings() throws Exception {
        Path apk = apkWith(
            new Entry("AndroidManifest.xml", "release-manifest".getBytes(StandardCharsets.UTF_8)),
            new Entry("classes.dex", "production-code".getBytes(StandardCharsets.UTF_8))
        );

        assertTrue(ReleaseApkInspector.inspect(apk).isEmpty());
    }

    @Test
    public void testControlClassPathIsRejected() throws Exception {
        Path apk = apkWith(
            new Entry(
                "dev/aiauto/android/testcontrol/DebugTestControl.class",
                new byte[] {0x01, 0x02}
            )
        );

        List<String> findings = ReleaseApkInspector.inspect(apk);

        assertTrue(findings.stream().anyMatch(value -> value.startsWith("entry:")));
    }

    @Test
    public void markerAndControlActionInDexAreRejected() throws Exception {
        Path apk = apkWith(
            new Entry(
                "classes.dex",
                "AI_AUTO_TEST_ONLY_V1 TEST_CONTROL_STATUS".getBytes(StandardCharsets.UTF_8)
            )
        );

        List<String> findings = ReleaseApkInspector.inspect(apk);

        assertTrue(findings.stream().anyMatch(value -> value.contains("AI_AUTO_TEST_ONLY_V1")));
        assertTrue(findings.stream().anyMatch(value -> value.contains("TEST_CONTROL_")));
    }

    @Test
    public void secureSettingsPermissionInBinaryManifestIsRejected() throws Exception {
        Path apk = apkWith(
            new Entry(
                "AndroidManifest.xml",
                "android.permission.WRITE_SECURE_SETTINGS".getBytes(StandardCharsets.UTF_16LE)
            )
        );

        List<String> findings = ReleaseApkInspector.inspect(apk);

        assertTrue(
            findings.stream().anyMatch(
                value -> value.contains("android.permission.WRITE_SECURE_SETTINGS")
            )
        );
    }

    private Path apkWith(Entry... entries) throws IOException {
        Path apk = temporaryFolder.newFile("app-release.apk").toPath();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk))) {
            for (Entry entry : entries) {
                output.putNextEntry(new ZipEntry(entry.name()));
                output.write(entry.content());
                output.closeEntry();
            }
        }
        return apk;
    }

    private record Entry(String name, byte[] content) {
    }
}
