package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Builds the thumbnail URL for a live stream.
 *
 * <p>Live streams cannot use the ordinary path. There is no fixed duration, so
 * {@link DeArrowRandomTime} has nothing to multiply, and there is no stable timestamp to
 * seek to in a broadcast that is still being produced — which is why the first version
 * skipped them entirely and a live-heavy screen showed no change at all.</p>
 *
 * <p>The browser extension solves this on the server rather than locally. From
 * {@code dataFetching.ts}: <em>"Live videos have no backup, so try to generate it now"</em>
 * — it passes {@code generateNow: true} whenever the video is live. Verified against the
 * live service on 2026-09-24: that request returned a real 640x360 frame of an in-progress
 * GTA stream, where the same endpoint returns HTTP 204 for ordinary videos it has not
 * already rendered.</p>
 *
 * <p>No timestamp is sent. The server picks the current moment of the broadcast, which is
 * the only meaningful choice — and means a live thumbnail legitimately changes between
 * views, unlike the seeded frame a normal video gets.</p>
 */
public final class DeArrowLiveThumbnail {

    private DeArrowLiveThumbnail() {
    }

    /**
     * @param videoId the live stream
     * @param config  supplies the thumbnail host, which may be a self-hosted mirror
     * @return a URL that renders the current frame of the broadcast
     */
    @NonNull
    public static String urlFor(@NonNull final String videoId,
                                @NonNull final DeArrowConfig config) {
        return config.getThumbnailApiUrl() + "?videoID=" + videoId + "&generateNow=true";
    }

    /**
     * Whether this item should take the live path rather than the seeded-frame one.
     *
     * <p>Uses the duration as the signal because that is what a list row actually carries:
     * the extractor reports 0 (or less) for a broadcast, and a row has no
     * {@code StreamType} to consult before it is opened.</p>
     *
     * @param durationSeconds the reported length
     * @return true if there is no length to seek into
     */
    public static boolean isLive(final long durationSeconds) {
        return durationSeconds <= 0;
    }

    /**
     * @param videoId         the video
     * @param durationSeconds its reported length
     * @param config          the user's settings
     * @return the live thumbnail URL, or null if this is not a live item
     */
    @Nullable
    public static String urlForLiveOnly(@NonNull final String videoId,
                                        final long durationSeconds,
                                        @NonNull final DeArrowConfig config) {
        return isLive(durationSeconds) ? urlFor(videoId, config) : null;
    }
}
