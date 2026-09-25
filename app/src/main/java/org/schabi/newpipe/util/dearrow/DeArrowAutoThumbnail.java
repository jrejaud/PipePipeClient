package org.schabi.newpipe.util.dearrow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Gets a frame out of a video without downloading any of the video.
 *
 * <p>YouTube already stores three automatically-extracted frames for every upload, at roughly
 * a quarter, a half and three quarters of the way through, and serves them from the same
 * public image host as the uploader's own thumbnail:</p>
 *
 * <pre>https://i.ytimg.com/vi/&lt;videoId&gt;/hq1.jpg   (also hq2, hq3)</pre>
 *
 * <p>They are ~7 KB each, need no API key, no player request, no signature deciphering and no
 * video decoding — a single plain HTTP GET, the same cost as the thumbnail the row is already
 * loading. Crucially they are <em>real frames</em>, not crops of the uploader's artwork:
 * measured against {@code hqdefault.jpg} they differ by 0.28–0.34 normalised RMSE, and from
 * each other by a similar margin (2026-09-25, eight-video sample).</p>
 *
 * <p><b>Why this exists at all.</b> {@link DeArrowFrameRenderer} produces a frame at the exact
 * seeded timestamp, which is more faithful, but each one costs an extractor call to resolve a
 * playback URL plus range reads into the video container — seconds per thumbnail. On a list of
 * twelve results that is slower than the user scrolls, so most rows were still showing
 * clickbait by the time they were looked at, and the feature read as broken. Three fixed
 * frames fetched instantly beat one perfect frame that arrives after the user has moved on.
 * The renderer is kept as the fallback for the videos this cannot serve.</p>
 *
 * <p><b>Live broadcasts get the same treatment from a different file.</b> {@code hq1}–{@code hq3}
 * are produced when an upload is processed and 404 for a stream that is still going. A live
 * broadcast instead has a single {@code hq720_live.jpg}, 1280×720 and natively 16:9, holding
 * the current moment of the stream and refreshed as it runs — which is exactly the frame the
 * DeArrow extension asks the thumbnail server to generate. Going straight to the image host is
 * both faster and far more reliable: {@code dearrow-thumb.ajay.app} answered HTTP 204 for every
 * live broadcast tried, on two separate days (2026-09-23, 2026-09-25), so
 * {@link DeArrowLiveFrame} is kept only as a second chance.</p>
 */
public final class DeArrowAutoThumbnail {

    private static final String TAG = "DeArrowAutoThumb";

    /** The host serving YouTube's stored thumbnails. No key, no auth, no rate limit in practice. */
    private static final String THUMBNAIL_HOST = "https://i.ytimg.com/vi/";

    /** How many automatically-extracted frames YouTube stores per upload: hq1, hq2, hq3. */
    @VisibleForTesting
    static final int AUTO_FRAME_COUNT = 3;

    /**
     * The one auto-extracted frame a live broadcast has: the current moment, 1280×720, and
     * already 16:9 so it needs no letterbox removal.
     */
    @VisibleForTesting
    static final String LIVE_FRAME = "hq720_live";

    /**
     * How long a live frame stays fresh.
     *
     * <p>Unlike an upload's frame, this one legitimately changes as the broadcast moves on, so
     * it is cached only long enough that scrolling does not re-fetch it.</p>
     */
    @VisibleForTesting
    static final long LIVE_CACHE_TTL_MS = 5 * 60 * 1000L;

    private static final int MAX_CACHED_FRAMES = 120;

    /** Smaller than a real JPEG could be; guards against a truncated or error body. */
    @VisibleForTesting
    static final int MIN_IMAGE_BYTES = 512;

    /**
     * A row is near-black if its mean luminance is below this, on a 0–255 scale.
     *
     * <p>Letterbox bars are encoded flat black but pick up a little JPEG ringing at the
     * boundary, so an exact-zero test misses them.</p>
     */
    @VisibleForTesting
    static final int BLACK_LUMA = 16;

