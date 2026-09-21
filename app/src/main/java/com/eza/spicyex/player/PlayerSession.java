package com.eza.spicyex.player;

import android.webkit.WebView;

/**
 * Shared holder for the single headless player WebView and its login/page state. Same role
 * as the engine-backed holder an earlier revision used, minus the engine: a plain WebView
 * needs no runtime object and
 * its cookies live in the app-wide CookieManager, so the login activity automatically shares
 * the session without any handoff.
 */
final class PlayerSession {
    static volatile WebView webview;
    static volatile boolean loggedIn;
    static volatile boolean sawLoginPage;
    static volatile String lastPageUrl = "";

    private PlayerSession() {
    }

    static void notePageUrl(String url) {
        lastPageUrl = url == null ? "" : url;
    }

    /**
     * loggedIn latches only on the transition *away* from accounts.spotify.com - a bare
     * open.spotify.com load looks identical for a logged-out visitor, so trusting it directly
     * would skip the login screen on first run.
     */
    static void onLocation(String url) {
        if (url == null) return;
        if (url.contains("accounts.spotify.com")) {
            sawLoginPage = true;
        } else if (url.startsWith("https://open.spotify.com") && sawLoginPage) {
            loggedIn = true;
        }
    }
}
