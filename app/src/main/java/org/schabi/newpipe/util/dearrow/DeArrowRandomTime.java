package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

/**
 * Picks the timestamp to grab a frame from when a video has no community thumbnail.
 *
 * <p>This is a port of the {@code alea} seeded PRNG that DeArrow uses (from the
 * {@code seedrandom} package, originally Johannes Baagøe's Alea). Porting it exactly rather
 * than using any random source matters for two reasons:</p>
 *
 * <ul>
 *   <li><b>The same video always shows the same frame</b> — on every device, every launch.
 *       Anything seeded on the clock would make a video's thumbnail change each time it
 *       scrolled past, which looks broken.</li>
 *   <li><b>It is the same number the DeArrow server itself stores.</b> Verified against the
 *       live API: {@code alea("dQw4w9WgXcQ")()} is {@code 0.5678500605281442}, which is
 *       exactly the {@code randomTime} the branding endpoint returns for that video. Using
 *       the same seed means the server's already-rendered frame is the one we ask for.</li>
 * </ul>
 *
 * <p>Needed because the branding API returns <em>nothing at all</em> for a video with no
 * submissions — it is simply absent from its hash bucket, so there is no {@code randomTime}
 * to read and the client has to derive it.</p>
 */
public final class DeArrowRandomTime {

    /** 2^-32, the scale alea uses to turn a uint32 into a fraction. */
    private static final double INV_2_32 = 2.3283064365386963e-10;

    /** 2^32. */
    private static final double TWO_32 = 4294967296.0;

    /** Alea's multiplier and Mash's seed constant, both fixed by the algorithm. */
    private static final double MULTIPLIER = 2091639;
    private static final long MASH_SEED = 0xefc8249dL;
    private static final double MASH_SCALE = 0.02519603282416938;

    /**
     * Frames are never taken from the last tenth of a video: that is where endscreens,
     * outros and subscribe animations live, which are exactly as useless as a clickbait
     * thumbnail. Upstream subtracts this rather than clamping, so the distribution stays
     * even instead of piling up at the boundary.
     */
    @VisibleForTesting
    static final double TAIL_TO_AVOID = 0.9;

    private DeArrowRandomTime() {
    }

    /**
     * @param videoId the video id, used as the PRNG seed
     * @return a fraction in [0, 0.9) of the way through the video
     */
    @VisibleForTesting
    static double fractionFor(@NonNull final String videoId) {
        double fraction = new Alea(videoId).next();
        if (fraction > TAIL_TO_AVOID) {
            fraction -= TAIL_TO_AVOID;
        }
        return fraction;
    }

    /**
     * The timestamp to grab a frame from.
     *
     * @param videoId         the video id
     * @param durationSeconds the video's length; must be positive
     * @return seconds into the video, or -1 if the duration is unusable (a live stream, or
     *         an item the extractor could not give a length for)
     */
    public static double secondsFor(@NonNull final String videoId, final long durationSeconds) {
        if (durationSeconds <= 0) {
            return -1;
        }
        return fractionFor(videoId) * durationSeconds;
    }

    /**
     * Johannes Baagøe's Alea, as shipped in {@code seedrandom}.
     *
     * <p>Written against JavaScript semantics, so every {@code >>> 0} becomes an explicit
     * ToUint32 and every {@code | 0} an explicit truncation. Getting either wrong produces
     * plausible-looking numbers that silently disagree with the server.</p>
     */
    private static final class Alea {
        private double s0;
        private double s1;
        private double s2;
        private long c = 1;

        Alea(@NonNull final String seed) {
            // One Mash instance across all six calls: its internal state carries over, and
            // using a fresh one per call gives different (wrong) results.
            final Mash mash = new Mash();
            s0 = mash.mash(" ");
            s1 = mash.mash(" ");
            s2 = mash.mash(" ");

            s0 -= mash.mash(seed);
            if (s0 < 0) {
                s0 += 1;
            }
            s1 -= mash.mash(seed);
            if (s1 < 0) {
                s1 += 1;
            }
            s2 -= mash.mash(seed);
            if (s2 < 0) {
                s2 += 1;
            }
        }

        double next() {
            final double t = MULTIPLIER * s0 + c * INV_2_32;
            s0 = s1;
            s1 = s2;
            c = (long) t;          // JS `t | 0`: truncate toward zero
            s2 = t - c;
            return s2;
        }
    }

    /** Alea's companion hash. Stateful by design — see the note in {@link Alea}. */
    private static final class Mash {
        private double n = MASH_SEED;

        double mash(@NonNull final String data) {
            for (int i = 0; i < data.length(); i++) {
                n += data.charAt(i);
                double h = MASH_SCALE * n;
                n = toUint32(h);
                h -= n;
                h *= n;
                n = toUint32(h);
                h -= n;
                n += h * TWO_32;
            }
            return toUint32(n) * INV_2_32;
        }
    }

    /**
     * JavaScript's ToUint32: truncate toward zero, then take it modulo 2^32.
     *
     * @param value any finite double
     * @return the same value as a uint32, expressed as a double so it can keep being used
     *         in floating-point arithmetic exactly as the original does
     */
    private static double toUint32(final double value) {
        double truncated = value < 0 ? Math.ceil(value) : Math.floor(value);
        truncated %= TWO_32;
        if (truncated < 0) {
            truncated += TWO_32;
        }
        return truncated;
    }
}
