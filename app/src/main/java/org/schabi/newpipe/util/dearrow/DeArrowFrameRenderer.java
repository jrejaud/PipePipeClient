package org.schabi.newpipe.util.dearrow;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Pulls a single frame out of a video, for videos nobody has submitted a thumbnail for.
 *
 * <p>This is the Android equivalent of what the DeArrow extension does in
 * {@code thumbnailRenderer.ts}: it creates a {@code <video>} on the playback URL, seeks to a
 * timestamp and draws that to a canvas. Here that is
 * {@link MediaMetadataRetriever#getFrameAtTime} against the same kind of URL.</p>
 *
 * <p><b>Why this cannot be left to the server.</b> The obvious approach — ask
 * {@code dearrow-thumb.ajay.app} to render it with {@code generateNow=true} — does not work.
 * That endpoint returned HTTP 204 on every attempt across several videos (2026-09-23): it
 * serves frames it already holds and will not generate new ones. The extension agrees,
 * treating any non-200 from the cache as "no thumbnail" and rendering locally. So a client
 * that wants a frame for an unsubmitted video has to produce it itself.</p>
 *
 * <p><b>This is the expensive path and it is treated as one.</b> Each render costs an
 * extractor call to resolve a stream URL plus range requests into the video. Live streams
 * and unknown durations are skipped, at most {@link #MAX_CONCURRENT_RENDERS} run at a time,
 * results are cached by video id, and a failure is always a silent no-op that leaves the
 * uploader's thumbnail alone.</p>
 */
public final class DeArrowFrameRenderer {

    private static final String TAG = "DeArrowFrameRenderer";

    /**
     * Renders in flight at once. Deliberately small: a screen of results can ask for twenty
     * at once, and each one opens a video stream. Two keeps memory and bandwidth bounded
     * while the rest wait their turn.
     */
    @VisibleForTesting
    static final int MAX_CONCURRENT_RENDERS = 2;

    /**
     * How many rendered frames to keep. Each is a scaled-down bitmap, so this is a few MB
     * rather than a few hundred.
     */
    private static final int MAX_CACHED_FRAMES = 60;

    /** Frames are rendered small: they are shown in a list row, never full screen. */
    private static final int TARGET_WIDTH = 480;
    private static final int TARGET_HEIGHT = 270;

    private static DeArrowFrameRenderer instance;

    private final LruCache<String, Bitmap> frames = new LruCache<>(MAX_CACHED_FRAMES);
    private final Map<String, Maybe<Bitmap>> inFlight = new ConcurrentHashMap<>();
    private final Semaphore renderSlots = new Semaphore(MAX_CONCURRENT_RENDERS, true);

    private DeArrowFrameRenderer() {
    }

    public static synchronized DeArrowFrameRenderer getInstance() {
        if (instance == null) {
            instance = new DeArrowFrameRenderer();
        }
        return instance;
    }

    /**
     * An already-rendered frame, if there is one.
     *
     * <p>Lets a recycled row show its frame with no asynchronous step, so scrolling back
     * over a video does not visibly flip a second time.</p>
     *
     * @param videoId the video
     * @return the cached frame, or null
     */
    @Nullable
    public Bitmap getCached(@NonNull final String videoId) {
        return frames.get(videoId);
    }

    /**
     * Renders the frame DeArrow would pick for this video.
     *
     * <p>Never errors: everything that can go wrong — a live stream, an unresolvable URL, a
     * codec that will not seek — completes empty, which callers read as "keep what YouTube
     * gave us".</p>
     *
     * @param serviceId the service the stream belongs to
     * @param url       the stream page URL
     * @param videoId   the video id, which seeds the timestamp
     * @param duration  the video's length in seconds; 0 or less means do nothing
     * @return a Maybe that emits at most one bitmap, on the IO scheduler
     */
    @NonNull
    public Maybe<Bitmap> render(final int serviceId,
                                @NonNull final String url,
                                @NonNull final String videoId,
                                final long duration) {
        final Bitmap cached = frames.get(videoId);
        if (cached != null) {
            return Maybe.just(cached);
        }
        final double seconds = DeArrowRandomTime.secondsFor(videoId, duration);
        if (seconds < 0) {
            // A live stream, or an item with no known length. Nothing to seek to.
            return Maybe.empty();
        }
        return inFlight.computeIfAbsent(videoId, id -> ExtractorHelper
                .getStreamInfo(serviceId, url, false)
                .flatMapMaybe(info -> Maybe.fromCallable(() -> renderBlocking(info, id, seconds)))
                .doFinally(() -> inFlight.remove(id))
                .doOnError(e -> Log.d(TAG, "could not resolve a stream for " + id, e))
                .onErrorComplete()
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * Does the actual frame grab. Blocking, and expects to be on an IO thread.
     *
     * @param info    the resolved stream, for its URLs and type
     * @param videoId the video, used as the cache key
     * @param seconds where in the video to grab from
     * @return the frame, or null if this video cannot give one
     */
    @Nullable
    private Bitmap renderBlocking(@NonNull final StreamInfo info,
                                  @NonNull final String videoId,
                                  final double seconds) {
        if (info.getStreamType() == StreamType.LIVE_STREAM
                || info.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
            // A live stream has no fixed length, so there is no frame at a timestamp.
            return null;
        }
        // Both lists, because they hold different things: getVideoStreams() is the
        // progressive (muxed) formats, which YouTube barely serves any more, and
        // getVideoOnlyStreams() is the adaptive ones, which is where everything actually
        // is. Reading only the first left nothing to render from — the feature looked
        // switched off (2026-09-24).
        final List<VideoStream> candidates = new ArrayList<>();
        if (info.getVideoStreams() != null) {
            candidates.addAll(info.getVideoStreams());
        }
        if (info.getVideoOnlyStreams() != null) {
            candidates.addAll(info.getVideoOnlyStreams());
        }
        final String streamUrl = smallestVideoUrl(candidates);
        if (streamUrl == null) {
            Log.d(TAG, "no usable video stream for " + videoId);
            return null;
        }

        MediaMetadataRetriever retriever = null;
        boolean acquired = false;
        try {
            // Queue rather than drop. Returning immediately when busy meant a screen of
            // results rendered at most two frames and every other row silently kept its
            // clickbait image. This is already an IO thread, so waiting here is free.
            renderSlots.acquire();
            acquired = true;

            retriever = new MediaMetadataRetriever();
            // YouTube refuses a request with no User-Agent, and MediaMetadataRetriever's
            // native HTTP stack sends none by default. Reuse the app's own so the stream
            // server sees the same client it would during playback.
            final Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", DownloaderImpl.USER_AGENT);
            retriever.setDataSource(streamUrl, headers);

            final Bitmap frame = retriever.getFrameAtTime((long) (seconds * 1_000_000L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                Log.d(TAG, "no frame at " + seconds + "s for " + videoId);
                return null;
            }
            final Bitmap scaled =
                    Bitmap.createScaledBitmap(frame, TARGET_WIDTH, TARGET_HEIGHT, true);
            if (scaled != frame) {
                frame.recycle();
            }
            frames.put(videoId, scaled);
            Log.d(TAG, "rendered a frame for " + videoId + " at " + seconds + "s");
            return scaled;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final Exception | OutOfMemoryError e) {
            // Swallowed on purpose: a frame we could not grab means the user keeps the
            // uploader's thumbnail, which is the correct fallback. Anything thrown here —
            // an unsupported codec, a dead URL, a device with no memory to spare — must not
            // break browsing over a cosmetic feature.
            Log.d(TAG, "could not render a frame for " + videoId + " from " + streamUrl, e);
            return null;
        } finally {
            if (acquired) {
                renderSlots.release();
            }
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (final Exception ignored) {
                    // release() throwing tells us nothing we can act on.
                }
            }
        }
    }

    /**
     * Picks the cheapest usable video stream.
     *
     * <p>The smallest one is wanted, not the best: the output is a list thumbnail a few
     * hundred pixels wide, and a 4K stream costs far more to range-request for no visible
     * gain.</p>
     *
     * <p><b>Video-only (adaptive) streams are explicitly included.</b> YouTube serves almost
     * nothing but those now, and a frame grab needs no audio track — excluding them left
     * nothing to render from at all, which showed up as the feature silently doing
     * nothing.</p>
     *
     * @param streams the resolved video streams
     * @return a URL, or null if none is usable
     */
    @Nullable
    @VisibleForTesting
    static String smallestVideoUrl(@Nullable final List<VideoStream> streams) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        VideoStream best = null;
        int bestHeight = Integer.MAX_VALUE;
        for (final VideoStream stream : streams) {
            if (stream == null || stream.getUrl() == null) {
                continue;
            }
            final int height = heightOf(stream.getResolution());
            if (height > 0 && height < bestHeight) {
                bestHeight = height;
                best = stream;
            } else if (best == null) {
                best = stream;
            }
        }
        return best == null ? null : best.getUrl();
    }

    /**
     * @param resolution a label like {@code "360p"} or {@code "1080p60"}
     * @return the vertical pixel count, or -1 if it cannot be read
     */
    @VisibleForTesting
    static int heightOf(@Nullable final String resolution) {
        if (resolution == null) {
            return -1;
        }
        final StringBuilder digits = new StringBuilder();
        for (int i = 0; i < resolution.length(); i++) {
            final char c = resolution.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (final NumberFormatException e) {
            return -1;
        }
    }
}
