package com.eza.spicyex.lyrics;

/**
 * Per-row spring profile for the Apple-style row cascade: pure motion math, no views.
 *
 * <p>Every row in the wave starts displaced by exactly the scroll delta the ScrollView has already
 * carried the content by, so the column is visually untouched on the frame the wave starts.
 * Weighting that starting <em>position</em> per row was tried and tears the column into bands that
 * each pop by a different fraction of a line before any spring has run. The wave therefore lives in
 * three things that are all continuous at t=0:
 *
 * <ul>
 *   <li>a small stagger, so rows further from the focused line leave slightly later;</li>
 *   <li>a launch velocity that leads the focused row toward its rest position and lets distant rows
 *       sag a little further first - the rope droop that makes the column read as one flexible
 *       thing rather than a rigid block sliding;</li>
 *   <li>a per-row spring that is stiffer and livelier at the focus and softer further out.</li>
 * </ul>
 *
 * <p>The launch velocity matters most on slow devices. A stagger of a few tens of milliseconds is
 * less than one frame on a phone rendering at 30fps, so a delay-only wave quantises onto frame
 * boundaries and degenerates into rows jumping in lockstep one frame apart - the "stepping" that
 * reads as jank. Velocity shaping is frame-rate independent: the same motion comes out at 30, 60
 * and 120fps, just sampled more coarsely.
 */
public final class LyricCascadeProfile {
    /**
     * Extra speed toward rest given to the focused row, per pixel of scroll delta, per second, at
     * {@link #REFERENCE_FREQUENCY_HZ}. Read it as a head start: a row launched at {@code v} reaches
     * a given point roughly {@code v / (omega^2 * delta)} earlier than one starting from rest.
     *
     * <p>Kept deliberately small. A launch is speed the row has on its very first frame, where a
     * spring starting from rest has almost none, so it is the one term here that can make the
     * motion look hard rather than weighted - and it does so worst exactly where it can least be
     * afforded. On a device holding 30fps a frame is 33ms, so every unit of this is 33ms worth of
     * visible jump on frame one: at 5.2 the focused row left with a ~13px step against the ~3px a
     * rest start gives, and the whole cascade read as snapping rather than travelling. The wave is
     * carried by the stagger and the per-row spring instead; this only tilts it.
     */
    private static final float LEAD_LAUNCH_PER_PX = 2.6f;
    /** Speed away from rest given to the furthest rows - the visible sag behind the wave. Less
     *  sensitive than the lead: it starts the row slowly by definition, since it works against the
     *  spring rather than with it. */
    private static final float TRAIL_DROOP_PER_PX = 2.0f;
    /** The frequency the two constants above are expressed at; the launch scales with the row's own
     *  frequency so that raising the cascade speed shortens the wave proportionally rather than
     *  quadratically (the head start a given speed buys goes as 1/omega^2). */
    private static final float REFERENCE_FREQUENCY_HZ = 1.85f;
    /** Rows this far from the focused line behave as fully "distant"; nearer ones blend in. */
    private static final float FALLOFF_SPAN_ROWS = 6f;
    /** How much slower the furthest rows are than the focused one. */
    private static final float FAR_FREQUENCY_DROP = 0.18f;
    /** How much more damped - so less lively - the furthest rows are. */
    private static final float FAR_DAMPING_GAIN = 0.08f;

    public final float delaySeconds;
    public final float frequencyHz;
    public final float damping;
    public final float launchVelocityPxPerSec;

    private LyricCascadeProfile(float delaySeconds, float frequencyHz, float damping,
                                float launchVelocityPxPerSec) {
        this.delaySeconds = delaySeconds;
        this.frequencyHz = frequencyHz;
        this.damping = damping;
        this.launchVelocityPxPerSec = launchVelocityPxPerSec;
    }

    /**
     * @param scrollDeltaPx how far the ScrollView already moved; every row starts displaced by
     *                      exactly this, so the sign here is the direction the rows travel back in.
     * @param rowDistance   rows between this one and the focused line (0 = the focused line).
     */
    public static LyricCascadeProfile forRow(
            float scrollDeltaPx,
            float rowDistance,
            float baseFrequencyHz,
            float baseDamping,
            float staggerSeconds,
            float maxDelaySeconds
    ) {
        // Smoothstep rather than a straight ramp: the difference between neighbouring rows is
        // smallest right around the focused line, where they sit side by side and any abrupt change
        // in behaviour between them is most visible.
        float reach = clamp01(Math.max(0f, rowDistance) / FALLOFF_SPAN_ROWS);
        float falloff = reach * reach * (3f - 2f * reach);
        float delay = Math.min(Math.max(0f, maxDelaySeconds),
                Math.max(0f, rowDistance) * Math.max(0f, staggerSeconds));
        float frequency = Math.max(0.2f, baseFrequencyHz * (1f - FAR_FREQUENCY_DROP * falloff));
        float damping = Math.min(1f, Math.max(0.1f, baseDamping + FAR_DAMPING_GAIN * falloff));
        // Negative = toward rest. The focused row is thrown at its target and overshoots it
        // slightly, which is the small dip the line settles out of; distant rows are pushed the
        // other way first and sag behind the wave instead of tracking it.
        float launch = -scrollDeltaPx
                * (LEAD_LAUNCH_PER_PX * (1f - falloff) - TRAIL_DROOP_PER_PX * falloff)
                * (frequency / REFERENCE_FREQUENCY_HZ);
        return new LyricCascadeProfile(delay, frequency, damping, launch);
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
