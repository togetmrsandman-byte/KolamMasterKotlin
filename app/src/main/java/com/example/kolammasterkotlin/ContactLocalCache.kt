package com.kolammaster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class ContactCacheSnapshot<T>(
    val value: T,
    val savedAtMillis: Long
)

internal class ContactLocalCache(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun loadConversations(userId: String): ContactCacheSnapshot<List<ContactConversation>>? =
        loadArray(userId, CONVERSATIONS_PREFIX) { json ->
            buildList {
                for (index in 0 until json.length()) {
                    val row = json.optJSONObject(index) ?: continue
                    try {
                        val conversation = ContactJson.decodeConversation(row)
                        if (conversation.userId == userId) add(conversation)
                    } catch (_: ContactDataException) {
                        continue
                    }
                }
            }
        }

    @Synchronized
    fun saveConversations(userId: String, conversations: List<ContactConversation>) {
        val json = JSONArray().apply {
            conversations.forEach { conversation ->
                put(
                    JSONObject()
                        .put("id", conversation.id)
                        .put("user_id", conversation.userId)
                        .put("email", conversation.email)
                        .put("phone", conversation.phone)
                        .put("subject", conversation.subject)
                        .put("status", conversation.status)
                        .put("created_at", conversation.createdAt)
                        .put("updated_at", conversation.updatedAt)
                        .put(
                            "contact_messages",
                            JSONArray().apply {
                                conversation.latestMessageCreatedAt?.let { createdAt ->
                                    put(
                                        JSONObject()
                                            .put("created_at", createdAt)
                                            .put(
                                                "message",
                                                conversation.latestMessagePreview.orEmpty()
                                            )
                                    )
                                }
                            }
                        )
                )
            }
        }
        saveJson(userId, CONVERSATIONS_PREFIX, json)
    }

    @Synchronized
    fun upsertConversation(userId: String, conversation: ContactConversation) {
        val current = loadConversations(userId)?.value.orEmpty()
        val updated = (current.filterNot { it.id == conversation.id } + conversation)
            .sortedByDescending {
                it.latestMessageCreatedAt ?: it.updatedAt.ifBlank { it.createdAt }
            }
        saveConversations(userId, updated)
    }

    @Synchronized
    fun loadConversation(
        userId: String,
        conversationId: String
    ): ContactCacheSnapshot<ContactConversation>? =
        loadJson(userId, conversationKey(conversationId)) { json ->
            ContactJson.decodeConversation(json)
        }

    @Synchronized
    fun saveConversation(userId: String, conversation: ContactConversation) {
        saveJson(
            userId,
            conversationKey(conversation.id),
            JSONObject()
                .put("id", conversation.id)
                .put("user_id", conversation.userId)
                .put("email", conversation.email)
                .put("phone", conversation.phone)
                .put("subject", conversation.subject)
                .put("status", conversation.status)
                .put("created_at", conversation.createdAt)
                .put("updated_at", conversation.updatedAt)
        )
    }

    @Synchronized
    fun loadMessages(
        userId: String,
        conversationId: String
    ): ContactCacheSnapshot<List<ContactMessage>>? =
        loadArray(userId, messagesKey(conversationId)) { json ->
            buildList {
                for (index in 0 until json.length()) {
                    val row = json.optJSONObject(index) ?: continue
                    try {
                        add(ContactJson.decodeMessage(row))
                    } catch (_: ContactDataException) {
                        continue
                    }
                }
            }
        }

    @Synchronized
    fun saveMessages(userId: String, conversationId: String, messages: List<ContactMessage>) {
        val json = JSONArray().apply {
            messages.forEach { message -> put(message.toJson()) }
        }
        saveJson(userId, messagesKey(conversationId), json)
    }

    @Synchronized
    fun upsertMessage(userId: String, message: ContactMessage) {
        val current = loadMessages(userId, message.conversationId)?.value.orEmpty()
        val updated = (current.filterNot { it.id == message.id } + message)
            .sortedBy(ContactMessage::createdAt)
        saveMessages(userId, message.conversationId, updated)
        val conversation = loadConversation(userId, message.conversationId)?.value
        if (conversation != null) {
            val updatedConversation = conversation.copy(
                updatedAt = message.createdAt,
                latestMessageCreatedAt = message.createdAt,
                latestMessagePreview = message.message.takeIf(String::isNotBlank)
                    ?: message.imageUrl?.let { ContactJson.IMAGE_ATTACHMENT_PREVIEW }
            )
            saveConversation(userId, updatedConversation)
            val conversations = loadConversations(userId)?.value.orEmpty()
            if (conversations.any { it.id == message.conversationId }) {
                saveConversations(
                    userId,
                    conversations.map {
                        if (it.id == message.conversationId) updatedConversation else it
                    }.sortedByDescending {
                        it.latestMessageCreatedAt ?: it.updatedAt.ifBlank { it.createdAt }
                    }
                )
            }
        }
    }

    fun isFresh(savedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean =
        savedAtMillis > 0L && nowMillis - savedAtMillis < CACHE_FRESHNESS_MILLIS

    suspend fun loadImage(url: String, retry: Boolean = false): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://", ignoreCase = true)) return@withContext null
            val directory = File(applicationContext.filesDir, IMAGE_DIRECTORY)
            val target = File(directory, "${url.sha256Hex()}.img")
            if (retry) target.delete()
            decodeImage(target)?.let { return@withContext it.asImageBitmap() }
            target.delete()
            if (!directory.exists() && !directory.mkdirs()) return@withContext null
            if (!directory.isDirectory) return@withContext null

            val temporary = try {
                File.createTempFile("contact-", ".tmp", directory)
            } catch (_: IOException) {
                return@withContext null
            }
            try {
                downloadImage(url, temporary)
                val bitmap = decodeImage(temporary) ?: return@withContext null
                bitmap.recycle()
                Os.rename(temporary.absolutePath, target.absolutePath)
                trimImageCache(directory, target)
                decodeImage(target)?.asImageBitmap()
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: android.system.ErrnoException) {
                null
            } catch (_: SecurityException) {
                null
            } finally {
                temporary.delete()
            }
        }

    @Synchronized
    private fun <T> loadJson(
        userId: String,
        prefix: String,
        decode: (JSONObject) -> T
    ): ContactCacheSnapshot<T>? {
        require(userId.isNotBlank()) { "Contact cache requires an account ID." }
        val key = cacheKey(userId, prefix)
        val encoded = preferences.getString(key, null) ?: return null
        val savedAt = preferences.getLong(timestampKey(key), 0L)
        return try {
            ContactCacheSnapshot(decode(JSONObject(encoded)), savedAt)
        } catch (_: JSONException) {
            null
        } catch (_: ContactDataException) {
            null
        }
    }

    @Synchronized
    private fun <T> loadArray(
        userId: String,
        prefix: String,
        decode: (JSONArray) -> T
    ): ContactCacheSnapshot<T>? {
        require(userId.isNotBlank()) { "Contact cache requires an account ID." }
        val key = cacheKey(userId, prefix)
        val encoded = preferences.getString(key, null) ?: return null
        val savedAt = preferences.getLong(timestampKey(key), 0L)
        return try {
            ContactCacheSnapshot(decode(JSONArray(encoded)), savedAt)
        } catch (_: JSONException) {
            null
        } catch (_: ContactDataException) {
            null
        }
    }

    @Synchronized
    private fun saveJson(userId: String, prefix: String, json: Any) {
        require(userId.isNotBlank()) { "Contact cache requires an account ID." }
        val key = cacheKey(userId, prefix)
        if (!preferences.edit()
                .putString(key, json.toString())
                .putLong(timestampKey(key), System.currentTimeMillis())
                .commit()
        ) {
            Log.w(TAG, "Could not persist Contact Us cache data.")
        }
    }

    private fun cacheKey(userId: String, prefix: String): String =
        "$prefix${userId.sha256Hex()}"

    private fun timestampKey(key: String): String = "$key:updated-at"

    private fun conversationKey(conversationId: String): String =
        "$CONVERSATION_PREFIX${conversationId.sha256Hex()}"

    private fun messagesKey(conversationId: String): String =
        "$MESSAGES_PREFIX${conversationId.sha256Hex()}"

    private fun ContactMessage.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("conversation_id", conversationId)
            .put("sender", sender)
            .put("message", message)
            .put("image_url", imageUrl)
            .put("created_at", createdAt)

    private fun downloadImage(url: String, destination: File) {
        val connection = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IOException("Contact image URL must use HTTPS.")
        try {
            connection.connectTimeout = IMAGE_CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = IMAGE_READ_TIMEOUT_MILLIS
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Image request failed with HTTP $status.")
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                throw IOException("Contact image exceeds the cache size limit.")
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
                            throw IOException("Contact image exceeds the cache size limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
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

    private fun trimImageCache(directory: File, newestFile: File) {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        var totalBytes = files.sumOf(File::length)
        files.asSequence()
            .filter { it != newestFile }
            .sortedBy(File::lastModified)
            .forEach { file ->
                if (totalBytes <= MAX_IMAGE_CACHE_BYTES) return
                val fileSize = file.length()
                if (file.delete()) totalBytes -= fileSize
            }
    }

    private fun String.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PREFERENCES_NAME = "kolam_master_contact_cache"
        const val CONVERSATIONS_PREFIX = "conversations:"
        const val CONVERSATION_PREFIX = "conversation:"
        const val MESSAGES_PREFIX = "messages:"
        const val IMAGE_DIRECTORY = "contact-images"
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_IMAGE_CACHE_BYTES = 100L * 1024 * 1024
        const val MAX_DECODED_DIMENSION = 1024
        const val IMAGE_CONNECT_TIMEOUT_MILLIS = 10_000
        const val IMAGE_READ_TIMEOUT_MILLIS = 15_000
        const val COPY_BUFFER_SIZE = 16 * 1024
        const val CACHE_FRESHNESS_MILLIS = 30_000L
        const val TAG = "ContactLocalCache"
    }
}