    /**
     * The most that may be cropped off each edge, as a fraction of height.
     *
     * <p>Without this, a frame that is legitimately dark at the top — a night scene, a fade —
     * would be cropped to a sliver. 16:9 inside a 4:3 box needs 12.5% off each edge, so this
     * leaves room for that and little else.</p>
     */
    @VisibleForTesting
    static final float MAX_CROP_FRACTION = 0.2f;

    private static DeArrowAutoThumbnail instance;

    private final LruCache<String, Entry> frames = new LruCache<>(MAX_CACHED_FRAMES);
    private final Map<String, Maybe<Bitmap>> inFlight = new ConcurrentHashMap<>();

    /** A frame plus when it arrived, because a live frame expires and an upload's does not. */
    private static final class Entry {
        private final Bitmap bitmap;
        private final long fetchedAt;
        private final boolean live;

        Entry(final Bitmap bitmap, final long fetchedAt, final boolean live) {
            this.bitmap = bitmap;
            this.fetchedAt = fetchedAt;
            this.live = live;
        }

        boolean isStale() {
            return live && System.currentTimeMillis() - fetchedAt > LIVE_CACHE_TTL_MS;
        }
    }

    private DeArrowAutoThumbnail() {
    }

    public static synchronized DeArrowAutoThumbnail getInstance() {
        if (instance == null) {
            instance = new DeArrowAutoThumbnail();
        }
        return instance;
    }

    /**
     * Picks which of the three stored frames to show.
     *
     * <p>Driven by the same seeded generator the DeArrow server uses to choose a timestamp
     * ({@link DeArrowRandomTime}), so the choice is arbitrary but stable: one video always
     * gets the same frame, on every device, across restarts. A row that scrolls away and
     * comes back does not change picture.</p>
     *
     * @param videoId the video
     * @return 1, 2 or 3 — the {@code N} in {@code hqN.jpg}
     */
    @VisibleForTesting
    static int frameIndexFor(@NonNull final String videoId) {
        // fractionFor is in [0, TAIL_TO_AVOID); rescaling to [0, 1) keeps all three frames
        // equally likely rather than starving hq3.
        final double scaled = DeArrowRandomTime.fractionFor(videoId)
                / DeArrowRandomTime.TAIL_TO_AVOID;
        final int index = (int) (scaled * AUTO_FRAME_COUNT) + 1;
        return Math.min(index, AUTO_FRAME_COUNT);
    }

    /**
     * @param videoId the video
     * @param live    whether this is a broadcast in progress rather than an upload
     * @return the URL of the stored frame chosen for this video
     */
    @VisibleForTesting
    @NonNull
    static String urlFor(@NonNull final String videoId, final boolean live) {
        if (live) {
            return String.format(Locale.US, "%s%s/%s.jpg", THUMBNAIL_HOST, videoId, LIVE_FRAME);
        }
        return String.format(Locale.US, "%s%s/hq%d.jpg",
                THUMBNAIL_HOST, videoId, frameIndexFor(videoId));
    }

    /**
     * A frame already fetched for this video, if there is one that is still current.
     *
     * <p>Lets a recycled row paint with no asynchronous hop, so scrolling back over a video
     * does not make it flip a second time.</p>
     *
     * @param videoId the video
     * @return the cached frame, or null
     */
    @Nullable
    public Bitmap getCached(@NonNull final String videoId) {
        final Entry entry = frames.get(videoId);
        if (entry == null) {
            return null;
        }
        if (entry.isStale()) {
            frames.remove(videoId);
            return null;
        }
        return entry.bitmap;
    }

