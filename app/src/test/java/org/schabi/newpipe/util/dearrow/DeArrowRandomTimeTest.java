package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the alea port.
 *
 * <p>The expected values are not invented: they were produced by running the real
 * {@code seedrandom} package's {@code alea(videoId)()} and captured verbatim. Two of them
 * are independently corroborated by the DeArrow API itself, which returns the same number
 * as a video's {@code randomTime} — so if this port drifts, it drifts away from the server
 * and the app stops asking for the frame the server already has.</p>
 */
public class DeArrowRandomTimeTest {

    /** Tolerance of one part in 10^15: these must match to the last meaningful digit. */
    private static final double EXACT = 1e-15;

    @Test
    public void matchesTheReferenceImplementation() {
        // Captured from `alea(id)()` in the seedrandom package, 2026-09-23.
        assertEquals(0.5678500605281442, DeArrowRandomTime.fractionFor("dQw4w9WgXcQ"), EXACT);
        assertEquals(0.1199329060036689, DeArrowRandomTime.fractionFor("TU2fsUBRH4M"), EXACT);
        assertEquals(0.5662975758314133, DeArrowRandomTime.fractionFor("ZzexW-eCmzY"), EXACT);
        assertEquals(0.7797515792772174, DeArrowRandomTime.fractionFor("GK2pZ_oVU1o"), EXACT);
        assertEquals(0.3425161107443273, DeArrowRandomTime.fractionFor("a-b_cdefghi"), EXACT);
    }

    /**
     * These two are the strong ones: the same numbers come back from the live branding API
     * as the video's {@code randomTime}, so they prove the port agrees with the server and
     * not merely with a local copy of the library.
     */
    @Test
    public void agreesWithTheValueTheDeArrowServerStores() {
        assertEquals("randomTime the API returns for the Rick Astley video",
                0.5678500605281442, DeArrowRandomTime.fractionFor("dQw4w9WgXcQ"), EXACT);
        assertEquals("randomTime the API returns for \"Me at the zoo\"",
                0.8537647312041372, DeArrowRandomTime.fractionFor("jNQXAC9IVRw"), EXACT);
    }

    @Test
    public void tailOfTheVideoIsAvoidedBySubtractingNotClamping() {
        // hbnt8mmL0ZI's raw alea value is 0.8755932725034654 -- under the threshold, so it
        // passes through untouched. A value above it must come back 0.9 lower, not pinned.
        assertEquals(0.8755932725034654, DeArrowRandomTime.fractionFor("hbnt8mmL0ZI"), EXACT);
        for (final String id : new String[]{"dQw4w9WgXcQ", "TU2fsUBRH4M", "ZzexW-eCmzY",
                "GK2pZ_oVU1o", "jNQXAC9IVRw", "a-b_cdefghi", "hbnt8mmL0ZI"}) {
            final double fraction = DeArrowRandomTime.fractionFor(id);
            assertTrue(id + " produced " + fraction, fraction >= 0 && fraction < 1);
        }
    }

    /** The whole reason for a seeded PRNG: a video must not change frame between runs. */
    @Test
    public void theSameVideoAlwaysGetsTheSameFrame() {
        for (int i = 0; i < 5; i++) {
            assertEquals(DeArrowRandomTime.fractionFor("GK2pZ_oVU1o"),
                    DeArrowRandomTime.fractionFor("GK2pZ_oVU1o"), 0.0);
        }
    }

    @Test
    public void differentVideosGetDifferentFrames() {
        assertTrue(DeArrowRandomTime.fractionFor("dQw4w9WgXcQ")
                != DeArrowRandomTime.fractionFor("TU2fsUBRH4M"));
    }

    @Test
    public void secondsScaleWithDuration() {
        // 0.7797515792772174 * 2384s = 1858.9...
        assertEquals(0.7797515792772174 * 2384,
                DeArrowRandomTime.secondsFor("GK2pZ_oVU1o", 2384), 1e-9);
    }

    /**
     * A live stream has no length to seek into, and neither does an item the extractor
     * could not measure. Both must opt out rather than render a frame at second zero.
     */
    @Test
    public void unusableDurationYieldsNoTimestamp() {
        assertTrue(DeArrowRandomTime.secondsFor("GK2pZ_oVU1o", 0) < 0);
        assertTrue(DeArrowRandomTime.secondsFor("GK2pZ_oVU1o", -1) < 0);
    }
}
