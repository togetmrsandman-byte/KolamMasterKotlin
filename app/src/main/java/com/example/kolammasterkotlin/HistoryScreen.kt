package com.kolammaster.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private val historyGold = KolamBronze
private val historyBody = KolamText
private val historyImageRatios = mapOf(
    "ancient-village-kolam-and-harvest life.webp" to 1f,
    "sparrows-and-ants-on-kolam.webp" to 1f,
    "evil-trapped.webp" to 1f,
    "sikku-kolam-bubble-picture.png" to 1514f / 250f,
    "pulli-kolam-bubble-picture.png" to 1514f / 250f,
    "tamil-village-1.webp" to 851f / 1400f,
    "tamil-village-2.webp" to 980f / 1605f,
    "tamil-village-3.webp" to 839f / 1400f,
    "tamil-village-4.webp" to 853f / 1400f,
    "tamil-village-5.webp" to 862f / 1400f,
    "tamil-village-6.webp" to 1020f / 1400f
)

@Composable
internal fun HistoryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HistoryHeading()
        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.history_intro),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            color = KolamText,
            fontSize = 15.sp,
            lineHeight = 23.sp
        )

        HistorySectionTitle(stringResource(R.string.history_sikku_pulli_title))
        HistoryParagraph(stringResource(R.string.history_sikku_origin_location))
        HistoryParagraph(stringResource(R.string.history_sikku_origin_story))
        HistoryImage("ancient-village-kolam-and-harvest life.webp")
        HistoryParagraph(stringResource(R.string.history_ancient_dot_grids))
        HistoryImage("sparrows-and-ants-on-kolam.webp")
        HistoryParagraph(stringResource(R.string.history_loop_belief))
        HistoryImage("evil-trapped.webp")
        HistoryParagraph(stringResource(R.string.history_sikku_description))
        HistoryImage("sikku-kolam-bubble-picture.png")
        HistoryParagraph(stringResource(R.string.history_pulli_description))
        HistoryImage("pulli-kolam-bubble-picture.png")

        HistorySectionTitle(stringResource(R.string.history_rangoli_title))
        HistoryBullet(stringResource(R.string.history_rangoli_origin_location))
        HistoryBullet(stringResource(R.string.history_rangoli_origin_story))

        HistorySectionTitle(stringResource(R.string.history_sangam_title))
        HistoryParagraph(stringResource(R.string.history_sangam_description))

        HistorySectionTitle(stringResource(R.string.history_festival_title))
        HistoryParagraph(stringResource(R.string.history_festival_description))

        HistorySectionTitle(stringResource(R.string.history_modern_science_title))
        HistoryParagraph(stringResource(R.string.history_modern_science_discovery))
        HistoryBullet(stringResource(R.string.history_modern_science_languages))
        HistoryBullet(stringResource(R.string.history_modern_science_research))

        HistorySectionTitle(stringResource(R.string.history_brahma_timing_title))
        HistoryParagraph(stringResource(R.string.history_brahma_timing_description))
        HistoryBullet(stringResource(R.string.history_brahma_cultural_belief))
        HistoryBullet(stringResource(R.string.history_brahma_social_signal))
        HistoryParagraph(stringResource(R.string.history_photo_collection))

        listOf(
            "tamil-village-1.webp",
            "tamil-village-2.webp",
            "tamil-village-3.webp",
            "tamil-village-4.webp",
            "tamil-village-5.webp",
            "tamil-village-6.webp"
        ).forEach { asset -> HistoryImage(asset) }
        HistoryImage("sikku-kolam-bubble-picture.png")
    }
}

@Composable
private fun HistoryHeading() {
    val border = rememberHistoryAsset("kolam-border.png")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HistoryTitleBorder(border, mirrored = true)
        Text(
            text = stringResource(R.string.history_title),
            modifier = Modifier.padding(horizontal = 10.dp),
            color = historyGold,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        HistoryTitleBorder(border)
    }
}

@Composable
private fun HistoryTitleBorder(border: ImageBitmap?, mirrored: Boolean = false) {
    Box(
        modifier = Modifier.size(width = 38.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        if (border != null) {
            Image(
                bitmap = border,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .requiredSize(width = 38.dp, height = 92.dp)
                    .then(
                        if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier
                    )
            )
        }
    }
}

@Composable
private fun HistorySectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 8.dp),
        color = historyGold,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Start
    )
}

@Composable
private fun HistoryParagraph(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        color = historyBody,
        fontSize = 15.sp,
        lineHeight = 23.sp
    )
}

@Composable
private fun HistoryBullet(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", color = historyGold, fontSize = 17.sp, modifier = Modifier.padding(end = 8.dp))
        Text(
            text = text,
            color = historyBody,
            fontSize = 15.sp,
            lineHeight = 23.sp
        )
    }
}

@Composable
private fun HistoryImage(assetName: String) {
    val image = rememberHistoryAsset(assetName)
    val ratio = historyImageRatios[assetName] ?: 1f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(12.dp))
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = assetName.substringBeforeLast('.').replace('-', ' '),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun rememberHistoryAsset(name: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val cached = historyImageCache.get(name)
    return produceState<ImageBitmap?>(initialValue = cached, context, name) {
        if (value == null) {
            value = try {
                withContext(Dispatchers.IO) {
                    val loadLock = historyImageLoadLocks.computeIfAbsent(name) { Any() }
                    synchronized(loadLock) {
                        historyImageCache.get(name) ?: context.assets.open(name).use { stream ->
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()?.also { image ->
                                historyImageCache.put(name, image)
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IOException) {
                null
            } catch (exception: RuntimeException) {
                null
            }
        }
    }.value
}

private val historyImageCache = object : LruCache<String, ImageBitmap>(
    (Runtime.getRuntime().maxMemory() / 6)
        .coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024)
        .toInt()
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width.toLong() * value.height * 4L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
}
private val historyImageLoadLocks = ConcurrentHashMap<String, Any>()
