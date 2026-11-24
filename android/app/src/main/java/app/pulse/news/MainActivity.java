package app.pulse.news;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Pulse for Android: a full-screen WebView on the deployed site. It loads the live app,
 * so every web deploy updates it. Links to other sites open in the phone's browser.
 */
public class MainActivity extends Activity {
    private static final String HOST = BuildConfig.PULSE_HOST;
    private static final String HOME = "https://" + HOST + "/";
    private WebView web;
    private boolean showingError = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0B0C);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " PulseAndroid/1");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, false);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                if (HOST.equalsIgnoreCase(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                showingError = false;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (!req.isForMainFrame()) return;
                showingError = true;
                String html = "<html><body style='margin:0;height:100vh;display:flex;flex-direction:column;"
                        + "align-items:center;justify-content:center;background:#0B0B0C;color:#F2F2F0;"
                        + "font-family:sans-serif;text-align:center;padding:24px'>"
                        + "<h2 style='font-family:serif'>Can't reach Pulse</h2>"
                        + "<p style='color:#9A9A9E'>Check your connection and try again.</p>"
                        + "<a href='" + HOME + "' style='margin-top:16px;padding:12px 22px;border-radius:999px;"
                        + "background:#F5A524;color:#000;text-decoration:none;font-weight:600'>Try again</a>"
                        + "</body></html>";
                view.loadDataWithBaseURL(HOME, html, "text/html", "utf-8", HOME);
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onBackPressed() {
        if (!showingError && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
