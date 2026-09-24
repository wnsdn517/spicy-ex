package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.function.Supplier;

/**
 * Spectrum bars for an instrumental track, shown in place of lyrics.
 *
 * <p>Capsules grow symmetrically out of a centre line, brighter toward their tips, over a soft
 * wider halo of the same shape, with a faint mirrored "floor" line. Each bar rises quickly to
 * the band level it is given and falls back under gravity, so transients read as hits and the
 * decay stays smooth whatever rate the analysis arrives at. With no signal (paused, or nothing
 * measured yet) the bars idle as a slow travelling wave instead of lying flat.
 *
 * <p>Redraws only while attached and something is still moving; the halo is a translucent
 * wider capsule rather than a blur filter, so each frame is a few dozen rounded rects.
 */
public final class InstrumentalVisualizerView extends View {
    private static final float RISE = 0.55f;
    private static final float GRAVITY = 2.6f; // share of full height per second^2
    private static final float IDLE_LEVEL = 0.07f;

    private final Supplier<float[]> spectrum;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float[] level = new float[0];
    private float[] fall = new float[0];
    private long lastFrameNanos;
    private int shaderHeight = -1;

    public InstrumentalVisualizerView(Context context, Supplier<float[]> spectrum) {
        super(context);
        this.spectrum = spectrum;
        halo.setColor(0x33FFFFFF);
        floor.setColor(0x26FFFFFF);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameNanos = 0L;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float[] source = spectrum == null ? null : spectrum.get();
        int bands = source == null || source.length == 0 ? 28 : source.length;
        if (level.length != bands) {
            level = new float[bands];
            fall = new float[bands];
        }
        long now = SystemClock.elapsedRealtimeNanos();
        float dt = lastFrameNanos == 0L ? 1f / 60f
                : Math.max(1f / 240f, Math.min(0.05f, (now - lastFrameNanos) / 1e9f));
        lastFrameNanos = now;

        boolean silent = true;
        if (source != null) {
            for (float v : source) {
                if (v > 0.02f) {
                    silent = false;
                    break;
                }
            }
        }
        double t = now / 1e9;
        boolean moving = false;
        for (int i = 0; i < bands; i++) {
            float target = silent || source == null
                    ? IDLE_LEVEL * (0.55f + 0.45f * (float) Math.sin(t * 1.7 - i * 0.42))
                    : Math.max(IDLE_LEVEL * 0.5f, source[i]);
            float current = level[i];
            if (target > current) {
                current += (target - current) * RISE;
                fall[i] = 0f;
            } else {
                fall[i] += GRAVITY * dt;
                current = Math.max(target, current - fall[i] * dt);
            }
            if (Math.abs(current - level[i]) > 0.0005f) moving = true;
            level[i] = current;
        }

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (shaderHeight != h) {
            shaderHeight = h;
            // Brighter toward the tips (top and bottom), softer at the centre line.
            bar.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xFFFFFFFF, 0xB3FFFFFF, 0xFFFFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        float slot = w / (float) bands;
        float barWidth = slot * 0.46f;
        float mid = h * 0.5f;
        float maxHalf = h * 0.46f;
        for (int i = 0; i < bands; i++) {
            float cx = slot * (i + 0.5f);
            float half = Math.max(barWidth * 0.5f, level[i] * maxHalf);
            float haloPad = barWidth * 0.45f;
            rect.set(cx - barWidth * 0.5f - haloPad, mid - half - haloPad,
                    cx + barWidth * 0.5f + haloPad, mid + half + haloPad);
            float haloRadius = rect.width() * 0.5f;
            canvas.drawRoundRect(rect, haloRadius, haloRadius, halo);
            rect.set(cx - barWidth * 0.5f, mid - half, cx + barWidth * 0.5f, mid + half);
            canvas.drawRoundRect(rect, barWidth * 0.5f, barWidth * 0.5f, bar);
        }
        canvas.drawRect(slot * 0.25f, mid - 0.5f, w - slot * 0.25f, mid + 0.5f, floor);
        if (isAttachedToWindow() && (moving || silent || !isQuiet(source))) {
            postInvalidateOnAnimation();
        }
    }

    private static boolean isQuiet(float[] source) {
        if (source == null) return true;
        for (float v : source) if (v > 0.02f) return false;
        return true;
    }
}
