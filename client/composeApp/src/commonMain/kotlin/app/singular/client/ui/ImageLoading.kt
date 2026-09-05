package app.singular.client.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade

/**
 * The shared image loader.
 *
 * Built once and reused — a per-call loader would rebuild its memory cache each time and
 * re-download images the app already had.
 *
 * Ktor is the fetcher because every platform here already ships a Ktor engine for the GraphQL
 * client; pulling in a second HTTP stack purely to fetch pictures would be waste.
 */
fun buildImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory()) }
        .memoryCache {
            MemoryCache.Builder()
                // A share of available memory rather than a fixed byte count: the same number
                // that is comfortable on a desktop would be most of a cheap phone's heap.
                .maxSizePercent(context, 0.20)
                .build()
        }
        .crossfade(true)
        .build()

/**
 * Draws a remote image.
 *
 * Attachment URLs are **presigned and short-lived**, so they must never be used as a cache key —
 * the same picture gets a different signature every time the message is fetched, which would
 * miss the cache on every render and re-download constantly. [stableKey] is the attachment's
 * snowflake, which never changes.
 */
/**
 * Draws bytes the user just picked, before anything has been uploaded.
 *
 * Decoded directly rather than handed to Coil. Coil's job is fetching and caching things that
 * live somewhere else; a byte array already in memory needs neither, and routing it through a
 * loader would mean inventing a cache key for something that exists for the length of one
 * editing session.
 *
 * The decode is remembered on the array itself, so scrubbing a size slider re-lays-out the
 * preview without re-decoding a photo on every frame.
 *
 * Returns nothing drawable if the bytes aren't a supported image — a file that arrived with a
 * misleading extension should leave an empty frame, not take the editor down.
 */
@Composable
fun LocalImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap = remember(bytes) { runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
        ?: return

    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
fun RemoteImage(
    url: String,
    stableKey: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    onState: (AsyncImagePainter.State) -> Unit = {},
) {
    AsyncImage(
        model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current)
            .data(url)
            .memoryCacheKey(stableKey)
            .diskCacheKey(stableKey)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onState = onState,
    )
}
