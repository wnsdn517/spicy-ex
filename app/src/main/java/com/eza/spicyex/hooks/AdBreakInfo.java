package com.eza.spicyex.hooks;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.eza.spicyex.References;
import com.eza.spicyex.xposed.XpLog;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the current ad sits in its break ("Advertisement · 1 of 3").
 *
 * <p>Read from the ad track's own metadata when Spotify puts it there, otherwise from the label
 * Spotify shows on screen. Knowing the last ad of a break is what lets the replacement music end
 * with the break instead of running on into the song.
 */
public final class AdBreakInfo {
    private static final String TAG = "[SpicyAdBreak]";
    /** "1 of 3", "1/3", "3개 중 1", "3件中1" - optionally after an ad word. */
    private static final Pattern INDEX_OF_COUNT = Pattern.compile(
            "(?i)(?:advertisement|ad|광고|広告|annonce|anuncio|werbung)?\\D{0,6}?(\\d{1,2})\\s*(?:of|/|de|von)\\s*(\\d{1,2})\\b");
    private static final Pattern COUNT_THEN_INDEX = Pattern.compile("(\\d{1,2})\\s*(?:개\\s*중|件中|個中)\\s*(\\d{1,2})");
    private static final Pattern AD_WORD = Pattern.compile("(?i)advertisement|\\bad\\b|광고|広告|annonce|anuncio|werbung");
    private static final long UI_SCAN_INTERVAL_MS = 1000L;

    /** 1-based position in the break; 0 when unknown. */
    final int index;
    /** Ads in the break; 0 when unknown. */
    final int count;

    private AdBreakInfo(int index, int count) {
        this.index = index;
        this.count = count;
    }

    boolean known() {
        return index > 0 && count > 0 && index <= count;
    }

    boolean isLast() {
        return known() && index == count;
    }

    private static volatile String metadataUri = "";
    private static volatile AdBreakInfo fromMetadata;
    private static String uiUri = "";
    private static long uiScannedAt;

