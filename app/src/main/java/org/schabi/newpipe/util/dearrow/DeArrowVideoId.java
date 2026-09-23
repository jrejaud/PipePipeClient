package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a YouTube video id from the URLs the extractor produces.
 *
 * <p>Kept separate from {@link DeArrowBinder} so that it has no Android dependencies and can be
 * unit-tested directly; URL shapes are exactly the sort of thing that is easy to get subtly wrong
 * and impossible to notice, because the failure mode is "DeArrow silently does nothing".</p>
 */
public final class DeArrowVideoId {

    /**
     * Matches the id in {@code watch?v=ID}, {@code youtu.be/ID}, {@code /shorts/ID},
     * {@code /embed/ID} and {@code /live/ID}. YouTube ids are exactly 11 characters from the
     * URL-safe base64 alphabet.
     */
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:v=|/shorts/|/embed/|/live/|youtu\\.be/)([A-Za-z0-9_-]{11})(?:[?&#]|$)");

    private DeArrowVideoId() {
    }

    /**
     * @param url a YouTube URL, or null
     * @return the 11-character video id, or null if this URL does not contain one (a channel, a
     *         playlist, a malformed URL, or a non-YouTube service)
     */
    @Nullable
    public static String fromUrl(@Nullable final String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        final Matcher matcher = VIDEO_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * @param videoId a candidate id
     * @return true if this is shaped like a YouTube video id
     */
    public static boolean isValid(@NonNull final String videoId) {
        return videoId.matches("[A-Za-z0-9_-]{11}");
    }
}
