package org.schabi.newpipe.util.dearrow;

import android.content.Context;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.util.PicassoHelper;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Applies DeArrow branding to an already-bound list row.
 *
 * <p>This is the class that answers the long-standing objection to DeArrow on Android — that
 * swapping titles and thumbnails after the fact cannot be done seamlessly. It can, provided three
 * rules are never broken, and they are enforced here rather than left to each call site:</p>
 *
 * <ol>
 *   <li><b>The original is bound first, synchronously, always.</b> {@link #apply} is called
 *       <em>after</em> the row has already been populated with the uploader's title and
 *       thumbnail. A DeArrow lookup can therefore never delay a bind or leave a row blank.</li>
 *   <li><b>A replacement is applied only if the row is still showing the same video.</b>
 *       RecyclerView reuses holders aggressively; the video id is stored on the view and
 *       re-checked on the main thread before anything is written, so a slow response for a
 *       scrolled-away row is discarded instead of corrupting the row that replaced it.</li>
 *   <li><b>A cached result is applied synchronously, with no asynchronous step at all.</b> After
 *       the first pass over a list, scrolling back and forth shows honest titles immediately —
 *       the visible flip only ever happens once per video, on the very first fetch.</li>
 * </ol>
 *
 * <p>A failure at any point is a no-op: the row keeps what YouTube gave it.</p>
 */
public final class DeArrowBinder {

    private DeArrowBinder() {
    }

    /**
     * Replaces the title and thumbnail of a row with DeArrow's, if the user has opted in and
     * DeArrow has anything to say about this video.
     *
     * @param infoItem     the item the row was just bound to; ignored unless it is a YouTube
     *                     stream, since DeArrow only covers YouTube
     * @param titleView    the row's title view, already showing the uploader's title
     * @param thumbnailView the row's thumbnail view, already loading the uploader's thumbnail;
     *                      may be null for rows that show no image
     */
    public static void apply(@Nullable final InfoItem infoItem,
                             @NonNull final TextView titleView,
                             @Nullable final ImageView thumbnailView) {
        applyToVideo(videoIdOf(infoItem), titleView, thumbnailView);
    }

    /**
     * The same, for rows built from a stored stream rather than an extractor item — the
     * subscription feed and the watch history, which hold a {@code StreamEntity} instead.
     *
     * @param serviceId     the service the stream came from; anything but YouTube is ignored
     * @param url           the stream URL the video id is read out of
     * @param titleView     the row's title view, already showing the stored title
     * @param thumbnailView the row's thumbnail view, or null
     */
    public static void apply(final int serviceId,
                             @Nullable final String url,
                             @NonNull final TextView titleView,
                             @Nullable final ImageView thumbnailView) {
        applyToVideo(serviceId == ServiceList.YouTube.getServiceId()
                ? DeArrowVideoId.fromUrl(url)
                : null, titleView, thumbnailView);
    }

    private static void applyToVideo(@Nullable final String videoId,
                                     @NonNull final TextView titleView,
                                     @Nullable final ImageView thumbnailView) {
        if (videoId == null) {
            clearPending(titleView);
            return;
        }
        final DeArrowConfig config = DeArrowSettings.read(titleView.getContext());
        if (!config.isEnabled()) {
            clearPending(titleView);
            return;
        }

        // Rule 2: remember which video this row is showing, so a late response can be discarded.
        titleView.setTag(R.id.dearrow_video_id, videoId);

        // Rule 3: a result we already have is applied with no asynchronous hop, so a row that
        // scrolls back into view never visibly flips a second time.
        final DeArrowBranding cached = DeArrowCache.getInstance().getCached(videoId);
        if (!cached.isEmpty()) {
            clearPending(titleView);
            write(cached, titleView, thumbnailView);
            return;
        }

        clearPending(titleView);
        final Disposable disposable = DeArrowCache.getInstance()
                .lookup(videoId, config)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(branding -> {
                    if (branding.isEmpty()) {
                        return;
                    }
                    if (!videoId.equals(titleView.getTag(R.id.dearrow_video_id))) {
                        // The holder was recycled onto a different video while we were waiting.
                        return;
                    }
                    write(branding, titleView, thumbnailView);
                }, error -> {
                    // lookup() is documented never to error; this arm exists so that a future
                    // change to it cannot crash the app from a background thread.
                });
        titleView.setTag(R.id.dearrow_disposable, disposable);
    }

    /**
     * Writes the replacement into the views.
     *
     * @param branding      what to show; null fields mean "leave this view alone"
     * @param titleView     the row's title view
     * @param thumbnailView the row's thumbnail view, or null
     */
    private static void write(@NonNull final DeArrowBranding branding,
                              @NonNull final TextView titleView,
                              @Nullable final ImageView thumbnailView) {
        if (branding.getTitle() != null) {
            titleView.setText(branding.getTitle());
        }
        if (branding.getThumbnailUrl() != null && thumbnailView != null) {
            final Context context = thumbnailView.getContext();
            // The uploader's thumbnail stays on screen as the placeholder while this loads, so a
            // slow or failed DeArrow render never shows the user an empty box.
            PicassoHelper.loadScaledDownThumbnail(context, branding.getThumbnailUrl())
                    .placeholder(thumbnailView.getDrawable())
                    .noFade()
                    .into(thumbnailView);
        }
    }

    /**
     * Reports whether some text on screen is the DeArrow title this stream is already showing.
     *
     * <p>Exists so that a caller which compares the displayed title against the original one to
     * decide "is this already drawn?" does not mistake a successful DeArrow replacement for a
     * stale view and redraw the page on every check.</p>
     *
     * @param info        the stream the view is showing
     * @param displayed   the text currently in the title view
     * @return true if {@code displayed} is the cached DeArrow title for this stream
     */
    public static boolean isShowing(@Nullable final StreamInfo info,
                                    @Nullable final String displayed) {
        if (info == null || displayed == null
                || info.getServiceId() != ServiceList.YouTube.getServiceId()) {
            return false;
        }
        final String videoId = DeArrowVideoId.fromUrl(info.getUrl());
        if (videoId == null) {
            return false;
        }
        return displayed.equals(DeArrowCache.getInstance().getCached(videoId).getTitle());
    }

    /** Cancels any lookup still running for a row that is being rebound. */
    private static void clearPending(@NonNull final TextView titleView) {
        final Object pending = titleView.getTag(R.id.dearrow_disposable);
        if (pending instanceof Disposable) {
            ((Disposable) pending).dispose();
            titleView.setTag(R.id.dearrow_disposable, null);
        }
    }

    /**
     * Extracts the YouTube video id a row is showing.
     *
     * @param infoItem the bound item
     * @return the 11-character video id, or null if this is not a YouTube stream (DeArrow has no
     *         data for Bilibili, NicoNico, SoundCloud or any of the other supported services)
     */
    @Nullable
    private static String videoIdOf(@Nullable final InfoItem infoItem) {
        if (!(infoItem instanceof StreamInfoItem)) {
            return null;
        }
        final StreamInfoItem item = (StreamInfoItem) infoItem;
        if (item.getServiceId() != ServiceList.YouTube.getServiceId()) {
            return null;
        }
        return DeArrowVideoId.fromUrl(item.getUrl());
    }
}
