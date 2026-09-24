package org.schabi.newpipe.util.dearrow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for the settings value object. */
public class DeArrowConfigTest {

    @Test
    public void theShippedDefaultIsOff() {
        assertFalse("DeArrow must make no requests until the user opts in",
                DeArrowConfig.disabled().isEnabled());
    }

    /**
     * On by default, matching the browser extension: "if there are no submissions, it will
     * ... set a screenshot from a random timestamp as the thumbnail". Off, the feature does
     * nothing at all for the overwhelming majority of videos, which reads as broken.
     */
    @Test
    public void randomFrameFallbackIsOnByDefault() {
        assertTrue("most videos have no submission; without this the feature is invisible",
                DeArrowConfig.disabled().shouldUseRandomFrameFallback());
    }

    @Test
    public void trailingSlashesAreStrippedSoUrlsAreNotDoubled() {
        final DeArrowConfig config = new DeArrowConfig(true, true, true, false, true,
                "https://example.org/", "https://thumbs.example.org/get/");
        assertEquals("https://example.org", config.getApiUrl());
        assertEquals("https://thumbs.example.org/get", config.getThumbnailApiUrl());
        assertEquals("https://example.org/api/branding/5f6b",
                DeArrowParser.buildBucketUrl("dQw4w9WgXcQ", config));
    }

    @Test
    public void allEnabledTurnsEverythingOn() {
        final DeArrowConfig config = DeArrowConfig.allEnabled();
        assertTrue(config.isEnabled());
        assertTrue(config.shouldReplaceTitles());
        assertTrue(config.shouldReplaceThumbnails());
        assertTrue(config.shouldAutoFormatTitles());
    }
}
