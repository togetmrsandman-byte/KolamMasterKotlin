package com.kolammaster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

internal object LessonThumbnailCache {
    private const val CACHE_DIRECTORY = "lesson-thumbnails"
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MAX_DECODED_DIMENSION = 512
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 15_000
    private const val COPY_BUFFER_SIZE = 16 * 1024

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightDownloads = ConcurrentHashMap<String, Deferred<File?>>()
    private val downloadSemaphore = Semaphore(3)

    suspend fun load(
        context: Context,
        url: String,
        onDownloadStarted: () -> Unit
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null

        val file = ensureCached(context, url, onDownloadStarted) ?: return@withContext null
        decodeThumbnail(file)?.asImageBitmap()
    }

    suspend fun prefetch(
        context: Context,
        urls: List<String>,
        onProgress: suspend (completed: Int, total: Int) -> Unit
    ): Boolean = coroutineScope {
        val uniqueUrls = urls.filter(String::isNotBlank).distinct()
        val completed = AtomicInteger()
        val results = uniqueUrls.map { url ->
            async {
                ensureCached(context, url) {}.also {
                    onProgress(completed.incrementAndGet(), uniqueUrls.size)
                }
            }
        }.awaitAll()
        results.all { it != null }
    }

    private suspend fun ensureCached(
        context: Context,
        url: String,
        onDownloadStarted: () -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val directory = File(context.applicationContext.filesDir, CACHE_DIRECTORY)
        val target = File(directory, "${url.sha256Hex()}.img")
        decodeThumbnail(target)?.let {
            it.recycle()
            return@withContext target
        }

        onDownloadStarted()
        val cacheKey = target.name
        val download = inFlightDownloads.computeIfAbsent(cacheKey) {
            downloadScope.async(start = CoroutineStart.LAZY) {
                downloadSemaphore.withPermit {
                    downloadIfNeeded(directory, target, url)
                }
            }.also { deferred ->
                deferred.invokeOnCompletion {
                    inFlightDownloads.remove(cacheKey, deferred)
                }
            }
        }
        download.start()
        download.await()
    }

    private fun downloadIfNeeded(
        cacheDirectory: File,
        target: File,
        url: String
    ): File? {
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) return null
        if (!cacheDirectory.isDirectory) return null

        decodeThumbnail(target)?.let {
            it.recycle()
            return target
        }
        val temporaryFile = try {
            File.createTempFile("thumbnail-", ".tmp", cacheDirectory)
        } catch (_: IOException) {
            return null
        }

        return try {
            downloadToFile(url, temporaryFile)
            if (!isValidThumbnail(temporaryFile)) return null
            Os.rename(temporaryFile.absolutePath, target.absolutePath)
            target
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            temporaryFile.delete()
        }
    }

    private fun downloadToFile(url: String, destination: File) {
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IOException("Thumbnail URL must use HTTPS.")
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Thumbnail request failed with HTTP $status.")
            }
            val expectedLength = connection.contentLengthLong
            if (expectedLength > MAX_IMAGE_BYTES) {
                throw IOException("Thumbnail exceeds the cache size limit.")
            }
            var copiedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copiedBytes += count
                        if (copiedBytes > MAX_IMAGE_BYTES) {
                            throw IOException("Thumbnail exceeds the cache size limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (expectedLength >= 0L && copiedBytes != expectedLength) {
                throw IOException("Thumbnail download was incomplete.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidThumbnail(file: File): Boolean {
        val bitmap = decodeThumbnail(file) ?: return false
        bitmap.recycle()
        return true
    }

    private fun decodeThumbnail(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_IMAGE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width, height) / sampleSize > MAX_DECODED_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
