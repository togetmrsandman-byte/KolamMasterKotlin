package com.kolammaster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.security.Principal
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class LessonThumbnailCacheInstrumentedTest {
    private lateinit var context: Context
    private val responseUrls = mutableListOf<String>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        responseUrls.forEach { url ->
            cacheFile(url).delete()
        }
        FakeThumbnailResponses.clear()
    }

    @Test
    fun firstRequestDownloadsAndSavesValidImage() = runBlocking {
        val url = newUrl()
        val response = FakeThumbnailResponses.respond(url, statusCode = 200, body = validPng())

        val image = LessonThumbnailCache.load(context, url) {}

        assertNotNull(image)
        assertEquals(1, response.requestCount.get())
        assertTrue(cacheFile(url).isFile)
        assertTrue(cacheFile(url).length() > 0L)
        assertTrue(isDecodable(cacheFile(url)))
    }

    @Test
    fun secondRequestUsesCachedImageWithoutAnotherNetworkRequest() = runBlocking {
        val url = newUrl()
        val response = FakeThumbnailResponses.respond(url, statusCode = 200, body = validPng())

        assertNotNull(LessonThumbnailCache.load(context, url) {})
        assertNotNull(LessonThumbnailCache.load(context, url) {})

        assertEquals(1, response.requestCount.get())
    }

    @Test
    fun concurrentRequestsForSameUrlShareOneDownload() = runBlocking {
        val url = newUrl()
        val releaseResponse = CountDownLatch(1)
        val response = FakeThumbnailResponses.respond(
            url = url,
            statusCode = 200,
            body = validPng(),
            responseRelease = releaseResponse
        )

        val first = async(Dispatchers.IO) { LessonThumbnailCache.load(context, url) {} }
        assertTrue(response.requestStarted.await(5, TimeUnit.SECONDS))
        val secondStarted = CountDownLatch(1)
        val second = async(Dispatchers.IO) {
            LessonThumbnailCache.load(context, url) { secondStarted.countDown() }
        }
        try {
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            withContext(Dispatchers.IO) {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
                while (System.nanoTime() < deadline && response.requestCount.get() == 1) {
                    Thread.sleep(10)
                }
            }
            assertEquals(1, response.requestCount.get())
        } finally {
            releaseResponse.countDown()
        }

        assertNotNull(first.await())
        assertNotNull(second.await())
        assertEquals(1, response.requestCount.get())
    }

    @Test
    fun invalidDownloadLeavesNoValidCacheAndLaterRetrySucceeds() = runBlocking {
        val url = newUrl()
        val response = FakeThumbnailResponses.respond(url, statusCode = 200, body = byteArrayOf(1, 2, 3))

        assertNull(LessonThumbnailCache.load(context, url) {})
        assertTrue(!isDecodable(cacheFile(url)))

        response.statusCode = 200
        response.body = validPng()
        assertNotNull(LessonThumbnailCache.load(context, url) {})

        assertEquals(2, response.requestCount.get())
        assertTrue(isDecodable(cacheFile(url)))
    }

    @Test
    fun corruptExistingCacheFileIsNotDisplayedAsValidImage() = runBlocking {
        val url = newUrl()
        val corruptFile = cacheFile(url)
        check(corruptFile.parentFile?.mkdirs() == true || corruptFile.parentFile?.isDirectory == true)
        corruptFile.writeBytes(byteArrayOf(9, 8, 7, 6))
        val response = FakeThumbnailResponses.respond(url, statusCode = 503, body = byteArrayOf())

        assertNull(LessonThumbnailCache.load(context, url) {})

        assertEquals(1, response.requestCount.get())
        assertTrue(!isDecodable(corruptFile))
    }

    @Test
    fun differentUrlsUseSeparateCacheEntries() = runBlocking {
        val firstUrl = newUrl()
        val secondUrl = newUrl()
        val firstResponse =
            FakeThumbnailResponses.respond(firstUrl, statusCode = 200, body = validPng())
        val secondResponse =
            FakeThumbnailResponses.respond(secondUrl, statusCode = 200, body = validPng())

        assertNotNull(LessonThumbnailCache.load(context, firstUrl) {})
        assertNotNull(LessonThumbnailCache.load(context, secondUrl) {})

        assertTrue(cacheFile(firstUrl).isFile)
        assertTrue(cacheFile(secondUrl).isFile)
        assertTrue(cacheFile(firstUrl).name != cacheFile(secondUrl).name)
        assertEquals(1, firstResponse.requestCount.get())
        assertEquals(1, secondResponse.requestCount.get())
    }

    private fun newUrl(): String =
        "https://thumbnail-cache.test/${UUID.randomUUID()}.png".also(responseUrls::add)

    private fun cacheFile(url: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(File(context.filesDir, "lesson-thumbnails"), "$digest.img")
    }

    private fun validPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun isDecodable(file: File): Boolean =
        file.isFile && BitmapFactory.decodeFile(file.absolutePath) != null

    private companion object {
        @BeforeClass
        @JvmStatic
        fun installHttpsTestHandler() {
            URL.setURLStreamHandlerFactory(URLStreamHandlerFactory { protocol ->
                if (!protocol.equals("https", ignoreCase = true)) {
                    null
                } else {
                    object : URLStreamHandler() {
                        override fun openConnection(url: URL): URLConnection =
                            FakeHttpsURLConnection(url, FakeThumbnailResponses.response(url.toString()))
                    }
                }
            })
        }
    }
}

private object FakeThumbnailResponses {
    private val responses = java.util.concurrent.ConcurrentHashMap<String, FakeThumbnailResponse>()

    fun respond(
        url: String,
        statusCode: Int,
        body: ByteArray,
        responseRelease: CountDownLatch? = null
    ): FakeThumbnailResponse =
        FakeThumbnailResponse(statusCode, body, responseRelease).also {
            responses[url] = it
        }

    fun response(url: String): FakeThumbnailResponse =
        responses[url] ?: FakeThumbnailResponse(404, byteArrayOf(), null)

    fun clear() = responses.clear()
}

private class FakeThumbnailResponse(
    @Volatile var statusCode: Int,
    @Volatile var body: ByteArray,
    private val responseRelease: CountDownLatch?
) {
    val requestCount = AtomicInteger()
    val requestStarted = CountDownLatch(1)

    fun awaitResponse() {
        requestCount.incrementAndGet()
        requestStarted.countDown()
        responseRelease?.await(5, TimeUnit.SECONDS)
    }
}

private class FakeHttpsURLConnection(
    url: URL,
    private val response: FakeThumbnailResponse
) : HttpsURLConnection(url) {
    private val requestRecorded = AtomicBoolean()

    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int {
        recordRequest()
        return response.statusCode
    }

    override fun getContentLengthLong(): Long = response.body.size.toLong()

    override fun getInputStream(): InputStream {
        recordRequest()
        if (response.statusCode !in 200..299) {
            throw java.io.IOException("Fake HTTP error ${response.statusCode}")
        }
        return ByteArrayInputStream(response.body)
    }

    override fun getCipherSuite(): String = "TLS_FAKE_WITH_NULL_NULL"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
    override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null

    private fun recordRequest() {
        if (requestRecorded.compareAndSet(false, true)) response.awaitResponse()
    }
}
