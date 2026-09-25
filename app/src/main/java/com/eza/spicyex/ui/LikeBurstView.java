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
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * The like burst, drawn once and then removed: a ring snapping outward and a spray of dots
 * around a point, and in the big (double-tap) form a filled heart or star that pops in with an
 * overshoot, holds a beat at a slight tilt, then floats up and fades - the Instagram Reels
 * double-tap heart. The small form (no icon) plays around the like button when it turns on.
 *
 * <p>Add it over everything with MATCH_PARENT and call {@link #play}; it takes no touches and
 * detaches itself when done. One animator drives every part, so nothing can drift apart.
 */
public final class LikeBurstView extends View {
    private static final int PARTICLES = 10;
    private static final long BIG_MS = 1050L;
    private static final long SMALL_MS = 560L;

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon;
    private final boolean star;
    private final boolean big;
    private final float cx;
    private final float cy;
    /** Radius the ring and particles are laid out against. */
    private final float radius;
    private final float tilt;
    private final float density;
    private final int[] colors;
    private float t;

    /**
     * @param star  a star (gold) instead of a heart (pink)
     * @param big   the double-tap form with the icon; false is the ring and dots only
     * @param x     centre, in the parent's coordinates
     * @param size  the icon's size in px (big), or the button's size (small)
     */
    public LikeBurstView(Context context, boolean star, boolean big, float x, float y, float size) {
        super(context);
        this.star = star;
        this.big = big;
        this.cx = x;
        this.cy = y;
        this.radius = size * 0.5f;
        this.density = context.getResources().getDisplayMetrics().density;
        this.icon = ActionIconDrawable.pathOf(star ? ActionIconDrawable.Kind.STAR
                : ActionIconDrawable.Kind.HEART);
        this.tilt = big ? (float) (Math.random() * 30.0 - 15.0) : 0f;
        this.colors = star
                ? new int[]{Color.rgb(255, 232, 110), Color.rgb(255, 196, 0), Color.rgb(255, 140, 0)}
                : new int[]{Color.rgb(255, 110, 150), Color.rgb(255, 55, 95), Color.rgb(230, 20, 70)};
        iconPaint.setStyle(Paint.Style.FILL);
        // A 24-unit gradient top to bottom, so it scales with the path.
        iconPaint.setShader(new LinearGradient(0f, 2f, 0f, 22f, colors[0], colors[2], Shader.TileMode.CLAMP));
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0x40000000);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(colors[1]);
        dotPaint.setStyle(Paint.Style.FILL);
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
        // Times below are fractions of the whole burst; the small form runs the same script
        // compressed, minus the icon.
        drawRing(canvas, phase(t, big ? 0.04f : 0f, big ? 0.40f : 0.55f));
        drawDots(canvas, phase(t, big ? 0.08f : 0.05f, big ? 0.55f : 1f));
        if (big) drawIcon(canvas);
    }

    private void drawRing(Canvas canvas, float p) {
        if (p <= 0f || p >= 1f) return;
        float e = easeOut(p);
        float r = radius * (0.35f + 1.05f * e);
        ringPaint.setStrokeWidth(Math.max(1f, radius * 0.22f * (1f - e)));
        ringPaint.setAlpha(Math.round(230 * (1f - p)));
        canvas.drawCircle(cx, cy, r, ringPaint);
    }

    private void drawDots(Canvas canvas, float p) {
        if (p <= 0f || p >= 1f) return;
        float e = easeOut(p);
        for (int i = 0; i < PARTICLES; i++) {
            double angle = Math.toRadians(i * (360.0 / PARTICLES) + (big ? tilt : 18f));
            // Alternate two rings of dots, the inner a touch slower, for depth.
            boolean inner = (i & 1) == 1;
            float reach = radius * (inner ? 1.15f : 1.45f);
            float d = radius * 0.55f + (reach - radius * 0.55f) * e;
            float x = cx + (float) Math.cos(angle) * d;
            float y = cy + (float) Math.sin(angle) * d;
            float dot = Math.max(0.6f * density, radius * (inner ? 0.07f : 0.1f) * (1f - p));
            dotPaint.setColor(colors[i % colors.length]);
            dotPaint.setAlpha(Math.round(255 * (1f - p * p)));
            canvas.drawCircle(x, y, dot, dotPaint);
        }
    }

    private void drawIcon(Canvas canvas) {
        float scale;
        float rise = 0f;
        float alpha = 1f;
        if (t < 0.18f) {
            scale = 1.25f * easeOut(t / 0.18f);                           // pop in, past full size
        } else if (t < 0.30f) {
            scale = 1.25f - 0.33f * easeInOut((t - 0.18f) / 0.12f);       // settle under
        } else if (t < 0.40f) {
            scale = 0.92f + 0.08f * easeInOut((t - 0.30f) / 0.10f);       // and back to 1
        } else if (t < 0.68f) {
            scale = 1f;                                                    // hold a beat
        } else {
            float p = (t - 0.68f) / 0.32f;
            float e = easeIn(p);
            scale = 1f - 0.35f * e;
            rise = radius * 1.1f * e;
            alpha = 1f - e;
        }
        if (scale <= 0.01f || alpha <= 0f) return;
        float size = radius * 2f * scale;
        // The tilt eases toward level as it holds, like a sticker settling.
        float angle = tilt * (1f - 0.5f * Math.min(1f, t / 0.68f));
        canvas.save();
        canvas.translate(cx, cy - rise);
        canvas.rotate(angle);
        // Shadow first, a little down, then the icon.
        canvas.save();
        canvas.translate(-size / 2f, -size / 2f + size * 0.04f);
        canvas.scale(size / 24f, size / 24f);
        shadowPaint.setAlpha(Math.round(0x40 * alpha));
        canvas.drawPath(icon, shadowPaint);
        canvas.restore();
        canvas.translate(-size / 2f, -size / 2f);
        canvas.scale(size / 24f, size / 24f);
        iconPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawPath(icon, iconPaint);
        canvas.restore();
    }

    private static float phase(float t, float start, float end) {
        if (t <= start) return 0f;
        if (t >= end) return 1f;
        return (t - start) / (end - start);
    }

    private static float easeOut(float p) {
        float q = 1f - p;
        return 1f - q * q * q;
    }

    private static float easeIn(float p) {
        return p * p;
    }

    private static float easeInOut(float p) {
        return p < 0.5f ? 2f * p * p : 1f - (float) Math.pow(-2f * p + 2f, 2) / 2f;
    }
}
