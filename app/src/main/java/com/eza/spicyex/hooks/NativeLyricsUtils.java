package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

import com.eza.spicyex.References;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.SpicyTextDetection;

import java.util.Locale;

/**
 * Stateless rendering/formatting helpers shared by {@link NativeSpicyLyricsHook} and the native
 * lyrics shell. Pulled out of the hook so the shell can live in its own file without reaching into
 * the hook's privates. Consumers use {@code import static ...NativeLyricsUtils.*} so call sites stay
 * unqualified ({@code dp(8)}, {@code isBlank(x)}).
 */
final class NativeLyricsUtils {

    private NativeLyricsUtils() {
    }

    static String sourceProviderLabel(String provider) {
        String value = safe(provider).trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(" cache")) {
            value = value.substring(0, value.length() - " cache".length()).trim();
        }
        return value.isEmpty() ? "unknown" : value;
    }

    static boolean hasJapaneseReading(AppliedLine line) {
        return line != null && line.japaneseReading != null && line.japaneseReading.furigana != null && !line.japaneseReading.furigana.isEmpty();
    }

    static boolean isChineseLine(AppliedLine line) {
        return line != null && SpicyTextDetection.itemChineseTest(line.text);
    }

    static float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return Math.max(0f, Math.min(1f, (positionMs - startMs) / (float) (endMs - startMs)));
    }

    static float spToPx(float sp) {
        Activity activity = References.currentActivity();
        float scaledDensity = activity == null ? 1f : activity.getResources().getDisplayMetrics().scaledDensity;
        return sp * scaledDensity;
    }

    static int sideSystemPadding(Context context) {
        boolean landscape = false;
        try {
            landscape = context.getResources().getDisplayMetrics().widthPixels > context.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignored) {
        }
        return landscape ? dp(72) : dp(20);
    }

    /** Whether the lyrics screen hides the status bar in the current orientation. */
    static boolean statusBarHidden(Context context) {
        try {
            boolean landscape = context.getResources().getDisplayMetrics().widthPixels
                    > context.getResources().getDisplayMetrics().heightPixels;
            com.eza.spicyex.SpotifyPlusConfig config = com.eza.spicyex.SpotifyPlusConfig.from(context);
            return Boolean.TRUE.equals(config.get(landscape
                    ? com.eza.spicyex.Settings.STATUS_BAR_HIDDEN_LANDSCAPE
                    : com.eza.spicyex.Settings.STATUS_BAR_HIDDEN_PORTRAIT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Top clearance for the lyrics screen's chrome, in the content area's own coordinates: the
     * status bar's height plus the chrome gap, plus {@link #hiddenBarShift}.
     *
     * <p>It no longer drops the bar's height while the bar is hidden: that, together with hiding
     * letting the window's content start higher up the screen, made the buttons, artwork and
     * lyrics jump up whenever "hide status bar" was on.
     */
    static int topSystemPadding(Context context) {
        int status = 0;
        try {
            int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) status = context.getResources().getDimensionPixelSize(resId);
        } catch (Throwable ignored) {
        }
        return status + dp(28) + hiddenBarShift();
    }

    /** Where the activity's content area starts on screen now, kept by the lyrics shell. */
    static volatile int contentScreenTop;
    /** Where it started the last time the status bar was showing; -1 until seen. */
    static volatile int shownContentScreenTop = -1;

    /**
     * How far the content area has moved up compared with the status bar showing - what has to
     * be added back so everything stays where it was. Zero while the bar shows. Before the bar
     * has ever been seen showing, assume the content started right below it (Android 11+ lays
     * a window that fits system windows out under the bar).
     */
    static int hiddenBarShift() {
        int shown = shownContentScreenTop;
        if (shown < 0) {
            if (android.os.Build.VERSION.SDK_INT < 30) return 0;
            Activity activity = References.currentActivity();
            if (activity == null) return 0;
            int resId = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            shown = resId > 0 ? activity.getResources().getDimensionPixelSize(resId) : 0;
        }
        return Math.max(0, shown - contentScreenTop);
    }

    static int dp(int value) {
        Activity activity = References.currentActivity();
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    static boolean isBlank(String value) {
        return com.eza.spicyex.lyrics.LyricUtils.isBlank(value);
    }

    static String safe(String value) {
        return com.eza.spicyex.lyrics.LyricUtils.safe(value);
    }

    static String emptyFallback(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    // TextView.setText always rebuilds the layout, even for identical text; these labels are
    // refreshed from the per-frame loop, so skip the call when nothing changed.
    static void setTextIfChanged(TextView view, CharSequence text) {
        if (view == null) return;
        if (TextUtils.equals(view.getText(), text)) return;
        view.setText(text);
    }

    static String trackIdFromUri(String uri) {
        return com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(uri);
    }

    static String shortTrackId(String uri) {
        String id = trackIdFromUri(uri);
        return id.isEmpty() ? safe(uri) : id;
    }

    static String formatMs(long ms) {
        if (ms < 0) return "--:--";
        long total = ms / 1000;
        long minutes = total / 60;
        long seconds = total % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
