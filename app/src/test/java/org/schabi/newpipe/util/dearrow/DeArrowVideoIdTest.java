package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for video-id extraction.
 *
 * <p>Worth testing on its own because the failure mode is invisible: a URL shape this regex misses
 * does not crash or log anything, it just means DeArrow silently never fires for that surface.</p>
 */
public class DeArrowVideoIdTest {

    @Test
    public void extractsFromAWatchUrl() {
        assertEquals("dQw4w9WgXcQ",
                DeArrowVideoId.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
    }

    @Test
    public void extractsWhenOtherQueryParametersFollow() {
        assertEquals("dQw4w9WgXcQ",
                DeArrowVideoId.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"));
    }

    @Test
    public void extractsWhenOtherQueryParametersPrecede() {
        assertEquals("dQw4w9WgXcQ",
                DeArrowVideoId.fromUrl("https://www.youtube.com/watch?app=desktop&v=dQw4w9WgXcQ"));
    }

    @Test
    public void extractsFromAShortLink() {
        assertEquals("dQw4w9WgXcQ", DeArrowVideoId.fromUrl("https://youtu.be/dQw4w9WgXcQ"));
    }

    @Test
    public void extractsFromShortsEmbedAndLive() {
        assertEquals("dQw4w9WgXcQ",
                DeArrowVideoId.fromUrl("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ",
                DeArrowVideoId.fromUrl("https://www.youtube.com/embed/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ",
                DeArrowVideoId.fromUrl("https://www.youtube.com/live/dQw4w9WgXcQ"));
    }

    @Test
    public void extractsIdsContainingHyphensAndUnderscores() {
        assertEquals("a-b_cdefghi",
                DeArrowVideoId.fromUrl("https://www.youtube.com/watch?v=a-b_cdefghi"));
    }

    @Test
    public void returnsNullForUrlsThatAreNotVideos() {
        assertNull(DeArrowVideoId.fromUrl("https://www.youtube.com/@someChannel"));
        assertNull(DeArrowVideoId.fromUrl("https://www.youtube.com/playlist?list=PL1234567890"));
        assertNull(DeArrowVideoId.fromUrl("https://www.bilibili.com/video/BV1xx411c7mD"));
    }

    @Test
    public void returnsNullForNullOrEmpty() {
        assertNull(DeArrowVideoId.fromUrl(null));
        assertNull(DeArrowVideoId.fromUrl(""));
    }

    @Test
    public void rejectsIdsOfTheWrongLength() {
        assertNull(DeArrowVideoId.fromUrl("https://www.youtube.com/watch?v=tooShort"));
        assertFalse(DeArrowVideoId.isValid("tooShort"));
        assertTrue(DeArrowVideoId.isValid("dQw4w9WgXcQ"));
    }
}
