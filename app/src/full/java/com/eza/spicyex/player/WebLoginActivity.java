package com.eza.spicyex.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * One-shot interactive login screen on a plain WebView. Cookies live in the app-wide
 * CookieManager, so the headless player session picks the login up with no handoff at
 * all - unlike the earlier engine-backed flow, which needed a shared session object.
 */
public final class WebLoginActivity extends Activity {
    private static final String LOGIN_URL = "https://accounts.spotify.com/en/login?continue="
            + "https%3A%2F%2Fopen.spotify.com%2F";
    private static final String HOME_PREFIX = "https://open.spotify.com";

    private WebView view;
    private boolean wasLoggedInAtOpen;

    private static boolean isGoogleAuthUrl(String url) {
        if (url == null) return false;
        String host = "";
        try {
            host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            return false;
        }
        return host.equals("google.com") || host.endsWith(".google.com")
                || host.contains(".google.") || host.endsWith(".youtube.com")
                || host.equals("youtube.com");
    }

    // Desktop-Chrome impersonation for Google OAuth, credited to Spotilol
    // (lyssadev/Spotilol GoogleSpoof.kt). Versions match our own UA string (Chrome 152)
    // so the header and JS signals agree with each other.
    private static final String GOOGLE_SPOOF_JS =
            "(function(){"
            + "var DESKTOP_UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36';"
            + "try{"
            + "window.screen.__defineGetter__('width',function(){return 1920;});"
            + "window.screen.__defineGetter__('height',function(){return 1080;});"
            + "window.__defineGetter__('innerWidth',function(){return 1920;});"
            + "window.__defineGetter__('innerHeight',function(){return 978;});"
            + "}catch(e){}"
            + "function safeDefine(obj,name,getter){"
            + "try{Object.defineProperty(obj,name,{get:getter,configurable:true});}catch(e){}}"
            + "safeDefine(navigator,'webdriver',function(){return false;});"
            + "safeDefine(navigator,'vendor',function(){return 'Google Inc.';});"
            + "safeDefine(navigator,'productSub',function(){return '20030107';});"
            + "safeDefine(navigator,'userAgent',function(){return DESKTOP_UA;});"
            + "safeDefine(navigator,'platform',function(){return 'Win32';});"
            + "safeDefine(navigator,'oscpu',function(){return 'Windows NT 10.0; Win64; x64';});"
            + "safeDefine(navigator,'languages',function(){return ['en-US','en'];});"
            + "safeDefine(navigator,'language',function(){return 'en-US';});"
            + "try{"
            + "var uaData={brands:["
            + "{brand:'Not.A/Brand',version:'8'},"
            + "{brand:'Chromium',version:'152'},"
            + "{brand:'Google Chrome',version:'152'}],"
            + "mobile:false,platform:'Windows',architecture:'x86',bitness:'64',"
            + "wow64:false,model:'',platformVersion:'10.0.0',"
            + "uaFullVersion:'152.0.0.0',"
            + "fullVersionList:["
            + "{brand:'Not.A/Brand',version:'8'},"
            + "{brand:'Chromium',version:'152.0.0.0'},"
            + "{brand:'Google Chrome',version:'152.0.0.0'}]};"
            + "uaData.getHighEntropyValues=async function(hints){var out={};"
            + "hints.forEach(function(h){if(h in uaData)out[h]=uaData[h];});return out;};"
            + "safeDefine(navigator,'userAgentData',function(){return uaData;});"
            + "safeDefine(navigator,'maxTouchPoints',function(){return 0;});"
            + "}catch(e){}"
            + "try{if(!window.chrome){var cs={runtime:{id:undefined,connect:function(){},"
            + "sendMessage:function(){}},app:{isInstalled:false},"
            + "loadTimes:function(){return{}},csi:function(){return{}},webstore:undefined};"
            + "Object.defineProperty(window,'chrome',{value:cs,configurable:true,writable:true});}}catch(e){}"
            + "safeDefine(navigator,'hardwareConcurrency',function(){return 16;});"
            + "safeDefine(navigator,'deviceMemory',function(){return 16;});"
            + "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wasLoggedInAtOpen = PlayerSession.loggedIn;
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // Instant native UI first: cold WebView provider init can take 10s+ on a busy
        // device, and any tap during that window is an ANR. The warm kick also lets the
        // already-running player service pay the one-time init cost instead of us.
        android.widget.TextView loading = new android.widget.TextView(this);
        loading.setText("Signing in\u2026");
        loading.setTextSize(18);
        loading.setGravity(android.view.Gravity.CENTER);
        loading.setTextColor(0xFFFFFFFF);
        loading.setBackgroundColor(0xFF121212);
        setContentView(loading, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try {
            Intent warm = new Intent("com.eza.spicyex.player.WARMUP");
            warm.setClassName(getPackageName(), "com.eza.spicyex.player.WebPlayerService");
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(warm);
            else startService(warm);
        } catch (Throwable ignored) {
        }

        WebView v = new WebView(this);
        v.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(v);
        view = v;

        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36");
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(v, true);
        } catch (Throwable ignored) {
        }
        v.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                PlayerSession.notePageUrl(url);
                PlayerSession.onLocation(url);
                if (isGoogleAuthUrl(url)) {
                    view.evaluateJavascript(GOOGLE_SPOOF_JS, null);
                }
                // A bare player URL proves nothing - logged-out visitors land on the same
                // shell. Only the user widget proves a session, so verify it before
                // auto-closing; otherwise the screen flashes shut on a login wall.
                if (url != null && url.startsWith(HOME_PREFIX) && !wasLoggedInAtOpen) {
                    view.postDelayed(() -> {
                        try {
                            WebLoginActivity activity = WebLoginActivity.this;
                            if (activity.isFinishing()) return;
                            WebView v = view;
                            v.evaluateJavascript(
                                    "(function(){try{var el=document.querySelector('[data-testid=\"user-widget-link\"]');"
                                    + "if(!el)return 'out';var t=(el.textContent||'').trim();"
                                    + "return t?'in:'+t:'out';}catch(e){return 'err';}})()",
                                    value -> {
                                        try {
                                            boolean ok = value != null && value.length() >= 4
                                                    && value.substring(0, 3).equalsIgnoreCase("\"in");
                                            if (ok) {
                                                PlayerSession.loggedIn = true;
                                                try {
                                                    CookieManager.getInstance().flush();
                                                } catch (Throwable ignored) {
                                                }
                                                activity.finish();
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    });
                        } catch (Throwable ignored) {
                        }
                    }, 2500);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                try {
                    // Fast path: link taps expose the target through hit test - OAuth buttons
                    // ("Continue with Google") usually arrive this way.
                    WebView.HitTestResult hit = view.getHitTestResult();
                    String extra = hit == null ? null : hit.getExtra();
                    if (extra != null && (extra.startsWith("http://") || extra.startsWith("https://"))) {
                        view.loadUrl(extra);
                        return true;
                    }
                    // Fallback: parked popup whose navigations forward into the main view.
                    // Abandoned popups leak one WebView; OAuth flows almost never abandon.
                    WebView popup = new WebView(view.getContext());
                    WebSettings ps = popup.getSettings();
                    ps.setJavaScriptEnabled(true);
                    ps.setDomStorageEnabled(true);
                    ps.setMediaPlaybackRequiresUserGesture(false);
                    try {
                        CookieManager cm = CookieManager.getInstance();
                        cm.setAcceptCookie(true);
                        cm.setAcceptThirdPartyCookies(popup, true);
                    } catch (Throwable ignored) {
                    }
                    final WebView mainView = view;
                    popup.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView v2, WebResourceRequest r) {
                            try {
                                String u = r != null && r.getUrl() != null ? r.getUrl().toString() : null;
                                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                                    mainView.loadUrl(u);
                                    try {
                                        popup.destroy();
                                    } catch (Throwable ignored) {
                                    }
                                    return true;
                                }
                            } catch (Throwable ignored) {
                            }
                            return false;
                        }
                    });
                    WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(popup);
                    resultMsg.sendToTarget();
                    return true;
                } catch (Throwable ignored) {
                }
                return false;
            }
        });
        v.loadUrl(LOGIN_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            view.onResume();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        try {
            view.onPause();
        } catch (Throwable ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (view != null) {
            try {
                view.stopLoading();
            } catch (Throwable ignored) {
            }
            try {
                view.loadUrl("about:blank");
            } catch (Throwable ignored) {
            }
            view.destroy();
            view = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        try {
            if (view != null && view.canGoBack()) {
                view.goBack();
                return;
            }
        } catch (Throwable ignored) {
        }
        super.onBackPressed();
    }

}
