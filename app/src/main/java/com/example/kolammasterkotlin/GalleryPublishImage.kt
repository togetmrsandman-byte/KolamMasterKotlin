package com.kolammaster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

internal data class GalleryPublishPhoto(
    val webpBytes: ByteArray,
    val preview: ImageBitmap,
    val isLossless: Boolean,
    val width: Int,
    val height: Int
)

internal const val MAX_GALLERY_PUBLISH_IMAGE_BYTES = 10 * 1024 * 1024

internal data class GalleryPublishEncoding(
    val width: Int,
    val height: Int,
    val isLossless: Boolean,
    val byteSize: Int
)

internal interface GalleryPublishImageFrame {
    val width: Int
    val height: Int
    fun resize(width: Int, height: Int): GalleryPublishImageFrame
    fun recycle()
}

private class BitmapGalleryPublishImageFrame(val bitmap: Bitmap) : GalleryPublishImageFrame {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    override fun resize(width: Int, height: Int): GalleryPublishImageFrame =
        BitmapGalleryPublishImageFrame(Bitmap.createScaledBitmap(bitmap, width, height, true))

    override fun recycle() = bitmap.recycle()
}

internal fun interface GalleryPublishWebpEncoder {
    fun encode(
        frame: GalleryPublishImageFrame,
        destination: File,
        lossless: Boolean,
        quality: Int
    ): Boolean
}

internal object AndroidGalleryPublishWebpEncoder : GalleryPublishWebpEncoder {
    override fun encode(
        frame: GalleryPublishImageFrame,
        destination: File,
        lossless: Boolean,
        quality: Int
    ): Boolean = FileOutputStream(destination).use { output ->
        val bitmap = (frame as? BitmapGalleryPublishImageFrame)?.bitmap
            ?: throw IllegalArgumentException("Android encoder requires a bitmap image frame.")
        val format = if (lossless) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.WEBP_LOSSY
        }
        bitmap.compress(format, quality, output)
    }
}

internal object GalleryPublishWebpOptimizer {
    fun optimize(
        source: GalleryPublishImageFrame,
        destination: File,
        encoder: GalleryPublishWebpEncoder = AndroidGalleryPublishWebpEncoder,
        maxBytes: Int = MAX_GALLERY_PUBLISH_IMAGE_BYTES
    ): GalleryPublishEncoding {
        require(maxBytes > 0)
        var working = source
        var ownsWorking = false
        try {
            while (true) {
                if (encoder.encode(working, destination, lossless = true, quality = 100)) {
                    val size = destination.length()
                    if (size in 1 until maxBytes.toLong() &&
                        destination.readBytes().isLosslessWebpPayload()
                    ) {
                        return GalleryPublishEncoding(
                            working.width,
                            working.height,
                            isLossless = true,
                            byteSize = size.toInt()
                        )
                    }
                }

                for (quality in LOSSY_QUALITIES) {
                    if (!encoder.encode(working, destination, lossless = false, quality = quality)) {
                        continue
                    }
                    val size = destination.length()
                    if (size in 1 until maxBytes.toLong() &&
                        destination.readBytes().isValidWebpPayload()
                    ) {
                        return GalleryPublishEncoding(
                            working.width,
                            working.height,
                            isLossless = false,
                            byteSize = size.toInt()
                        )
                    }
                }

                if (working.width <= MIN_OPTIMIZED_DIMENSION &&
                    working.height <= MIN_OPTIMIZED_DIMENSION
                ) {
                    throw IOException(
                        "This image could not be reduced below 10 MiB. Please choose a smaller image."
                    )
                }

                val next = working.resize(
                    (working.width * DIMENSION_SCALE).toInt().coerceAtLeast(1),
                    (working.height * DIMENSION_SCALE).toInt().coerceAtLeast(1),
                )
                if (ownsWorking) working.recycle()
                working = next
                ownsWorking = true
            }
        } finally {
            if (ownsWorking) working.recycle()
        }
    }

    private val LOSSY_QUALITIES = intArrayOf(96, 92, 88, 84, 80, 76, 72, 68, 64, 60, 56, 52)
    private const val DIMENSION_SCALE = 0.88f
    private const val MIN_OPTIMIZED_DIMENSION = 128
}

