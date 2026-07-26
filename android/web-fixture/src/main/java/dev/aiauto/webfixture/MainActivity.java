package dev.aiauto.webfixture;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 功能用途：承载三种离线 WebView/Canvas 模式，提供可复位状态并在请求发起前拒绝非 fixture URL。
 */
public final class MainActivity extends ComponentActivity {
    private static final String EXTRA_MODE = "mode";
    private static final String MODE_FULL = "full";
    private static final String MODE_PARTIAL = "partial";
    private static final String MODE_CANVAS = "canvas";
    private static final String STATE_MODE = "fixture.mode";
    private static final String ASSET_ROOT = "file:///android_asset/web/";

    private final AtomicInteger rejectedRequestCount = new AtomicInteger();
    private final AtomicInteger pageGeneration = new AtomicInteger();
    private WebView webView;
    private TextView modeStatus;
    private TextView networkStatus;
    private TextView generationStatus;
    private String mode = MODE_FULL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.fixture_webview);
        modeStatus = findViewById(R.id.mode_status);
        networkStatus = findViewById(R.id.network_status);
        generationStatus = findViewById(R.id.generation_status);
        configureWebView(webView);
        configureBackNavigation();

        bindModeButton(R.id.mode_full, MODE_FULL);
        bindModeButton(R.id.mode_partial, MODE_PARTIAL);
        bindModeButton(R.id.mode_canvas, MODE_CANVAS);
        findViewById(R.id.reset_fixture).setOnClickListener(view -> loadMode(mode));

        String restored = savedInstanceState == null ? null : savedInstanceState.getString(STATE_MODE);
        String requested = restored == null ? getIntentMode(getIntent()) : restored;
        loadMode(requested);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadMode(getIntentMode(intent));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_MODE, mode);
        super.onSaveInstanceState(outState);
    }

    private void configureBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // fixture 内页面使用 WebView 历史，无历史时暂时禁用回调并退出宿主。
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.clearCache(true);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void bindModeButton(int id, String targetMode) {
        Button button = findViewById(id);
        button.setOnClickListener(view -> loadMode(targetMode));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView target) {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = target.getSettings();
        // fixture 的交互契约依赖本地 JS；URL 白名单、无网络权限和 blockNetworkLoads 共同限制来源。
        settings.setJavaScriptEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setDatabaseEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(false);
        target.setWebViewClient(new OfflineClient());
    }

    private void loadMode(String requestedMode) {
        mode = normalizeMode(requestedMode);
        rejectedRequestCount.set(0);
        modeStatus.setText(getString(R.string.mode_status, mode));
        updateNetworkStatus();
        webView.loadUrl(ASSET_ROOT + mode + ".html?run=fixture");
    }

    private String getIntentMode(Intent intent) {
        if (intent == null) {
            return MODE_FULL;
        }
        String extra = intent.getStringExtra(EXTRA_MODE);
        if (extra != null) {
            return extra;
        }
        Uri data = intent.getData();
        return data == null ? MODE_FULL : data.getQueryParameter(EXTRA_MODE);
    }

    private String normalizeMode(String candidate) {
        if (MODE_PARTIAL.equals(candidate) || MODE_CANVAS.equals(candidate)) {
            return candidate;
        }
        return MODE_FULL;
    }

    private void updateNetworkStatus() {
        networkStatus.setText(
            getString(R.string.network_status, rejectedRequestCount.get())
        );
    }

    private WebResourceResponse reject(String url) {
        rejectedRequestCount.incrementAndGet();
        runOnUiThread(this::updateNetworkStatus);
        String body = String.format(Locale.ROOT, "blocked:%s", url);
        return new WebResourceResponse(
            "text/plain",
            StandardCharsets.UTF_8.name(),
            403,
            "Offline fixture blocked request",
            Collections.singletonMap("Cache-Control", "no-store"),
            new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
        );
    }

    private final class OfflineClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (!FixtureUrlPolicy.isAllowed(url)) {
                reject(url);
                return true;
            }
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
            WebView view,
            WebResourceRequest request
        ) {
            String url = request.getUrl().toString();
            return FixtureUrlPolicy.isAllowed(url) ? null : reject(url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!FixtureUrlPolicy.isAllowed(url)) {
                view.stopLoading();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (!FixtureUrlPolicy.isAllowed(url)) {
                return;
            }
            generationStatus.setText(
                getString(R.string.generation_status, pageGeneration.incrementAndGet())
            );
        }
    }
}
