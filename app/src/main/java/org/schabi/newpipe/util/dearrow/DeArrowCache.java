package org.schabi.newpipe.util.dearrow;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Fetches and caches DeArrow branding, one API bucket at a time.
 *
 * <p>Lookups are keyed by video id but <em>fetched</em> by hash bucket, so a single request
 * usually answers this video and caches a hundred others for free — see
 * {@link DeArrowParser#hashPrefix(String)} for why the request is shaped that way. Requests for
 * the same bucket that arrive while one is already in flight share its result rather than
 * starting a second one, which matters because a scrolling list asks about twenty videos at
 * once.</p>
 *
 * <p>The cache is in-memory only and is deliberately not persisted: community submissions are
 * edited and removed, and a stale on-disk cache would keep showing a title the server has since
 * taken down.</p>
 */
public final class DeArrowCache {

    private static final String TAG = "DeArrowCache";

    /**
     * How many videos to remember. One bucket is ~130 videos, so this holds roughly the last
     * 40 buckets — far more than a browsing session touches, at a few hundred KB.
     */
    @VisibleForTesting
    static final int MAX_CACHED_VIDEOS = 5000;

    private static DeArrowCache instance;

    private final Map<String, DeArrowBranding> cache =
            Collections.synchronizedMap(new LinkedHashMap<String, DeArrowBranding>(
                    16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, DeArrowBranding> eldest) {
                    return size() > MAX_CACHED_VIDEOS;
                }
            });

    private final Map<String, Single<String>> inFlightBuckets = new ConcurrentHashMap<>();

    private DeArrowCache() {
    }

    public static synchronized DeArrowCache getInstance() {
        if (instance == null) {
            instance = new DeArrowCache();
        }
        return instance;
    }

    /**
     * Looks up the branding for one video.
     *
     * <p>Never fails: a disabled feature, an unreachable API, a malformed response and a video
     * DeArrow has never heard of all resolve to {@link DeArrowBranding#NONE}, whose meaning is
     * "show what YouTube gave us". Callers therefore need no error path, which is what keeps the
     * bind sites a single line.</p>
     *
     * @param videoId the video to look up
     * @param config  the user's settings, read once per lookup so a change takes effect without
     *                an app restart
     * @return a Single that emits on the IO scheduler and never errors
     */
    @NonNull
    public Single<DeArrowBranding> lookup(@NonNull final String videoId,
                                          @NonNull final DeArrowConfig config) {
        if (!config.isEnabled()) {
            return Single.just(DeArrowBranding.NONE);
        }
        final DeArrowBranding cached = cache.get(videoId);
        if (cached != null) {
            return Single.just(cached);
        }
        return fetchBucket(videoId, config)
                .map(body -> readBucket(body, videoId, config))
                .onErrorReturnItem(DeArrowBranding.NONE)
                .subscribeOn(Schedulers.io());
    }

    /**
     * Returns an already-cached result without touching the network.
     *
     * <p>Used by view binding to apply a known result synchronously, so a row that scrolls back
     * into view does not visibly flip from the clickbait title to the honest one.</p>
     *
     * @param videoId the video to look up
     * @return the cached branding, or {@link DeArrowBranding#NONE} if nothing is cached
     */
    @NonNull
    public DeArrowBranding getCached(@NonNull final String videoId) {
        final DeArrowBranding cached = cache.get(videoId);
        return cached == null ? DeArrowBranding.NONE : cached;
    }

    /** Drops every cached entry. Exposed as a settings action for debugging a wrong title. */
    public void clear() {
        cache.clear();
    }

    @NonNull
    private Single<String> fetchBucket(@NonNull final String videoId,
                                       @NonNull final DeArrowConfig config) {
        final String prefix = DeArrowParser.hashPrefix(videoId);
        // computeIfAbsent keeps concurrent callers for the same bucket on one request; cache()
        // replays the body to every subscriber, and the entry is removed once it settles so a
        // later lookup can retry after a transient failure.
        return inFlightBuckets.computeIfAbsent(prefix, p -> Single
                .fromCallable(() -> {
                    final Response response = NewPipe.getDownloader()
                            .get(DeArrowParser.buildBucketUrl(videoId, config));
                    if (response.responseCode() != 200) {
                        throw new DeArrowParseException(
                                "DeArrow API returned HTTP " + response.responseCode());
                    }
                    return response.responseBody();
                })
                .doFinally(() -> inFlightBuckets.remove(p))
                .subscribeOn(Schedulers.io())
                .cache());
    }

    /**
     * Parses a bucket body, caching every video in it and returning the one that was asked for.
     *
     * @param body    the raw bucket response
     * @param videoId the video that triggered the fetch
     * @param config  the user's settings
     * @return the branding for {@code videoId}
     */
    @NonNull
    private DeArrowBranding readBucket(@NonNull final String body,
                                       @NonNull final String videoId,
                                       @NonNull final DeArrowConfig config) {
        try {
            // Parse the body ONCE. Calling parseBucketEntry per video re-parses the whole 17 KB
            // payload for each of the ~130 videos in it; on a scrolling feed that quadratic work
            // saturated the CPU and produced an "isn't responding" dialog on a real device
            // (2026-09-23). The unit tests were green throughout.
            cache.putAll(DeArrowParser.parseBucket(body, config));
            // A video absent from its own bucket simply has no submissions; cache that too, so
            // scrolling past it repeatedly does not re-request the bucket.
            cache.putIfAbsent(videoId, DeArrowBranding.NONE);
            return getCached(videoId);
        } catch (final DeArrowParseException e) {
            // Swallowed on purpose, and only here: an unparseable response means the user sees
            // the uploader's original title and thumbnail, which is exactly the correct
            // fallback. Propagating it would break browsing over a third-party outage.
            Log.w(TAG, "Could not parse DeArrow bucket response", e);
            return DeArrowBranding.NONE;
        }
    }
}
