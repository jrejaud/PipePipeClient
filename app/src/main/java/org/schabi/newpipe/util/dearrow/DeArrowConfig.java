package org.schabi.newpipe.util.dearrow;

import androidx.annotation.NonNull;

/**
 * The user's DeArrow preferences, as a plain value object.
 *
 * <p>This exists so that {@link DeArrowParser} can stay free of Android imports and therefore be
 * covered by ordinary JVM unit tests. {@link DeArrowSettings} is the adapter that builds one of
 * these from {@code SharedPreferences}.</p>
 */
public final class DeArrowConfig {

    /** The official DeArrow branding API, shared with SponsorBlock. */
    public static final String DEFAULT_API_URL = "https://sponsor.ajay.app";

    /** The official DeArrow thumbnail renderer. Separate host from the branding API. */
    public static final String DEFAULT_THUMBNAIL_API_URL =
            "https://dearrow-thumb.ajay.app/api/v1/getThumbnail";

    private final boolean enabled;
    private final boolean replaceTitles;
    private final boolean replaceThumbnails;
    private final boolean useRandomFrameFallback;
    private final boolean autoFormatTitles;
    @NonNull
    private final String apiUrl;
    @NonNull
    private final String thumbnailApiUrl;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeArrowConfig(final boolean enabled,
                         final boolean replaceTitles,
                         final boolean replaceThumbnails,
                         final boolean useRandomFrameFallback,
                         final boolean autoFormatTitles,
                         @NonNull final String apiUrl,
                         @NonNull final String thumbnailApiUrl) {
        this.enabled = enabled;
        this.replaceTitles = replaceTitles;
        this.replaceThumbnails = replaceThumbnails;
        this.useRandomFrameFallback = useRandomFrameFallback;
        this.autoFormatTitles = autoFormatTitles;
        this.apiUrl = stripTrailingSlash(apiUrl);
        this.thumbnailApiUrl = stripTrailingSlash(thumbnailApiUrl);
    }

    /** A config with everything on and the official endpoints — the shape most tests want. */
    public static DeArrowConfig allEnabled() {
        return new DeArrowConfig(true, true, true, true, true,
                DEFAULT_API_URL, DEFAULT_THUMBNAIL_API_URL);
    }

    /** The shipped default: DeArrow does nothing until the user opts in. */
    public static DeArrowConfig disabled() {
        return new DeArrowConfig(false, true, true, false, true,
                DEFAULT_API_URL, DEFAULT_THUMBNAIL_API_URL);
    }

    private static String stripTrailingSlash(@NonNull final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** @return false when no DeArrow request should be made at all. */
    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldReplaceTitles() {
        return replaceTitles;
    }

    public boolean shouldReplaceThumbnails() {
        return replaceThumbnails;
    }

    /**
     * @return true if a thumbnail may be rendered at the API's suggested {@code randomTime} when
     *         no community frame has been submitted. Off by default, because it costs a
     *         thumbnail-server render for every video rather than only the curated ones.
     */
    public boolean shouldUseRandomFrameFallback() {
        return useRandomFrameFallback;
    }

    public boolean shouldAutoFormatTitles() {
        return autoFormatTitles;
    }

    /** @return branding API base URL, never with a trailing slash. */
    @NonNull
    public String getApiUrl() {
        return apiUrl;
    }

    /** @return full thumbnail endpoint URL, never with a trailing slash. */
    @NonNull
    public String getThumbnailApiUrl() {
        return thumbnailApiUrl;
    }
}