    /**
     * Fetches the stored frame for a video.
     *
     * <p>Completes empty rather than erroring for anything that is not a usable image — a 404
     * (live broadcasts, and uploads still being processed), a truncated body, bytes that will
     * not decode, an unreachable host. Callers read that as "leave the row alone", and for a
     * 404 specifically as "try the slow renderer instead".</p>
     *
     * @param videoId the video
     * @param live    whether this is a broadcast in progress rather than an upload
     * @return a Maybe emitting at most one bitmap, on the IO scheduler
     */
    @NonNull
    public Maybe<Bitmap> fetch(@NonNull final String videoId, final boolean live) {
        final Bitmap cached = getCached(videoId);
        if (cached != null) {
            return Maybe.just(cached);
        }
        // Two rows showing the same video — which the subscription feed does produce — share
        // one request instead of racing; the entry is dropped once it settles so a later bind
        // can retry after a transient failure.
        return inFlight.computeIfAbsent(videoId, id -> Maybe
                .fromCallable(() -> fetchBlocking(id, live))
                .doFinally(() -> inFlight.remove(id))
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * @param videoId the video
     * @param live    whether this is a broadcast in progress rather than an upload
     * @return the decoded, de-letterboxed frame, or null if there is nothing usable
     */
    @Nullable
    private Bitmap fetchBlocking(@NonNull final String videoId, final boolean live) {
        try {
            final Response response = NewPipe.getDownloader().get(urlFor(videoId, live));
            if (response.responseCode() != 200) {
                // 404 here means the upload is too fresh to have been processed, or that a
                // row reported the wrong stream type — the caller falls back accordingly.
                return null;
            }
            final byte[] body = response.rawResponseBody();
            if (body == null || body.length < MIN_IMAGE_BYTES) {
                return null;
            }
            final Bitmap decoded = BitmapFactory.decodeByteArray(body, 0, body.length);
            if (decoded == null) {
                return null;
            }
            // The live frame is already 16:9; only the 4:3-boxed upload frames need cropping.
            final Bitmap frame = live ? decoded : stripLetterbox(decoded);
            frames.put(videoId, new Entry(frame, System.currentTimeMillis(), live));
            return frame;
        } catch (final Exception | OutOfMemoryError e) {
            // Swallowed on purpose: no frame means the uploader's thumbnail stays, which is
            // the correct fallback. A cosmetic feature must never break browsing.
            Log.d(TAG, "no stored frame for " + videoId, e);
            return null;
        }
    }

    /**
     * Removes the black bars YouTube pads these frames with.
     *
     * <p>They are served in a 4:3 box — 480×360 — so a widescreen video arrives with a black
     * band above and below it. Pasted into a 16:9 list row unchanged, the picture is squashed
     * into the middle third and looks obviously wrong next to the untouched rows around it.
     * The bars are detected rather than assumed, because a genuinely 4:3 upload has none and
     * cropping it blind would cut the top and bottom off the picture.</p>
     *
     * @param source the decoded frame
     * @return the frame with its bars removed, or {@code source} itself if it has none
     */
    @VisibleForTesting
    @NonNull
    static Bitmap stripLetterbox(@NonNull final Bitmap source) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        final int limit = (int) (height * MAX_CROP_FRACTION);

        int top = 0;
        while (top < limit && isRowBlack(source, top, width)) {
            top++;
        }
        int bottom = height - 1;
        while (height - 1 - bottom < limit && isRowBlack(source, bottom, width)) {
            bottom--;
        }
        final int cropped = bottom - top + 1;
        if (top == 0 && cropped == height) {
            return source;
        }
        if (cropped < height / 2) {
            // Something is wrong with the detection — a nearly-all-black frame, most likely.
            // Showing it whole is worse than showing a sliver of it.
            return source;
        }
        return Bitmap.createBitmap(source, 0, top, width, cropped);
    }

    /**
     * @param bitmap the frame
     * @param y      the row to test
     * @param width  the frame's width
     * @return whether that row is dark enough to be a letterbox bar
     */
    private static boolean isRowBlack(@NonNull final Bitmap bitmap, final int y, final int width) {
        // Every eighth pixel is plenty to tell a flat black bar from picture, and keeps this
        // to a few hundred reads per frame rather than a few hundred thousand.
        final int step = Math.max(1, width / 60);
        long total = 0;
        int samples = 0;
        for (int x = 0; x < width; x += step) {
            final int pixel = bitmap.getPixel(x, y);
            final int r = (pixel >> 16) & 0xFF;
            final int g = (pixel >> 8) & 0xFF;
            final int b = pixel & 0xFF;
            // Rec. 601 luma, integer-scaled to avoid a float per pixel.
            total += (299L * r + 587L * g + 114L * b) / 1000L;
            samples++;
        }
        return samples > 0 && total / samples < BLACK_LUMA;
    }
}
