package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsSkeletonView;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.ui.ActionIconDrawable;

/** Builds transient loading and error rows for the fullscreen lyric surface. */
final class LyricsShellEmptyStateController {
    private static final long ERROR_DISPLAY_MS = 10_000L;
    private final Activity activity;
    private final SpotifyPlusConfig config;
    private final LyricsTextFactory textFactory;
    private int stateToken;

    LyricsShellEmptyStateController(
            Activity activity,
            SpotifyPlusConfig config,
            LyricsTextFactory textFactory
    ) {
        this.activity = activity;
        this.config = config;
        this.textFactory = textFactory;
    }

    void showLoading(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message) {
        showLoading(lyricsScroll, lyricsColumn, message, 0);
    }

    /** Ad placeholder: a compact ad badge, with mute status shown only when auto-mute is enabled. */
    void showAdPlaceholder(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message) {
        stateToken++;
        lyricsColumn.removeAllViews();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x1AFFFFFF);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), 0x22FFFFFF);
        card.setBackground(cardBg);
        card.setPadding(dp(24), dp(28), dp(24), dp(24));

        TextView badge = textFactory.createText(activity, "AD", 16, Color.WHITE,
                textFactory.resolveTypeface(true));
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(0.12f);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(0xCC8B5CF6);
        badgeBg.setCornerRadius(dp(12));
        badge.setBackground(badgeBg);
        card.addView(badge, new LinearLayout.LayoutParams(dp(64), dp(38)));

        TextView label = textFactory.createText(
                activity,
                message,
                18,
                Color.WHITE,
                textFactory.resolveTypeface(false));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(16), 0, 0);
        card.addView(label);

        boolean muted = Boolean.TRUE.equals(config.get(Settings.AUTO_MUTE_ADS));
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setGravity(Gravity.CENTER);
        if (muted) {
            ImageView muteIcon = new ImageView(activity);
            muteIcon.setImageDrawable(new ActionIconDrawable(
                    ActionIconDrawable.Kind.VOLUME_OFF, Color.rgb(190, 180, 255),
                    activity.getResources().getDisplayMetrics().density));
            statusRow.addView(muteIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        TextView hint = textFactory.createText(
                activity,
                muted ? "Ad audio muted · Lyrics return after the ad"
                        : "Lyrics return after the ad",
                13,
                Color.rgb(178, 178, 188),
                textFactory.resolveTypeface(false));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(muted ? dp(6) : 0, dp(10), 0, 0);
        statusRow.addView(hint);
        card.addView(statusRow);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(80);
        cardLp.leftMargin = dp(32);
        cardLp.rightMargin = dp(32);
        lyricsColumn.addView(card, cardLp);

        // Subtle badge pulse keeps the ad state alive without making the whole placeholder jump.
        ValueAnimator pulse = ValueAnimator.ofFloat(0.88f, 1f);
        pulse.setDuration(1200);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            badge.setAlpha(v);
            badge.setScaleX(v);
            badge.setScaleY(v);
        });
        pulse.start();
        card.setTag(pulse);

        lyricsScroll.post(() -> lyricsScroll.scrollTo(0, 0));
    }

    void showLoading(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message, int horizontalInsetPx) {
        stateToken++;
        lyricsColumn.removeAllViews();
        if (config.get(Settings.SHOW_SKELETON)) {
            LyricsSkeletonView skeleton = new LyricsSkeletonView(activity);
            int horizontalPad = horizontalInsetPx > 0 ? horizontalInsetPx : dp(18);
            skeleton.setHorizontalPaddingPx(horizontalPad);
            LinearLayout.LayoutParams skeletonLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lyricsColumn.addView(skeleton, skeletonLp);
            // Height may be 0 if called before layout; defer margin calculation to
            // the first layout pass via a posted runnable (never immediate) so the
            // offset is always correct.
            alignLoadingStart(lyricsScroll, lyricsColumn, skeleton, horizontalPad);
            return;
        }
        TextView loading = textFactory.createText(
                activity,
                message,
                22,
                Color.rgb(179, 179, 179),
                textFactory.resolveTypeface(true));
        loading.setGravity(Gravity.CENTER);
        int pad = horizontalInsetPx > 0 ? horizontalInsetPx : dp(16);
        loading.setPadding(pad, dp(100), pad, dp(16));
        lyricsColumn.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Defer scroll reset to the layout pass so lyricsScroll.getHeight() is valid.
        lyricsScroll.post(() -> lyricsScroll.scrollTo(0, 0));
    }

    private void alignLoadingStart(
            ScrollView lyricsScroll,
            LinearLayout lyricsColumn,
            View loadingView
    ) {
        alignLoadingStart(lyricsScroll, lyricsColumn, loadingView, 0);
    }

    private void alignLoadingStart(
            ScrollView lyricsScroll,
            LinearLayout lyricsColumn,
            View loadingView,
            int horizontalMarginPx
    ) {
        Runnable align = () -> {
            if (loadingView.getParent() != lyricsColumn) return;
            ViewGroup.LayoutParams rawParams = loadingView.getLayoutParams();
            if (!(rawParams instanceof LinearLayout.LayoutParams)) return;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) rawParams;
            int topMargin = loadingTopMargin(
                    lyricsScroll.getHeight(), lyricsScroll.getPaddingTop());
            if (params.topMargin != topMargin) {
                params.topMargin = topMargin;
            }
            int hMargin = horizontalMarginPx > 0 ? horizontalMarginPx : dp(12);
            if (params.leftMargin != hMargin) params.leftMargin = hMargin;
            if (params.rightMargin != hMargin) params.rightMargin = hMargin;
            loadingView.setLayoutParams(params);
            lyricsScroll.scrollTo(0, 0);
        };
        // Height may be 0 on first call (pre-layout). Post instead of running immediately
        // so lyricsScroll.getHeight() always returns a valid value after layout.
        lyricsScroll.post(align);
    }

    private int loadingTopMargin(int viewportHeightPx, int paddingTopPx) {
        if (viewportHeightPx <= 0) return 0;
        // Position loading at the top of the lyrics area (where first line appears)
        // instead of centering in the viewport. This matches the lyrics render position.
        return Math.max(0, paddingTopPx + dp(56));
    }

    void showError(LinearLayout lyricsColumn, String error) {
        final int token = ++stateToken;
        lyricsColumn.removeAllViews();
        LinearLayout errorBox = new LinearLayout(activity);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = textFactory.createText(
                activity,
                "No lyrics found",
                24,
                Color.WHITE,
                textFactory.resolveTypeface(true));
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(16), dp(80), dp(16), dp(8));
        errorBox.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView message = textFactory.createText(
                activity,
                safe(error),
                14,
                Color.rgb(179, 179, 179),
                textFactory.resolveTypeface(false));
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(16), dp(4), dp(16), dp(16));
        errorBox.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(errorBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.postDelayed(() -> {
            if (token != stateToken || errorBox.getParent() != lyricsColumn) return;
            errorBox.animate().alpha(0f).setDuration(350L).withEndAction(() -> {
                if (token != stateToken || errorBox.getParent() != lyricsColumn) return;
                lyricsColumn.removeAllViews();
                showInterludeIndicator(lyricsColumn);
            }).start();
        }, ERROR_DISPLAY_MS);
    }

    private void showInterludeIndicator(LinearLayout lyricsColumn) {
        boolean noteMode = "note".equals(config.get(Settings.INTERLUDE_ICON));
        TextView indicator = textFactory.createText(
                activity, noteMode ? "♪" : "•  •  •", noteMode ? 38 : 44,
                Color.WHITE, textFactory.resolveTypeface(true));
        indicator.setGravity(Gravity.START);
        indicator.setAlpha(0f);
        indicator.setPadding(dp(16), dp(80), dp(16), dp(16));
        lyricsColumn.addView(indicator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        indicator.animate().alpha(1f).setDuration(350L).start();
    }
}
