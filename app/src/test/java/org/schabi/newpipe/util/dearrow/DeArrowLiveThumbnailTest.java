package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for the live-stream thumbnail path. */
public class DeArrowLiveThumbnailTest {

    private static final String LIVE_ID = "hbnt8mmL0ZI";

    @Test
    public void liveUrlAsksTheServerToGenerateNowAndSendsNoTimestamp() {
        final String url = DeArrowLiveThumbnail.urlFor(LIVE_ID, DeArrowConfig.allEnabled());
        assertTrue(url, url.contains("videoID=" + LIVE_ID));
        assertTrue("live has to ask the server to render now", url.contains("generateNow=true"));
        assertFalse("a broadcast has no timestamp to seek to", url.contains("time="));
    }

    @Test
    public void aReportedDurationMeansItIsNotLive() {
        assertTrue(DeArrowLiveThumbnail.isLive(0));
        assertTrue("an unknown length is treated as live", DeArrowLiveThumbnail.isLive(-1));
        assertFalse(DeArrowLiveThumbnail.isLive(1));
        assertFalse(DeArrowLiveThumbnail.isLive(6437));
    }

    @Test
    public void ordinaryVideosGetNoLiveUrl() {
        assertNull("a normal video must take the seeded-frame path instead",
                DeArrowLiveThumbnail.urlForLiveOnly(LIVE_ID, 6437, DeArrowConfig.allEnabled()));
    }

    @Test
    public void liveVideosDoGetOne() {
        assertEquals(DeArrowLiveThumbnail.urlFor(LIVE_ID, DeArrowConfig.allEnabled()),
                DeArrowLiveThumbnail.urlForLiveOnly(LIVE_ID, 0, DeArrowConfig.allEnabled()));
    }

    @Test
    public void aSelfHostedThumbnailServerIsHonoured() {
        final DeArrowConfig config = new DeArrowConfig(true, true, true, true, true,
                "https://sponsor.example.org", "https://thumbs.example.org/get/");
        assertEquals("https://thumbs.example.org/get?videoID=" + LIVE_ID + "&generateNow=true",
                DeArrowLiveThumbnail.urlFor(LIVE_ID, config));
    }
}
