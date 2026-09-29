package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.ui.theme.ImageGridToken
import ai.openonion.oochat.ui.theme.MessageBubbleToken
import ai.openonion.oochat.ui.theme.bubbleShape
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image attachment grid: a single image renders at a 4:3 aspect ratio; two or
 * more render as a 2-column grid of 1:1 tiles, matching the Figma spec.
 */
@Composable
internal fun ImageGridBubble(images: List<String>, isUser: Boolean) {
    val shape = bubbleShape(isUser)
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant

    if (images.size == 1) {
        AsyncImage(
            model = rememberImageModel(images.first(), targetShortSidePx = null),
            contentDescription = "Attached image",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                // Cap first, then fill it: a lone image should take the whole
                // width a bubble is allowed, not shrink to its intrinsic size.
                .bubbleMaxWidth(MessageBubbleToken.MaxWidthFraction, MessageBubbleToken.MaxWidth)
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(shape)
                .background(placeholderColor)
        )
    } else {
        // A grid cell is at most half the grid's width, so decoding an agent
        // image at the full 1568px cap held ~4x the pixels the cell can show.
        // Coil can't trim this for us: the data-URL path below hands it an
        // already-decoded BitmapDrawable, which it draws as-is.
        val cellShortSidePx = with(LocalDensity.current) {
            ((ImageGridToken.MaxWidth - ImageGridToken.ItemSpacing) / 2).roundToPx()
        }
        Column(
            modifier = Modifier
                .bubbleMaxWidth(MessageBubbleToken.MaxWidthFraction, ImageGridToken.MaxWidth)
                .fillMaxWidth()
                .clip(shape),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            images.chunked(2).forEach { rowImages ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    rowImages.forEach { image ->
                        AsyncImage(
                            model = rememberImageModel(image, cellShortSidePx),
                            contentDescription = "Attached image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(placeholderColor)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Coil has no built-in fetcher for the `data:` URI scheme (only agent-sent
 * images ever use it — see ProtocolParser's "agent_image" case; user-sent
 * images are always `file://`/`content://`, which Coil handles natively as
 * a plain [String] model). Decoding it ourselves into a [BitmapDrawable] —
 * a model type Coil maps directly, no fetch/decode step needed — sidesteps
 * writing a custom Fetcher for what both encodings already boil down to.
 *
 * Decoded off the composition (main) thread via [produceState] + a
 * background dispatcher, with the same bounds-then-sampled decode
 * [ai.openonion.oochat.data.local.ImageAttachmentStoreImpl] uses on
 * the send side — an agent-sent screenshot decoded full-resolution
 * synchronously during composition would block the UI thread and risks the
 * exact OOM that two-pass decode exists to avoid.
 *
 * [targetShortSidePx] is the width the image will actually be drawn into, or
 * null to only apply the absolute [MAX_AGENT_IMAGE_DIMENSION] cap. Non-data
 * models are handed to Coil untouched — it already sizes those decodes from
 * the composable's own layout constraints.
 */
@Composable
private fun rememberImageModel(image: String, targetShortSidePx: Int?): Any {
    if (!image.startsWith("data:")) return image
    val resources = LocalContext.current.resources
    val model by produceState<Any>(initialValue = image, key1 = image, key2 = targetShortSidePx) {
        value = withContext(Dispatchers.Default) {
            decodeDataUrl(image, resources, targetShortSidePx) ?: image
        }
    }
    return model
}

private fun decodeDataUrl(
    dataUrl: String,
    resources: android.content.res.Resources,
    targetShortSidePx: Int?
): BitmapDrawable? {
    return try {
        val base64 = dataUrl.substringAfter(',', "")
        if (base64.isEmpty()) return null
        val bytes = Base64.decode(base64, Base64.DEFAULT)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(
                calculateDataUrlInSampleSize(bounds.outWidth, bounds.outHeight, MAX_AGENT_IMAGE_DIMENSION),
                targetShortSidePx?.let { shortSideInSampleSize(bounds.outWidth, bounds.outHeight, it) } ?: 1
            )
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        BitmapDrawable(resources, bitmap)
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }
}

/** Largest power-of-two subsampling that still leaves the long side >= [targetDimension]. */
private fun calculateDataUrlInSampleSize(width: Int, height: Int, targetDimension: Int): Int {
    var inSampleSize = 1
    val longSide = maxOf(width, height)
    while (longSide / (inSampleSize * 2) >= targetDimension) {
        inSampleSize *= 2
    }
    return inSampleSize
}

/**
 * Halve while the *short* side still covers [targetPx]. ContentScale.Crop
 * scales the short side up to fill the cell, so that — not the long side — is
 * what sets the resolution actually needed; sampling a panorama by its long
 * side would blur the dimension that was already the tight one.
 */
private fun shortSideInSampleSize(width: Int, height: Int, targetPx: Int): Int {
    var inSampleSize = 1
    val shortSide = minOf(width, height)
    while (shortSide / (inSampleSize * 2) >= targetPx) {
        inSampleSize *= 2
    }
    return inSampleSize
}

private const val MAX_AGENT_IMAGE_DIMENSION = 1568
