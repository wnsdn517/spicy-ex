package com.eza.spicyex.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * The like acknowledgement, drawn once and then removed - restrained, in the manner of the
 * system's own: no confetti, no ring, no tilt.
 *
 * <p>The big (double-tap) form is a single heart or star that springs in with only a slight
 * overshoot over a soft glow of its own colour, rests for a moment, then dissolves - growing a
 * touch while it fades rather than flying off. The small form is just that glow, breathing out
 * once behind the like button as it turns on.
 *
 * <p>Add it over everything and call {@link #play}; it takes no touches and detaches itself when
 * done. One animator drives every part, so nothing can drift apart.
 */
public final class LikeBurstView extends View {
    private static final long BIG_MS = 1150L;
    private static final long SMALL_MS = 620L;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon;
    private final boolean big;
    private final float cx;
    private final float cy;
    private final float radius;
    private float t;

    /**
     * @param star  a star (gold) instead of a heart (the system pink)
     * @param big   the double-tap form with the icon; false is the glow alone
     * @param x     centre, in the parent's coordinates
     * @param size  the icon's size in px (big), or the button's size (small)
     */
    public LikeBurstView(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.big = big;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.icon = ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART);
        int top = star ? Color.rgb(255, 214, 10) : Color.rgb(255, 55, 95);
        int bottom = star ? Color.rgb(255, 176, 0) : Color.rgb(230, 30, 75);
        int glow = star ? Color.rgb(255, 204, 0) : Color.rgb(255, 45, 85);
        iconPaint.setStyle(Paint.Style.FILL);
        // In the icon's 24-unit box, so it scales with the path: a quiet top-to-bottom depth.
        iconPaint.setShader(new LinearGradient(0f, 3f, 0f, 21f, top, bottom, Shader.TileMode.CLAMP));
        // A faint light across the upper half, the way a glossy system glyph catches light.
        sheenPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setShader(new LinearGradient(0f, 3f, 0f, 13f, 0x38FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.BLACK);
        // A unit-radius glow, scaled per frame.
        glowPaint.setShader(new RadialGradient(0f, 0f, 1f,
                new int[]{withAlpha(glow, 0x66), withAlpha(glow, 0x22), withAlpha(glow, 0)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Adds the burst to {@code parent} and plays it; it removes itself at the end. */
    public void play(ViewGroup parent) {
        parent.addView(this, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(big ? BIG_MS : SMALL_MS);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        clock.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(LikeBurstView.this);
            }
        });
        clock.start();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (big) drawBig(canvas); else drawPulse(canvas);
    }

    /** The button form: one soft breath of light behind it. */
    private void drawPulse(Canvas canvas) {
        float e = easeOutCubic(t);
        float r = radius * (0.7f + 0.9f * e);
        float alpha = (1f - t) * (1f - t);
        drawGlow(canvas, r, alpha);
    }

    private void drawBig(Canvas canvas) {
        // Timeline, as fractions of the whole: spring in over the first 38%, rest until 62%,
        // then dissolve.
        float scale;
        float alpha;
        if (t < 0.38f) {
            float p = t / 0.38f;
            scale = 0.45f + 0.55f * spring(p);
            alpha = Math.min(1f, p * 3.5f);
        } else if (t < 0.62f) {
            scale = 1f;
            alpha = 1f;
        } else {
            float p = (t - 0.62f) / 0.38f;
            float e = easeInOut(p);
            scale = 1f + 0.14f * e;
            alpha = 1f - e;
        }
        if (alpha <= 0.003f) return;

        drawGlow(canvas, radius * 1.55f * scale, alpha * 0.9f);

        float size = radius * 2f * scale;
        canvas.save();
        canvas.translate(cx - size / 2f, cy - size / 2f);
        canvas.scale(size / 24f, size / 24f);
        // A soft contact shadow: the shape again, a little lower and faint.
        canvas.save();
        canvas.translate(0f, 0.7f);
        shadowPaint.setAlpha(Math.round(0x30 * alpha));
        canvas.drawPath(icon, shadowPaint);
        canvas.restore();
        iconPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(icon, iconPaint);
        sheenPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(icon, sheenPaint);
        canvas.restore();
    }

    private void drawGlow(Canvas canvas, float r, float alpha) {
        if (r <= 0f || alpha <= 0.003f) return;
        glowPaint.setAlpha(Math.round(255 * Math.min(1f, alpha)));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(r, r);
        canvas.drawCircle(0f, 0f, 1f, glowPaint);
        canvas.restore();
    }

    /** A lightly damped spring from 0 to 1: about 6% overshoot, settled by the end. */
    private static float spring(float p) {
        double damping = 0.62;
        double omega = 11.0;
        double decay = Math.exp(-damping * omega * p);
        double wd = omega * Math.sqrt(1 - damping * damping);
        return (float) (1 - decay * (Math.cos(wd * p) + damping * omega / wd * Math.sin(wd * p)));
    }

    private static float easeOutCubic(float p) {
        float q = 1f - p;
        return 1f - q * q * q;
    }

    private static float easeInOut(float p) {
        return p < 0.5f ? 4f * p * p * p : 1f - (float) Math.pow(-2f * p + 2f, 3) / 2f;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
