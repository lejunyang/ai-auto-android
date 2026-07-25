package dev.aiauto.webfixture;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 功能用途：校验 WebView 请求只指向固定 assets 目录，避免相似前缀和路径穿越触发外部访问。
 */
public final class FixtureUrlPolicy {
    private static final String ROOT_PATH = "/android_asset/web/";

    private FixtureUrlPolicy() {
    }

    public static boolean isAllowed(String rawUrl) {
        if (rawUrl == null || rawUrl.toLowerCase(Locale.ROOT).contains("%2e")) {
            return false;
        }
        try {
            URI uri = new URI(rawUrl);
            if (!"file".equals(uri.getScheme()) || uri.getAuthority() != null) {
                return false;
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith(ROOT_PATH) || path.contains("//")) {
                return false;
            }
            String relative = path.substring(ROOT_PATH.length());
            return !relative.isBlank()
                && !relative.endsWith("/")
                && !relative.contains("..")
                && !relative.contains("\\")
                && relative.matches("[A-Za-z0-9._/-]+")
                && uri.normalize().getPath().equals(path);
        } catch (URISyntaxException error) {
            return false;
        }
    }
}