internal suspend fun Context.prepareGalleryPublishPhoto(uri: Uri): GalleryPublishPhoto =
    withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        var sourceFormat = "unknown"
        var sourceWidth = 0
        var sourceHeight = 0
        var sourceSize = 0L
        var convertedWidth = 0
        var convertedHeight = 0
        var convertedSize = 0L
        var convertedLossless = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw IOException("Lossless WebP conversion requires Android 11 or later.")
        }
        val directory = File(cacheDir, "publish-images")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not prepare the selected photo.")
        }
        val sourceFile = File.createTempFile("kolam-source-", ".image", directory)
        var webpFile: File? = null
        try {
            val convertedFile = File.createTempFile("kolam-optimized-", ".webp", directory)
            webpFile = convertedFile
            val mimeType = contentResolver.getType(uri)
            sourceFormat = mimeType ?: "unknown"
            if (mimeType?.startsWith("image/", ignoreCase = true) != true) {
                throw IOException("Choose a supported photo.")
            }
            val source = contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not open the selected photo. Please choose it again.")
            source.use { input ->
                FileOutputStream(sourceFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var copiedBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        if (copiedBytes > MAX_SOURCE_FILE_SIZE_BYTES) {
                            throw IOException("The selected photo is too large to process.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            sourceSize = sourceFile.length()

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(sourceFile).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("Choose a valid image file.")
            }
            sourceWidth = bounds.outWidth
            sourceHeight = bounds.outHeight

            val orientation = ExifInterface(sourceFile)
                .getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sourceSampleSize(bounds.outWidth, bounds.outHeight)
            }
            val decoded = FileInputStream(sourceFile).use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: throw IOException("Choose a readable image file.")
            var oriented: Bitmap? = null
            try {
                val outputBitmap = applyExifOrientation(decoded, orientation)
                oriented = outputBitmap
                val encoding = GalleryPublishWebpOptimizer.optimize(
                    BitmapGalleryPublishImageFrame(outputBitmap),
                    convertedFile
                )
                convertedWidth = encoding.width
                convertedHeight = encoding.height
                convertedSize = encoding.byteSize.toLong()
                convertedLossless = encoding.isLossless
                val encoded = convertedFile.readBytes()
                if (encoded.size >= MAX_GALLERY_PUBLISH_IMAGE_BYTES ||
                    !encoded.isValidWebpPayload() ||
                    (encoding.isLossless && !encoded.isLosslessWebpPayload()) ||
                    (!encoding.isLossless && encoded.isLosslessWebpPayload())
                ) {
                    throw IOException("The optimized photo did not pass WebP validation.")
                }
                val previewWidth = encoding.width
                val previewHeight = encoding.height
                if (oriented !== decoded) oriented.recycle()
                oriented = null
                decoded.recycle()
                val previewOptions = BitmapFactory.Options().apply {
                    inSampleSize = imageSampleSize(
                        previewWidth,
                        previewHeight,
                        PREVIEW_DECODE_MAX_DIMENSION
                    )
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val convertedPreview = FileInputStream(convertedFile).use {
                    BitmapFactory.decodeStream(it, null, previewOptions)
                } ?: throw IOException("A preview could not be decoded from the converted WebP.")
                val previewBitmap = try {
                    val previewScale = minOf(
                        PREVIEW_MAX_DIMENSION.toFloat() / convertedPreview.width,
                        PREVIEW_MAX_DIMENSION.toFloat() / convertedPreview.height,
                        1f
                    )
                    Bitmap.createScaledBitmap(
                        convertedPreview,
                        (convertedPreview.width * previewScale).toInt().coerceAtLeast(1),
                        (convertedPreview.height * previewScale).toInt().coerceAtLeast(1),
                        true
                    ).copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IOException("A preview could not be created.")
                } finally {
                    convertedPreview.recycle()
                }
                GalleryPublishPhoto(
                    encoded,
                    previewBitmap.asImageBitmap(),
                    encoding.isLossless,
                    encoding.width,
                    encoding.height
                )
            } catch (exception: OutOfMemoryError) {
                throw IOException("This photo is too large to convert on this device.", exception)
            } finally {
                if (oriented != null && oriented !== decoded) oriented.recycle()
                decoded.recycle()
            }
        } catch (exception: OutOfMemoryError) {
            throw IOException("This photo is too large to convert on this device.", exception)
        } finally {
            sourceFile.delete()
            webpFile?.delete()
            if (BuildConfig.DEBUG) {
                Log.d(
                    "GalleryPublishImage",
                    "sourceFormat=$sourceFormat sourceDimensions=${sourceWidth}x$sourceHeight " +
                        "sourceBytes=$sourceSize convertedFormat=image/webp " +
                        "convertedDimensions=${convertedWidth}x$convertedHeight " +
                        "convertedBytes=$convertedSize lossless=$convertedLossless " +
                        "durationMs=${SystemClock.elapsedRealtime() - startedAt}"
                )
            }
        }
    }

    private fun imageSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun sourceSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (
            width.toLong() / sampleSize * (height.toLong() / sampleSize) >
            MAX_SOURCE_PIXEL_COUNT
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private const val MAX_SOURCE_FILE_SIZE_BYTES = 250 * 1024 * 1024
private const val MAX_SOURCE_PIXEL_COUNT = 16_000_000L
private const val COPY_BUFFER_SIZE = 64 * 1024
private const val PREVIEW_MAX_DIMENSION = 512
private const val PREVIEW_DECODE_MAX_DIMENSION = 1024
