package com.kolammaster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

internal object CommunityGalleryImageCache {
    private const val CACHE_DIRECTORY = "community-gallery"
    private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    private const val MAX_DECODED_DIMENSION = 1024
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 15_000
    private const val COPY_BUFFER_SIZE = 16 * 1024

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightDownloads = ConcurrentHashMap<String, Deferred<File?>>()
    private val downloadSemaphore = Semaphore(3)
    private val activeCacheKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var hasReconciledGallery = false

    suspend fun load(context: Context, image: CommunitySubmissionImage): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (image.imageUrl.isBlank()) return@withContext null

            val file = ensureCached(context, image) ?: return@withContext null
            decodeImage(file)?.asImageBitmap()
        }

    suspend fun prefetch(
        context: Context,
        images: List<CommunitySubmissionImage>,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val uniqueImages = images.distinctBy { cacheFile(context, it).name }
        val completed = AtomicInteger()
        val results = uniqueImages.map { image ->
            async {
                val result = ensureCached(context, image)
                onProgress(completed.incrementAndGet(), uniqueImages.size)
                result != null
            }
        }.awaitAll()
        results.all { it }
    }

    private suspend fun ensureCached(
        context: Context,
        image: CommunitySubmissionImage
    ): File? = withContext(Dispatchers.IO) {
        if (image.imageUrl.isBlank()) return@withContext null

        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, CACHE_DIRECTORY)
        val target = cacheFile(directory, image)
        val key = target.name
        decodeImage(target)?.let {
            it.recycle()
            return@withContext target
        }
        target.delete()
        val download = inFlightDownloads.computeIfAbsent(key) {
            downloadScope.async(start = CoroutineStart.LAZY) {
                downloadSemaphore.withPermit {
                    downloadIfNeeded(directory, target, image.imageUrl)
                }
            }.also { deferred ->
                deferred.invokeOnCompletion {
                    inFlightDownloads.remove(key, deferred)
                }
            }
        }
        download.start()
        download.await()
    }

    suspend fun reconcile(
        context: Context,
        images: List<CommunitySubmissionImage>,
        displayedImage: CommunitySubmissionImage? = null
    ) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, CACHE_DIRECTORY)
        val currentFiles = images.mapTo(mutableSetOf()) { cacheFile(directory, it).name }
        displayedImage?.let { currentFiles += cacheFile(directory, it).name }
        activeCacheKeys.clear()
        activeCacheKeys.addAll(currentFiles)
        hasReconciledGallery = true
        if (directory.isDirectory) {
            directory.listFiles()
                ?.filter { it.isFile && it.extension == "img" && it.name !in currentFiles }
                ?.forEach(File::delete)
        }
    }

    internal fun cacheFile(context: Context, image: CommunitySubmissionImage): File =
        cacheFile(
            File(context.applicationContext.filesDir, CACHE_DIRECTORY),
            image
        )

    private fun cacheFile(directory: File, image: CommunitySubmissionImage): File {
        val identity = listOf(image.id, image.imageKey.orEmpty(), image.imageUrl)
            .joinToString(separator = "\u0000")
        return File(directory, "${identity.sha256Hex()}.img")
    }

    private fun downloadIfNeeded(directory: File, target: File, url: String): File? {
        if (!directory.exists() && !directory.mkdirs()) return null
        if (!directory.isDirectory) return null

        decodeImage(target)?.let {
            it.recycle()
            return target
        }
        target.delete()
        val temporaryFile = try {
            File.createTempFile("community-", ".tmp", directory)
        } catch (_: IOException) {
            return null
        }
        return try {
            downloadToFile(url, temporaryFile)
            if (!isValidImage(temporaryFile)) return null
            Os.rename(temporaryFile.absolutePath, target.absolutePath)
            if (hasReconciledGallery && target.name !in activeCacheKeys) target.delete()
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
            ?: throw IOException("Community image URL must use HTTPS.")
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("Community image request failed with HTTP $status.")
            }
            val expectedLength = connection.contentLengthLong
            if (expectedLength > MAX_IMAGE_BYTES) {
                throw IOException("Community image exceeds the cache size limit.")
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
                            throw IOException("Community image exceeds the cache size limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (expectedLength >= 0L && copiedBytes != expectedLength) {
                throw IOException("Community image download was incomplete.")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidImage(file: File): Boolean {
        val bitmap = decodeImage(file) ?: return false
        bitmap.recycle()
        return true
    }

    private fun decodeImage(file: File): Bitmap? {
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
        while (maxOf(width, height) / sampleSize > MAX_DECODED_DIMENSION) sampleSize *= 2
        return sampleSize
    }

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
