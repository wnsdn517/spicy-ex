package com.eza.spicyex.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Headless Spotify web player on a plain system WebView. The Spotify-side bridge, the warm
 * receiver and the settings UI all reach it through the com.eza.spicyex.player.* broadcast
 * actions below plus the warm-reply codes.
 *
 * <p>Why WebView rather than GeckoView, which an earlier revision used: Chromium never
 * preflights the User-Agent header, so the Widevine license and image fetches that died
 * under Gecko's custom-UA CORS
 * preflight (bmo#1629921) just work; the engine is a fraction of the memory; and
 * WebViewClient.shouldInterceptRequest gives real adblocking, which GeckoView cannot
 * do without a separate extension process. Desktop disguise (which is what makes
 * Spotify serve the full player) comes from the UA string plus sec-ch-ua* spoofing,
 * same recipe as Spotilol.
 */
public final class WebPlayerService extends Service {
    private static final String TAG = "[SpicyWeb]";
    private static final String CHANNEL_ID = "spicy_player";
    private static final int NOTIFICATION_ID = 2001;
    private static final String HOME = "https://open.spotify.com/";

    // The wire protocol. PlayerWarmReceiver and ConnectEntry send these verbatim, so any
    // rename has to land in all three files at once.
    public static final String ACTION_PLAY = "com.eza.spicyex.player.PLAY";
    public static final String ACTION_PAUSE = "com.eza.spicyex.player.PAUSE";
    public static final String ACTION_TOGGLE = "com.eza.spicyex.player.TOGGLE";
    public static final String ACTION_NEXT = "com.eza.spicyex.player.NEXT";
    public static final String ACTION_PREVIOUS = "com.eza.spicyex.player.PREVIOUS";
    public static final String ACTION_SEEK = "com.eza.spicyex.player.SEEK";
    public static final String ACTION_VOLUME = "com.eza.spicyex.player.VOLUME";
    public static final String ACTION_OPEN = "com.eza.spicyex.player.OPEN";
    public static final String ACTION_SEARCH = "com.eza.spicyex.player.SEARCH";
    public static final String ACTION_WARMUP = "com.eza.spicyex.player.WARMUP";
    public static final String ACTION_LOGIN = "com.eza.spicyex.player.LOGIN";
    public static final String ACTION_STOP = "com.eza.spicyex.player.STOP";
    public static final String ACTION_RECONNECT = "com.eza.spicyex.player.RECONNECT";
    public static final String EXTRA_WARM_REPLY = "warm_reply";
    public static final int WARM_RESULT_READY = 1;
    public static final int WARM_RESULT_STARTING = 2;
    public static final int WARM_RESULT_FAILED = 3;
    public static final int WARM_RESULT_LOGIN_REQUIRED = 4;

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    // A spoofed UA *string* alone isn't enough: Spotify's web player also reads the separate,
    // string-independent Client Hints API (navigator.userAgentData.mobile) and touch/GPU signals,
    // all of which say "real Android device" regardless of the UA string, and that combination is
    // what its own browser-support check rejects with "your browser isn't supported" - credited to
    // Spotilol (project/lol/webview/injections/BrowserSpoof.kt), which needed the same thing for
    // third-party OAuth popups; we need it on the Spotify page itself since we actually play
    // through it rather than just bouncing through a login flow.
    private static final String BROWSER_SPOOF_JS = "(function(){"
            + "try{"
            + "window.screen.__defineGetter__('width',function(){return 1920;});"
            + "window.screen.__defineGetter__('height',function(){return 1080;});"
            + "window.screen.__defineGetter__('availWidth',function(){return 1920;});"
            + "window.screen.__defineGetter__('availHeight',function(){return 1040;});"
            + "window.__defineGetter__('innerWidth',function(){return 1920;});"
            + "window.__defineGetter__('innerHeight',function(){return 978;});"
            + "}catch(e){}"
            + "function safeDefine(obj,name,getter){try{Object.defineProperty(obj,name,{get:getter,configurable:true});}catch(e){}}"
            + "safeDefine(navigator,'webdriver',function(){return false;});"
            + "safeDefine(navigator,'vendor',function(){return 'Google Inc.';});"
            + "safeDefine(navigator,'productSub',function(){return '20030107';});"
            + "safeDefine(navigator,'platform',function(){return 'Win32';});"
            + "safeDefine(navigator,'oscpu',function(){return 'Windows NT 10.0; Win64; x64';});"
            + "safeDefine(navigator,'languages',function(){return ['en-US','en'];});"
            + "safeDefine(navigator,'language',function(){return 'en-US';});"
            + "safeDefine(navigator,'maxTouchPoints',function(){return 0;});"
            + "safeDefine(navigator,'hardwareConcurrency',function(){return 16;});"
            + "safeDefine(navigator,'deviceMemory',function(){return 16;});"
            + "try{"
            + "var uaData={brands:[{brand:'Not.A/Brand',version:'8'},{brand:'Chromium',version:'150'},{brand:'Google Chrome',version:'150'}],"
            + "mobile:false,platform:'Windows',architecture:'x86',bitness:'64',wow64:false,model:'',platformVersion:'10.0.0',"
            + "uaFullVersion:'150.0.7871.187',fullVersionList:[{brand:'Not.A/Brand',version:'8'},{brand:'Chromium',version:'150.0.7871.187'},{brand:'Google Chrome',version:'150.0.7871.187'}]};"
            + "uaData.getHighEntropyValues=async function(hints){var out={};hints.forEach(function(h){if(h in uaData) out[h]=uaData[h];});return out;};"
            + "safeDefine(navigator,'userAgentData',function(){return uaData;});"
            + "}catch(e){}"
            + "try{"
            + "if(!window.chrome){"
            + "Object.defineProperty(window,'chrome',{value:{runtime:{id:undefined,connect:function(){},sendMessage:function(){}},app:{isInstalled:false},loadTimes:function(){return{};},csi:function(){return{};},webstore:undefined},configurable:true,writable:true});"
            + "}"
            + "}catch(e){}"
            + "try{"
            + "var gp=WebGLRenderingContext.prototype.getParameter;"
            + "WebGLRenderingContext.prototype.getParameter=function(p){if(p===37445)return 'Google Inc. (NVIDIA)';if(p===37446)return 'ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 (0x00002584) Direct3D11 vs_5_0 ps_5_0, D3D11)';return gp.call(this,p);};"
            + "if(window.WebGL2RenderingContext){"
            + "var gp2=WebGL2RenderingContext.prototype.getParameter;"
            + "WebGL2RenderingContext.prototype.getParameter=function(p){if(p===37445)return 'Google Inc. (NVIDIA)';if(p===37446)return 'ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 (0x00002584) Direct3D11 vs_5_0 ps_5_0, D3D11)';return gp2.call(this,p);};"
            + "}"
            + "}catch(e){}"
            + "})();";

    /**
     * The player page is a 1x1 transparent overlay nobody ever sees, but the desktop SPA still
     * animates as if it were on a monitor: rAF-driven progress bars, CSS transitions, marquee
     * titles, hover effects. Each of those keeps the renderer's main thread and compositor busy
     * every vsync for the whole time the player is alive (which, with the boot receiver, is
     * all day). Nothing here touches audio, timers or network: rAF callbacks still run - just a
     * few times a second, like a background tab - and CSS animations/transitions finish
     * instantly, so any code waiting on transitionend still gets it.
     */
    private static final String IDLE_RENDER_JS = "(function(){try{"
            + "if(window.__spicyIdle)return;window.__spicyIdle=true;"
            + "var q=[],t=0;"
            + "function flush(){t=0;var c=q;q=[];var now=performance.now();"
            + "for(var i=0;i<c.length;i++){if(c[i]){try{c[i].f(now);}catch(e){}}}}"
            + "window.requestAnimationFrame=function(f){q.push({f:f});"
            + "if(!t)t=setTimeout(flush,250);return q.length;};"
            + "window.cancelAnimationFrame=function(id){if(id>0&&q[id-1])q[id-1]=null;};"
            + "function css(){try{var s=document.createElement('style');"
            + "s.textContent='*,*::before,*::after{animation-duration:0s!important;animation-delay:0s!important;"
            + "animation-iteration-count:1!important;transition:none!important;scroll-behavior:auto!important}"
            + "img,video,canvas,picture,svg image{visibility:hidden!important}';"
            + "(document.head||document.documentElement).appendChild(s);}catch(e){}}"
            + "if(document.documentElement)css();else document.addEventListener('DOMContentLoaded',css);"
            + "}catch(e){}})();";

    // Analytics + ad-audio host lists, credited to Spotilol (lyssadev/Spotilol AdBlocker.kt).
    // workbox-window is deliberately NOT blocked: Spotify lazy-loads it as a webpack chunk
    // during init and blocking it trips a ChunkLoadError -> React error boundary.
    private static final String[] ANALYTICS_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "fastly-insights.com", "sentry.io",
            "t.6sc.co", "tracker.samplicio.us", "adsrvr.org", "aet.spotify.com",
            "retargeting-pixels", "spotify.com/gabo-receiver-service/public/v3/events",
    };
    // Canvas (the looping artist video) and other decorative video: never visible here, and each
    // one is a hardware video decode running for the length of a track.
    private static final String[] VIDEO_MARKERS = {
            "canvaz.scdn.co", "video.akamaized.net", "video-fa.scdn.co", "video-ak.cdn.spotify.com",
    };
    private static final String[] AD_AUDIO_MARKERS = {
            "akamaized.net/audio/", "scdn.co/audio/", "scdn.co/mp3-ad/", "spotifycdn.com/audio/",
            "amillionads.com", "2mdn.net", "adxcel.com", "adstudio-assets.scdn.co",
            "scdn.co/mp3-ad/", "mp3ad.scdn.co", "audio-ads.spotify.com", "ads-akp.spotify.com",
            "ads-fa.spotify.com", "adeventtracker.spotify.com", "pixel-static.spotify.com",
            "pixel.spotify.com", "adstudio.spotify.com", "ads.spotify.com",
    };

    // Decorative looping videos decode like real video; on an audio-only session that is pure
    // CPU/battery waste and a dropout source under load. Audio plays through <audio>, untouched.
    private static final String FREEZE_VIDEOS_JS =
            "(function(){try{"
            + "if(window.__spicyFrozen)return;window.__spicyFrozen=true;"
            + "function f(v){try{if(v.__spicyF)return;v.__spicyF=true;"
            + "v.removeAttribute('autoplay');v.pause();v.preload='none';}catch(e){}}"
            + "function sweep(){try{var vs=document.querySelectorAll('video');"
            + "for(var i=0;i<vs.length;i++)f(vs[i]);}catch(e){}}"
            + "sweep();"
            + "try{new MutationObserver(function(m){for(var i=0;i<m.length;i++){"
            + "var a=m[i].addedNodes;for(var j=0;j<a.length;j++){var n=a[j];"
            + "if(n.nodeType!==1)continue;"
            + "if(n.tagName==='VIDEO')f(n);"
            + "else if(n.querySelectorAll){var vs=n.querySelectorAll('video');"
            + "for(var k=0;k<vs.length;k++)f(vs[k]);}}}})"
            + ".observe(document.documentElement,{childList:true,subtree:true});}catch(e){}"
            + "}catch(e){}})();";

    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long WIFI_REAPER_DELAY_MS = 45 * 60 * 1000L;
    private static final int WATCHDOG_MAX_RESETS = 2;
    private static final long WATCHDOG_DELAY_MS = 45 * 1000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean networkLost;
    private int watchdogResets;
    private android.view.WindowManager overlayWindowManager;
    private WebView overlayAttachedView;
    // Set when the platform refused foreground promotion in onCreate - any start command that
    // still lands afterwards has nothing left to run against, so it just stops the service.
    private boolean foregroundDenied;

    private final Runnable reconnectRunnable = () -> {
        try {
            if (PlayerSession.webview == null) return;
            recoverConnection();
        } catch (Throwable t) {
            Log.e(TAG, "reconnect failed type=" + t.getClass().getName());
        }
    };

    private final Runnable freezeRetry = () -> {
        try {
            evalJs(FREEZE_VIDEOS_JS);
        } catch (Throwable ignored) {
        }
    };

    private final Runnable wifiReaper = () -> {
        try {
            releaseWifi();
        } catch (Throwable ignored) {
        }
    };

    /**
     * Same soft-first policy as before: the Connect cloud session belongs to the page's own
     * JS, so a healthy page only gets an online nudge; a provably broken page gets reloaded.
     */
    private void recoverConnection() {
        try {
            WebView w = PlayerSession.webview;
            if (w == null) return;
            String u = PlayerSession.lastPageUrl;
            if (u != null && (u.contains("open.spotify.com") || u.contains("accounts.spotify.com"))) {
                Log.i(TAG, "page alive, nudging reconnect without reload");
                evalJs("window.dispatchEvent(new Event('online'));");
            } else {
                Log.i(TAG, "page broken (" + u + "), reloading player page");
                w.reload();
            }
        } catch (Throwable t) {
            Log.e(TAG, "recoverConnection failed type=" + t.getClass().getName());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        watchNetwork();
        if (!com.eza.spicyex.ForegroundServiceGuard.promote(
                this, NOTIFICATION_ID, buildNotification("Starting"), TAG)) {
            // Nothing else in here is worth setting up if we can't be a foreground service -
            // and staying alive without reaching the foreground just earns a kill from the
            // platform watchdog a few seconds later.
            foregroundDenied = true;
            stopSelf();
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":playback");
                wakeLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "wakeLock create failed type=" + t.getClass().getName());
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG + ":stream");
                wifiLock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "wifiLock create failed type=" + t.getClass().getName());
        }
    }

    private void keepAwakeWhilePlaying() {
        try {
            if (wakeLock != null) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Throwable t) {
            Log.e(TAG, "wakeLock acquire failed type=" + t.getClass().getName());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable t) {
            Log.e(TAG, "wakeLock release failed type=" + t.getClass().getName());
        }
    }

    private void acquireStreamingLocks() {
        keepAwakeWhilePlaying();
        try {
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable t) {
            Log.e(TAG, "wifiLock acquire failed type=" + t.getClass().getName());
        }
        main.removeCallbacks(wifiReaper);
        main.postDelayed(wifiReaper, WIFI_REAPER_DELAY_MS);
    }

    private void releaseStreamingLocks() {
        releaseWakeLock();
        releaseWifi();
        main.removeCallbacks(wifiReaper);
    }

    private void releaseWifi() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable t) {
            Log.e(TAG, "wifiLock release failed type=" + t.getClass().getName());
        }
    }

    private void watchNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLost(Network network) {
                    networkLost = true;
                    Log.i(TAG, "network lost");
                }

                @Override
                public void onAvailable(Network network) {
                    if (!networkLost) return;
                    networkLost = false;
                    Log.i(TAG, "network available, reloading player shortly");
                    main.removeCallbacks(reconnectRunnable);
                    main.postDelayed(reconnectRunnable, 2500);
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (Throwable t) {
            Log.e(TAG, "watchNetwork failed type=" + t.getClass().getName());
        }
    }

    private void unwatchNetwork() {
        try {
            main.removeCallbacks(reconnectRunnable);
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) {
        } finally {
            networkCallback = null;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (foregroundDenied) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (action == null) return START_STICKY;
        if (ACTION_STOP.equals(action)) {
            releaseStreamingLocks();
            destroyPlayer();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_WARMUP.equals(action)) {
            android.os.ResultReceiver reply;
            try {
                reply = intent.getParcelableExtra(EXTRA_WARM_REPLY);
            } catch (Throwable ignored) {
                reply = null;
            }
            syncLoggedInFromCookie();
            if (PlayerSession.webview != null && PlayerSession.loggedIn) {
                sendWarmReply(reply, WARM_RESULT_READY);
                return START_STICKY;
            }
            sendWarmReply(reply, WARM_RESULT_STARTING);
            final android.os.ResultReceiver pending = reply;
            ensureReady(() -> {
                if (PlayerSession.loggedIn) {
                    sendWarmReply(pending, WARM_RESULT_READY);
                    return;
                }
                checkLoginViaDom(ok -> sendWarmReply(pending,
                        ok ? WARM_RESULT_READY : WARM_RESULT_LOGIN_REQUIRED));
            });
            return START_STICKY;
        }
        ensureReady(() -> {
            if (ACTION_LOGIN.equals(action)) {
                launchLogin();
                return;
            }
            if (ACTION_RECONNECT.equals(action)) {
                recoverConnection();
                syncLoggedInFromCookie();
                if (!PlayerSession.loggedIn) launchLogin();
                else postNotification();
                return;
            }
            // Never gate playback on a guess: load the target, verify login from the
            // live DOM in parallel for state/notification accuracy.
            checkLoginViaDom(null);
            dispatch(action, intent);
        });
        return START_STICKY;
    }

    /**
     * Login truth straight from the live page: the user widget exists only when signed in
     * (same signal Spotilol reads). Beats every heuristic - URL gating cannot tell an
     * anonymous player load from a logged-in one, and the token endpoint needs headers a
     * blind fetch cannot reproduce (it 403s even with valid cookies).
     */
    private interface LoginCheck {
        void onResult(boolean loggedIn);
    }

    /**
     * sp_dc is Spotify's own long-lived web session cookie - present only for an authenticated
     * open.spotify.com session, and it survives across our own process restarts (CookieManager
     * persists it to disk) even though PlayerSession.loggedIn itself is a plain in-memory flag
     * that resets to false every time Spotify's process restarts. Without this, a returning user
     * whose Connect sign-in was already done saw "Tap to sign in to Spotify" and auto-start
     * skipped playback dispatch for however long the live DOM check below took to catch up (or
     * forever, if it gave up first - see LOGIN_CHECK_MAX_TRIES).
     */
    private static boolean hasSessionCookie() {
        try {
            String cookies = CookieManager.getInstance().getCookie("https://open.spotify.com");
            return cookies != null && cookies.contains("sp_dc=");
        } catch (Throwable t) {
            return false;
        }
    }

    /** One-way correction (never flips a genuinely-true flag back off by itself): if the
     *  persisted session cookie says logged in but the in-memory flag hasn't caught up yet
     *  (typically right after a process restart), bring it up to date immediately instead of
     *  waiting on the slower live DOM check. */
    private void syncLoggedInFromCookie() {
        if (!PlayerSession.loggedIn && hasSessionCookie()) {
            PlayerSession.loggedIn = true;
            postNotification();
        }
    }

    private static final long LOGIN_CHECK_TIMEOUT_MS = 20000L;
    // Cold WebView provider init plus the full open.spotify.com SPA load can take 10s+ on a busy
    // device (see ensureReady's own comment) - the original 2-try/~6s window gave up long before
    // that finished on a slow device, permanently stranding PlayerSession.loggedIn at false for
    // the rest of the process's life since nothing else re-triggers this check on its own.
    private static final int LOGIN_CHECK_MAX_TRIES = 6;
    private static final String LOGIN_CHECK_JS =
            "(function(){try{"
            + "var el=document.querySelector('[data-testid=\"user-widget-link\"]');"
            + "if(!el)return 'out';"
            + "var t=(el.textContent||'').trim();"
            + "return t?'in:'+t:'out';"
            + "}catch(e){return 'err';}})()";

    private void checkLoginViaDom(LoginCheck callback) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(() -> checkLoginViaDom(callback));
                return;
            }
            WebView w = PlayerSession.webview;
            syncLoggedInFromCookie();
            if (w == null) {
                // No live page to confirm with yet - the cookie sync above is the best answer
                // available, rather than defaulting to false just because the WebView hasn't
                // been constructed.
                if (callback != null) callback.onResult(PlayerSession.loggedIn);
                return;
            }
            final boolean[] settled = {false};
            Runnable timeout = () -> {
                if (settled[0]) return;
                settled[0] = true;
                if (callback != null) {
                    try {
                        callback.onResult(false);
                    } catch (Throwable ignored) {
                    }
                }
            };
            main.postDelayed(timeout, LOGIN_CHECK_TIMEOUT_MS);
            // The SPA renders after commit; read twice - a negative on a half-painted page
            // means nothing, so only the second negative settles.
            Runnable read = new Runnable() {
                int tries;

                @Override
                public void run() {
                    try {
                        WebView v = PlayerSession.webview;
                        if (v == null || settled[0]) return;
                        v.evaluateJavascript(LOGIN_CHECK_JS, value -> {
                            if (settled[0]) return;
                            boolean ok = false;
                            try {
                                ok = value != null && value.length() >= 4
                                        && value.substring(0, 3).equalsIgnoreCase("\"in");
                            } catch (Throwable ignored) {
                            }
                            if (ok) {
                                settled[0] = true;
                                main.removeCallbacks(timeout);
                                PlayerSession.loggedIn = true;
                                postNotification();
                            } else if (++tries >= LOGIN_CHECK_MAX_TRIES) {
                                settled[0] = true;
                                main.removeCallbacks(timeout);
                            } else {
                                main.postDelayed(this, 3500);
                                return;
                            }
                            Log.i(TAG, "login check loggedIn=" + ok);
                            if (callback != null) {
                                try {
                                    callback.onResult(ok);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
            };
            main.postDelayed(read, 2500);
        } catch (Throwable ignored) {
            if (callback != null) {
                try {
                    callback.onResult(false);
                } catch (Throwable ignored2) {
                }
            }
        }
    }

    private void sendWarmReply(android.os.ResultReceiver reply, int code) {
        try {
            if (reply != null) reply.send(code, null);
        } catch (Throwable ignored) {
        }
    }

    private void launchLogin() {
        postNotification();
        try {
            Intent i = new Intent(this, WebLoginActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "launchLogin startActivity failed type=" + t.getClass().getName());
        }
    }

    private void dispatch(String action, Intent intent) {
        WebView w = PlayerSession.webview;
        if (w == null) return;
        if (!ACTION_PAUSE.equals(action)) acquireStreamingLocks();
        switch (action) {
            case ACTION_OPEN: {
                String uri = intent.getStringExtra("uri");
                if (uri != null && !uri.isEmpty()) {
                    Log.i(TAG, "open " + toOpenUrl(uri));
                    w.loadUrl(toOpenUrl(uri));
                }
                break;
            }
            case ACTION_PLAY:
                pressPlay(true);
                break;
            case ACTION_PAUSE:
                pressPlay(false);
                break;
            case ACTION_TOGGLE:
                evalJs("var b=document.querySelector('[data-testid=\"control-button-playpause\"]');if(b)b.click();");
                break;
            case ACTION_NEXT:
                evalJs("var b=document.querySelector('[data-testid=\"control-button-skip-forward\"]');if(b)b.click();");
                break;
            case ACTION_PREVIOUS:
                evalJs("var b=document.querySelector('[data-testid=\"control-button-skip-back\"]');if(b)b.click();");
                break;
            case ACTION_SEEK: {
                int pos = intent.getIntExtra("position", 0);
                evalJs("var s=document.querySelector('[data-testid=\"progress-bar\"] input[type=\"range\"]');if(!s)return;var m=+s.max||100;s.focus();s.value=Math.max(0,Math.min(m," + pos + "));s.dispatchEvent(new Event('input',{bubbles:true}));s.dispatchEvent(new Event('change',{bubbles:true}));");
                break;
            }
            case ACTION_VOLUME: {
                int vol = intent.getIntExtra("volume", 50);
                evalJs("var s=document.querySelector('[data-testid=\"volume-bar\"] input[type=\"range\"]');if(!s)return;var m=+s.max||100;s.value=Math.round(m*" + vol + "/100);s.dispatchEvent(new Event('input',{bubbles:true}));s.dispatchEvent(new Event('change',{bubbles:true}));");
                break;
            }
            case ACTION_SEARCH: {
                String q = intent.getStringExtra("q");
                if (q != null && !q.isEmpty()) {
                    try {
                        w.loadUrl("https://open.spotify.com/search/" + java.net.URLEncoder.encode(q, "UTF-8"));
                    } catch (Throwable ignored) {
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void pressPlay(boolean wantPlay) {
        Log.i(TAG, "pressPlay want=" + wantPlay);
        evalJs("var b=document.querySelector('[data-testid=\"control-button-playpause\"]');if(!b)return;var l=(b.getAttribute('aria-label')||'').toLowerCase();var isPlay=l.indexOf('play')>=0&&l.indexOf('pause')<0;if(" + wantPlay + "&&isPlay)b.click();if(!" + wantPlay + "&&!isPlay)b.click();");
        if (wantPlay) acquireStreamingLocks();
        else releaseStreamingLocks();
        postNotification();
    }

    private String toOpenUrl(String uri) {
        String u = uri.trim();
        if (u.startsWith("http")) return u;
        if (u.startsWith("spotify:track:")) return "https://open.spotify.com/track/" + u.substring(14);
        if (u.startsWith("spotify:album:")) return "https://open.spotify.com/album/" + u.substring(14);
        if (u.startsWith("spotify:playlist:")) return "https://open.spotify.com/playlist/" + u.substring(17);
        if (u.startsWith("spotify:artist:")) return "https://open.spotify.com/artist/" + u.substring(15);
        return HOME;
    }

    private void evalJs(String script) {
        try {
            WebView w = PlayerSession.webview;
            if (w == null) return;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post(() -> evalJs(script));
                return;
            }
            w.evaluateJavascript(script, null);
        } catch (Throwable t) {
            Log.e(TAG, "evalJs failed type=" + t.getClass().getName());
        }
    }

    private interface Ready {
        void run();
    }

    /**
     * Adds the WebView to a real system window (1x1px, fully transparent, touch-through) instead
     * of leaving it purely headless. android.permission.SYSTEM_ALERT_WINDOW is a special
     * permission the user must grant manually via Settings - if it isn't granted yet, this is a
     * no-op and playback stays headless (silent DRM failure) exactly as before.
     */
    private void attachToOverlayWindow(WebView w) {
        try {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.w(TAG, "overlay permission not granted - DRM playback will likely fail headless");
                return;
            }
            android.view.WindowManager wm =
                    (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE;
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    1, 1, type,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT);
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            wm.addView(w, lp);
            if (Build.VERSION.SDK_INT >= 35) {
                // Votes only for this invisible 1x1 view; other windows keep their own rates.
                try {
                    w.setRequestedFrameRate(android.view.View.REQUESTED_FRAME_RATE_CATEGORY_LOW);
                } catch (Throwable ignored) {
                }
            }
            overlayWindowManager = wm;
            overlayAttachedView = w;
            Log.i(TAG, "webview attached to overlay window");
        } catch (Throwable t) {
            Log.e(TAG, "attachToOverlayWindow failed type=" + t.getClass().getName(), t);
        }
    }

    private void detachOverlayWindow() {
        if (overlayWindowManager == null || overlayAttachedView == null) return;
        try {
            overlayWindowManager.removeViewImmediate(overlayAttachedView);
        } catch (Throwable ignored) {
        }
        overlayWindowManager = null;
        overlayAttachedView = null;
    }

    private void ensureReady(Ready onReady) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> ensureReady(onReady));
            return;
        }
        try {
            if (PlayerSession.webview != null) {
                onReady.run();
                return;
            }
            WebView w = new WebView(getApplicationContext());
            // Widevine EME needs a real, composited on-screen surface behind it; a WebView that
            // only ever exists inside this headless Service (never added to any Window) doesn't
            // get one, which is what actually produces "Playback of protected content is not
            // enabled" regardless of the permission grant below. attachToOverlayWindow makes it
            // a genuine (if visually negligible) on-screen window when the user has granted
            // "draw over other apps"; otherwise this silently stays headless as before.
            w.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
            attachToOverlayWindow(w);
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            s.setSupportMultipleWindows(true);
            s.setJavaScriptCanOpenWindowsAutomatically(true);
            s.setSupportZoom(true);
            s.setBuiltInZoomControls(true);
            s.setDisplayZoomControls(false);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setGeolocationEnabled(false);
            s.setSaveFormData(false);
            // Cover art, avatars and playlist mosaics decode and upload to the GPU for a page
            // nobody sees; playback, login and Connect never need them.
            s.setBlockNetworkImage(true);
            s.setUserAgentString(DESKTOP_UA);
            try {
                CookieManager cm = CookieManager.getInstance();
                cm.setAcceptCookie(true);
                cm.setAcceptThirdPartyCookies(w, true);
            } catch (Throwable ignored) {
            }
            // Must land before the page's own scripts run their browser-support feature
            // detection - onPageStarted's evaluateJavascript() is too late for that, so this
            // uses the document-start injection point instead where available.
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(w, BROWSER_SPOOF_JS,
                            Collections.singleton("https://*.spotify.com"));
                    WebViewCompat.addDocumentStartJavaScript(w, IDLE_RENDER_JS,
                            Collections.singleton("https://open.spotify.com"));
                }
            } catch (Throwable t) {
                Log.e(TAG, "addDocumentStartJavaScript failed type=" + t.getClass().getName(), t);
            }
            w.setWebViewClient(new PlayerWebClient());
            w.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public void onReceivedTitle(WebView view, String title) {
                    super.onReceivedTitle(view, title);
                    Log.i(TAG, "title=" + title);
                }

                // Surfaces the page's own console output (its EME/DRM rejection reason included)
                // to logcat - otherwise that detail is invisible from the native side entirely.
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                    android.webkit.ConsoleMessage.MessageLevel level = cm.messageLevel();
                    if (level != android.webkit.ConsoleMessage.MessageLevel.ERROR
                            && level != android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                        return true;
                    }
                    Log.i(TAG, "console[" + cm.messageLevel() + "] " + cm.message()
                            + " (" + cm.sourceId() + ":" + cm.lineNumber() + ")");
                    return true;
                }

                // Without a WebChromeClient overriding this, WebView silently denies every
                // permission request - including RESOURCE_PROTECTED_MEDIA_ID, which is what
                // Chromium's EME/Widevine path asks for before it will play anything but
                // previews. That's the exact cause of "Playback of protected content is not
                // enabled": the page's own DRM request was never being granted.
                @Override
                public void onPermissionRequest(android.webkit.PermissionRequest request) {
                    if (request == null) return;
                    try {
                        request.grant(request.getResources());
                    } catch (Throwable t) {
                        request.deny();
                    }
                }
            });
            PlayerSession.webview = w;
            w.loadUrl(HOME);
            Log.i(TAG, "player created");
            armWatchdog(w);
            onReady.run();
        } catch (Throwable t) {
            Log.e(TAG, "ensureReady failed type=" + t.getClass().getName(), t);
            PlayerSession.webview = null;
        }
    }

    /**
     * A player that never reports any page never loaded anything - reset it bounded so the
     * next command rebuilds genuinely fresh instead of talking to a corpse forever.
     */
    private void armWatchdog(WebView created) {
        try {
            main.postDelayed(() -> {
                try {
                    if (watchdogResets >= WATCHDOG_MAX_RESETS) return;
                    if (PlayerSession.webview != created || created == null) return;
                    if (!PlayerSession.lastPageUrl.isEmpty()) return;
                    Log.w(TAG, "watchdog: player never loaded, resetting");
                    destroyPlayer();
                    watchdogResets++;
                } catch (Throwable ignored) {
                }
            }, WATCHDOG_DELAY_MS + 5000);
        } catch (Throwable ignored) {
        }
    }

    private void destroyPlayer() {
        try {
            WebView w = PlayerSession.webview;
            PlayerSession.webview = null;
            detachOverlayWindow();
            if (w != null) {
                try {
                    w.stopLoading();
                } catch (Throwable ignored) {
                }
                try {
                    w.loadUrl("about:blank");
                } catch (Throwable ignored) {
                }
                try {
                    w.destroy();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private final class PlayerWebClient extends WebViewClient {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.i(TAG, "location=" + url);
                PlayerSession.notePageUrl(url);
                PlayerSession.onLocation(url);
                postNotification();
                // Best-effort fallback for WebView builds without DOCUMENT_START_SCRIPT support;
                // later than ideal (page scripts may already be running) but still before most
                // browser-support checks actually execute in practice.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    try {
                        view.evaluateJavascript(BROWSER_SPOOF_JS, null);
                        if (url != null && url.startsWith("https://open.spotify.com")) {
                            view.evaluateJavascript(IDLE_RENDER_JS, null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            PlayerSession.notePageUrl(url);
            postNotification();
            if (url != null && url.contains("open.spotify.com")) {
                evalJs(FREEZE_VIDEOS_JS);
                main.removeCallbacks(freezeRetry);
                main.postDelayed(freezeRetry, 4000);
                checkLoginViaDom(null);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            return false;
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            super.onReceivedError(view, request, error);
            try {
                if (request != null && request.isForMainFrame()) {
                    Log.e(TAG, "page error code=" + error.getErrorCode());
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            try {
                if (request != null && request.isForMainFrame() && errorResponse != null
                        && errorResponse.getStatusCode() >= 400) {
                    Log.e(TAG, "page http error status=" + errorResponse.getStatusCode());
                }
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean crashed = false;
            try {
                crashed = detail != null && detail.didCrash();
            } catch (Throwable ignored) {
            }
            Log.e(TAG, "renderer gone crashed=" + crashed + ", dropping player for rebuild");
            destroyPlayer();
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                if (request == null || request.getUrl() == null) return null;
                String url = request.getUrl().toString();
                String lower = url.toLowerCase(Locale.ROOT);
                for (String host : ANALYTICS_HOSTS) {
                    if (lower.contains(host)) {
                        return new WebResourceResponse("text/plain", "utf-8", 200, "OK",
                                corsHeaders(), new ByteArrayInputStream(new byte[0]));
                    }
                }
                for (String marker : VIDEO_MARKERS) {
                    if (lower.contains(marker)) {
                        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                                corsHeaders(), new ByteArrayInputStream(new byte[0]));
                    }
                }
                boolean adAudio = false;
                for (String marker : AD_AUDIO_MARKERS) {
                    if (lower.contains(marker)) {
                        adAudio = true;
                        break;
                    }
                }
                if (adAudio && !lower.contains("podz-content") && !lower.contains("gew4-spclient")) {
                    Log.i(TAG, "ad audio swapped: " + summarizedUrl(url));
                    try {
                        InputStream silent = view.getContext().getAssets().open("silent.mp3");
                        Map<String, String> headers = corsHeaders();
                        headers.put("Content-Type", "audio/mpeg");
                        return new WebResourceResponse("audio/mpeg", null, 200, "OK", headers, silent);
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private Map<String, String> corsHeaders() {
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            return headers;
        }

        private String summarizedUrl(String url) {
            try {
                if (url.length() > 120) return url.substring(0, 120) + "...";
                return url;
            } catch (Throwable ignored) {
                return "?";
            }
        }
    }

    private void createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Spicy web player", NotificationManager.IMPORTANCE_LOW);
                NotificationManager m = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (m != null) m.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play);
        b.setContentTitle("Web player service running");
        b.setContentText(text);
        b.setOngoing(false);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (!PlayerSession.loggedIn) {
            try {
                Intent i = new Intent(this, WebLoginActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(this, 0, i, piFlags));
            } catch (Throwable ignored) {
            }
        }
        try {
            Intent pause = new Intent(this, WebPlayerService.class).setAction(ACTION_PAUSE);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Pause", PendingIntent.getService(this, 11, pause, piFlags)).build());
            Intent stop = new Intent(this, WebPlayerService.class).setAction(ACTION_STOP);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", PendingIntent.getService(this, 12, stop, piFlags)).build());
            b.setDeleteIntent(PendingIntent.getService(this, 0, stop, piFlags));
        } catch (Throwable ignored) {
        }
        return b.build();
    }

    private void postNotification() {
        try {
            String text = !PlayerSession.loggedIn ? "Tap to sign in to Spotify" : "Swipe to stop";
            NotificationManager m = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (m != null) m.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // No WebView trim API exists; the renderer lives in its own sandboxed process and the
        // system reclaims it. Deliberately never touch the player on trim - an evicted player
        // rebuilds on the next command via the watchdog path.
    }

    @Override
    public void onDestroy() {
        unwatchNetwork();
        main.removeCallbacks(wifiReaper);
        main.removeCallbacks(freezeRetry);
        main.removeCallbacks(reconnectRunnable);
        releaseStreamingLocks();
        destroyPlayer();
        super.onDestroy();
    }
}
