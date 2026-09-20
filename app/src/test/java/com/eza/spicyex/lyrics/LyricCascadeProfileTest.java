package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the shape of the Apple-style row cascade.
 *
 * <p>The properties asserted here are the ones the motion actually reads as: the focused row leads
 * and arrives first, distant rows sag behind before following, and nothing about the profile
 * depends on the frame rate. The exact constants are free to move; these invariants are not.
 */
public class LyricCascadeProfileTest {

    private static final float DELTA_PX = 120f;
    private static final float BASE_HZ = 1.85f;
    private static final float BASE_DAMPING = 0.74f;
    private static final float STAGGER = 0.020f;
    private static final float MAX_DELAY = 0.10f;

    private static LyricCascadeProfile at(float distance) {
        return LyricCascadeProfile.forRow(
                DELTA_PX, distance, BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY);
    }

    @Test
    public void focusedRowIsThrownTowardRestAndDistantRowsSagAway() {
        // Rows start displaced by +delta and spring back to zero, so "toward rest" is negative.
        assertTrue("focused row should be launched toward its resting place",
                at(0f).launchVelocityPxPerSec < 0f);
        assertTrue("distant rows should sag further out before following",
                at(8f).launchVelocityPxPerSec > 0f);
    }

    @Test
    public void launchVelocityScalesWithAndFollowsTheSignOfTheScrollDelta() {
        LyricCascadeProfile forward = LyricCascadeProfile.forRow(
                DELTA_PX, 0f, BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY);
        LyricCascadeProfile backward = LyricCascadeProfile.forRow(
                -DELTA_PX, 0f, BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY);
        LyricCascadeProfile twiceAsFar = LyricCascadeProfile.forRow(
                2f * DELTA_PX, 0f, BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY);
        assertEquals(-forward.launchVelocityPxPerSec, backward.launchVelocityPxPerSec, 1e-4f);
        assertEquals(2f * forward.launchVelocityPxPerSec, twiceAsFar.launchVelocityPxPerSec, 1e-3f);
    }

    @Test
    public void focusedRowIsTheLiveliestAndDistantRowsAreHeavier() {
        LyricCascadeProfile focused = at(0f);
        LyricCascadeProfile far = at(8f);
        assertEquals(BASE_HZ, focused.frequencyHz, 1e-4f);
        assertEquals(BASE_DAMPING, focused.damping, 1e-4f);
        assertTrue("distant rows travel more slowly", far.frequencyHz < focused.frequencyHz);
        assertTrue("distant rows settle with less life", far.damping > focused.damping);
    }

    @Test
    public void focusedRowNeverWaitsAndTheStaggerIsBounded() {
        assertEquals(0f, at(0f).delaySeconds, 1e-6f);
        assertEquals(STAGGER, at(1f).delaySeconds, 1e-6f);
        assertEquals(MAX_DELAY, at(40f).delaySeconds, 1e-6f);
    }

    @Test
    public void profileIsContinuousAcrossNeighbouringRows() {
        // Any step in behaviour between two rows sitting side by side is directly visible, so the
        // falloff must not have a knee in it.
        float launchScale = Math.abs(at(0f).launchVelocityPxPerSec);
        for (float distance = 0f; distance < 9f; distance += 1f) {
            LyricCascadeProfile here = at(distance);
            LyricCascadeProfile justPast = at(distance + 0.01f);
            assertEquals("frequency jumps at distance " + distance,
                    here.frequencyHz, justPast.frequencyHz, 0.01f);
            assertEquals("damping jumps at distance " + distance,
                    here.damping, justPast.damping, 0.01f);
            assertEquals("launch velocity jumps at distance " + distance,
                    here.launchVelocityPxPerSec, justPast.launchVelocityPxPerSec,
                    launchScale * 0.02f);
        }
    }

    @Test
    public void launchScalesWithFrequencySoRaisingTheSpeedShortensTheWaveProportionally() {
        // The head start a launch buys goes as v / (omega^2 * delta), so a launch that did not
        // track the frequency would shrink the wave quadratically as the speed setting went up -
        // a "faster" cascade would arrive as one rigid block rather than a quicker wave.
        float single = Math.abs(LyricCascadeProfile.forRow(
                DELTA_PX, 0f, BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY).launchVelocityPxPerSec);
        float doubled = Math.abs(LyricCascadeProfile.forRow(
                DELTA_PX, 0f, 2f * BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY).launchVelocityPxPerSec);
        assertEquals(2f * single, doubled, 1e-2f);
    }

    @Test
    public void dampingStaysUnderdampedSoTheFocusedLineVisiblyDips() {
        // A critically damped row only eases into place. The dip the active line settles out of is
        // the spring overshooting, which needs a damping ratio meaningfully below 1.
        assertTrue(at(0f).damping < 0.9f);
        assertTrue("even the furthest rows must not go dead", at(20f).damping < 1f);
    }

    @Test
    public void degenerateInputsStayInRange() {
        LyricCascadeProfile zeroDelta = LyricCascadeProfile.forRow(
                0f, 3f, BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY);
        assertEquals(0f, zeroDelta.launchVelocityPxPerSec, 1e-6f);
        LyricCascadeProfile negativeDistance = LyricCascadeProfile.forRow(
                DELTA_PX, -5f, BASE_HZ, BASE_DAMPING, STAGGER, MAX_DELAY);
        assertEquals(0f, negativeDistance.delaySeconds, 1e-6f);
        LyricCascadeProfile bouncy = LyricCascadeProfile.forRow(
                DELTA_PX, 0f, BASE_HZ, 0.05f, STAGGER, MAX_DELAY);
        assertTrue(bouncy.damping > 0f);
        assertTrue(bouncy.frequencyHz > 0f);
    }
}