    /** Called by References for every ad track it reads. Logs the fields once per ad. */
    public static void noteMetadata(String uri, Map<String, String> metadata) {
        if (uri == null || metadata == null || uri.equals(metadataUri)) return;
        metadataUri = uri;
        fromMetadata = parseMetadata(metadata);
        StringBuilder keys = new StringBuilder();
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            if (value.length() > 60) value = value.substring(0, 60) + "…";
            keys.append(e.getKey()).append('=').append(value).append("; ");
        }
        XpLog.log(TAG + " ad metadata " + uri + ": " + keys);
    }

    /**
     * Best current reading for the ad {@code uri}, or null when nothing says where it is.
     *
     * <p>Spotify's own "2 of 3" is read from every window of the process, not just the current
     * activity - with the lyrics screen on top, the player showing it sits in the window behind.
     * That screen may also stop updating while hidden, so its label is only trusted when it
     * changes: an ad that starts while the label still reads what it read for the previous ad
     * is taken to be the next one in the same break.
     */
    static synchronized AdBreakInfo current(String uri) {
        if (uri == null) return null;
        AdBreakInfo meta = uri.equals(metadataUri) ? fromMetadata : null;
        if (meta != null && meta.known()) {
            lastAskedAt = SystemClock.uptimeMillis();
            remember(uri, meta, null);
            return meta;
        }
        long now = SystemClock.uptimeMillis();
        // Asked about continuously through a break (ad card, ad music): a long silence means a
        // song played in between, so this is a new break even if nobody said the last one ended.
        if (lastAskedAt > 0L && now - lastAskedAt > 20_000L) noteBreakOver();
        lastAskedAt = now;
        if (!uri.equals(uiUri) || now - uiScannedAt >= UI_SCAN_INTERVAL_MS) {
            uiScannedAt = now;
            uiUri = uri;
            Scan scan = scanWindows();
            remember(uri, scan.position, scan.positionText);
            if (scan.breakLeftSec >= 0 && !scan.breakLeftText.equals(lastBreakText)) {
                lastBreakText = scan.breakLeftText;
                breakEndsAt = now + scan.breakLeftSec * 1000L;
            }
        }
        return breakUri.equals(uri) ? breakPosition : null;
    }

    /**
     * Milliseconds left in the whole ad break (every ad still to come, not just this one), or -1
     * when Spotify has not said. Projected from the last "37s left in the break" it showed.
     */
    static synchronized long breakRemainingMs() {
        if (breakEndsAt <= 0L) return -1L;
        return Math.max(0L, breakEndsAt - SystemClock.uptimeMillis());
    }

    /** True when the break ends with this ad: it is the last one, or the break has no more
     *  time left than this ad does. */
    static synchronized boolean endsWithThisAd(AdBreakInfo info, long adRemainingMs) {
        if (info != null && info.isLast()) return true;
        long left = breakRemainingMs();
        return left >= 0 && adRemainingMs >= 0 && left <= adRemainingMs + 1500L;
    }

    /** Called when the ad pauses or resumes: the break's end moves with the pause. */
    static synchronized void notePaused(boolean paused) {
        long now = SystemClock.uptimeMillis();
        if (paused) {
            if (pausedAt == 0L) pausedAt = now;
        } else if (pausedAt != 0L) {
            if (breakEndsAt > 0L) breakEndsAt += now - pausedAt;
            pausedAt = 0L;
        }
    }

    /** A song is playing: the break is over. */
    static synchronized void noteBreakOver() {
        breakUri = "";
        breakPosition = null;
        lastPositionText = "";
        lastBreakText = "";
        breakEndsAt = 0L;
        pausedAt = 0L;
    }

    /** Folds one reading for {@code uri} into the break being followed. */
    private static void remember(String uri, AdBreakInfo reading, String readingText) {
        String text = readingText == null ? "" : readingText;
        boolean fresh = reading != null && (readingText == null || !text.equals(lastPositionText));
        if (fresh) {
            lastPositionText = text;
            breakUri = uri;
            breakPosition = reading;
            return;
        }
        if (uri.equals(breakUri)) return;
        // A new ad and no new reading: the next one of the break we already know, if any.
        AdBreakInfo previous = breakPosition;
        breakUri = uri;
        breakPosition = previous != null && previous.known() && previous.index < previous.count
                ? new AdBreakInfo(previous.index + 1, previous.count) : null;
    }

    private static String breakUri = "";
    private static AdBreakInfo breakPosition;
    private static String lastPositionText = "";
    private static String lastBreakText = "";
    private static long breakEndsAt;
    private static long pausedAt;
    private static long lastAskedAt;

    static AdBreakInfo parseMetadata(Map<String, String> metadata) {
        int index = -1;
        int count = -1;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT);
            String value = e.getValue() == null ? "" : e.getValue().trim();
            AdBreakInfo text = parseText(value);
            if (text != null) return text;
            if (!key.contains("ad") && !key.contains("break") && !key.contains("slot")) continue;
            int number = parseInt(value);
            if (number < 0) continue;
            if (key.contains("count") || key.contains("total") || key.contains("size")
                    || key.contains("length") && !key.contains("ms")) {
                count = number;
            } else if (key.contains("index") || key.contains("position") && !key.contains("ms")
                    || key.contains("number") || key.contains("ordinal")) {
                index = number;
            }
        }
        if (count <= 0 || index < 0) return null;
        // Whether a numeric index counts from 0 or 1 is not known up front; 0 can only mean the
        // former. (The logged metadata above is what confirms which one Spotify uses.)
        return valid(index == 0 ? 1 : index, count);
    }

    static AdBreakInfo parseText(String text) {
        if (text == null || text.isEmpty() || text.length() > 80) return null;
        Matcher m = COUNT_THEN_INDEX.matcher(text);
        if (m.find()) return valid(parseInt(m.group(2)), parseInt(m.group(1)));
        m = INDEX_OF_COUNT.matcher(text);
        if (m.find() && AD_WORD.matcher(text).find()) return valid(parseInt(m.group(1)), parseInt(m.group(2)));
        return null;
    }

    private static AdBreakInfo valid(int index, int count) {
        if (index < 1 || count < 1 || index > count || count > 20) return null;
        return new AdBreakInfo(index, count);
    }

    /** What one pass over the windows found. */
    private static final class Scan {
        AdBreakInfo position;
        String positionText = "";
        int breakLeftSec = -1;
        String breakLeftText = "";
    }

    /** "37s left in the break", "1:05 left in the break", "광고 시간 37초 남음"... */
    private static final Pattern BREAK_WORD = Pattern.compile(
            "(?i)\\bbreak\\b|left|remaining|남음|남았|残り|restant|restan|verbleib");
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern SECONDS = Pattern.compile("(?i)(\\d{1,3})\\s*(?:s\\b|sec|초|秒)");

    /** Seconds a "time left in the break" label gives, or -1 when it is not one. */
    static int parseBreakLeft(String text) {
        if (text == null || text.isEmpty() || text.length() > 60) return -1;
        if (!BREAK_WORD.matcher(text).find()) return -1;
        Matcher clock = CLOCK.matcher(text);
        if (clock.find()) return parseInt(clock.group(1)) * 60 + parseInt(clock.group(2));
        Matcher seconds = SECONDS.matcher(text);
        if (seconds.find()) return parseInt(seconds.group(1));
        return -1;
    }

    private static Scan scanWindows() {
        Scan scan = new Scan();
        java.util.List<View> roots = windowRoots();
        if (roots.isEmpty()) {
            Activity activity = References.currentActivity();
            if (activity != null && activity.getWindow() != null) roots.add(activity.getWindow().getDecorView());
        }
        for (View root : roots) {
            try {
                scan(root, 0, scan);
            } catch (Throwable ignored) {
            }
            if (scan.position != null && scan.breakLeftSec >= 0) break;
        }
        return scan;
    }

    /** Every window's root view in this process (the player behind the lyrics screen too). */
    @SuppressWarnings("unchecked")
    private static java.util.List<View> windowRoots() {
        java.util.List<View> roots = new java.util.ArrayList<>();
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field views = global.getDeclaredField("mViews");
            views.setAccessible(true);
            Object value = views.get(instance);
            if (value instanceof java.util.List) {
                synchronized (instance) {
                    for (Object view : (java.util.List<Object>) value) {
                        if (view instanceof View) roots.add((View) view);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // The current activity first: when it shows the label, it is the live one.
        Activity activity = References.currentActivity();
        View current = activity == null || activity.getWindow() == null ? null
                : activity.getWindow().getDecorView();
        if (current != null) {
            roots.remove(current);
            roots.add(0, current);
        }
        return roots;
    }

    private static void scan(View view, int depth, Scan out) {
        if (view == null || depth > 60) return;
        if (view instanceof TextView) {
            read(String.valueOf(((TextView) view).getText()), out);
        }
        CharSequence description = view.getContentDescription();
        if (description != null) read(description.toString(), out);
        if (out.position != null && out.breakLeftSec >= 0) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                scan(group.getChildAt(i), depth + 1, out);
                if (out.position != null && out.breakLeftSec >= 0) return;
            }
        }
    }

    private static void read(String text, Scan out) {
        if (out.position == null) {
            AdBreakInfo info = parseText(text);
            if (info != null) {
                out.position = info;
                out.positionText = text;
            }
        }
        if (out.breakLeftSec < 0) {
            int left = parseBreakLeft(text);
            if (left >= 0) {
                out.breakLeftSec = left;
                out.breakLeftText = text;
            }
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
