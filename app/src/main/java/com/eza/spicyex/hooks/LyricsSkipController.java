package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeIconButtons.applyPressScale;
import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.ui.ActionIconDrawable;

/** Owns the floating "skip intro" / "next track" pill shown during the instrumental stretches
 * before the first synced line and after the last one. */
final class LyricsSkipController {
    private static final OvershootInterpolator ENTER_INTERPOLATOR = new OvershootInterpolator(2.2f);

    private final View pill;
    private final TextView label;
    private String shownLabel = "";

    private LyricsSkipController(View pill, TextView label) {
        this.pill = pill;
        this.label = label;
    }

    static LyricsSkipController attach(Activity activity, FrameLayout parent, LyricsTextFactory textFactory) {
        int textColor = Color.rgb(248, 248, 250);
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(20), dp(13), dp(16), dp(13));
        pill.setAlpha(0f);
        pill.setScaleX(0.82f);
        pill.setScaleY(0.82f);
        pill.setVisibility(View.GONE);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setElevation(dp(6));

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(26));
        background.setColor(Color.argb(168, 32, 32, 38));
        background.setStroke(dp(1), Color.argb(46, 255, 255, 255));
        pill.setBackground(background);
        applyPressScale(pill);

        TextView labelView = textFactory.createText(activity, "", 14, textColor, textFactory.resolveTypeface(true));
        labelView.setIncludeFontPadding(false);
        labelView.setLetterSpacing(0.01f);
        pill.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView chevron = new ImageView(activity);
        chevron.setImageDrawable(new ActionIconDrawable(ActionIconDrawable.Kind.CHEVRON_RIGHT, textColor, density));
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(17), dp(17));
        chevronLp.leftMargin = dp(3);
        pill.addView(chevron, chevronLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, 0, 0, dp(96));
        parent.addView(pill, lp);
        return new LyricsSkipController(pill, labelView);
    }

    void show(String labelText, Runnable onTap) {
        pill.setOnClickListener(v -> {
            if (onTap != null) onTap.run();
        });
        if (!labelText.equals(shownLabel)) {
            shownLabel = labelText;
            label.setText(labelText);
        }
        if (pill.getVisibility() != View.VISIBLE) {
            pill.animate().cancel();
            pill.setVisibility(View.VISIBLE);
            pill.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(ENTER_INTERPOLATOR)
                    .setDuration(340L)
                    .start();
        }
    }

    void hide() {
        if (pill.getVisibility() == View.GONE) return;
        shownLabel = "";
        pill.animate().cancel();
        pill.animate()
                .alpha(0f)
                .scaleX(0.88f)
                .scaleY(0.88f)
                .setInterpolator(null)
                .setDuration(140L)
                .withEndAction(() -> pill.setVisibility(View.GONE))
                .start();
    }
}
