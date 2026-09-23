package org.schabi.newpipe.util.dearrow;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * What DeArrow says a video should be shown as: a replacement title, a replacement thumbnail URL,
 * or neither.
 *
 * <p>Both fields are independently nullable, because the two settings are independent and because
 * a video very often has a community title but no community thumbnail. A null field means
 * <em>keep what YouTube gave us</em> — it never means "blank".</p>
 */
public final class DeArrowBranding {

    /** The result for a video DeArrow knows nothing about. Never null, so callers can skip a
     * null check and just read the two fields. */
    public static final DeArrowBranding NONE = new DeArrowBranding(null, null);

    @Nullable
    private final String title;
    @Nullable
    private final String thumbnailUrl;

    public DeArrowBranding(@Nullable final String title, @Nullable final String thumbnailUrl) {
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
    }

    /** @return the replacement title, or null to keep the uploader's title. */
    @Nullable
    public String getTitle() {
        return title;
    }

    /** @return the replacement thumbnail URL, or null to keep the uploader's thumbnail. */
    @Nullable
    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    /** @return true if applying this would change nothing on screen. */
    public boolean isEmpty() {
        return title == null && thumbnailUrl == null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeArrowBranding)) {
            return false;
        }
        final DeArrowBranding other = (DeArrowBranding) o;
        return Objects.equals(title, other.title)
                && Objects.equals(thumbnailUrl, other.thumbnailUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, thumbnailUrl);
    }

    @Override
    public String toString() {
        return "DeArrowBranding{title=" + title + ", thumbnailUrl=" + thumbnailUrl + "}";
    }
}
