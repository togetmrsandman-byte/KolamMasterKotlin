package com.kolammaster.app

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object KmpLessonExtractor {
    private const val HEADER = "KMP1"
    private const val HEADER_SIZE = 4
    private const val IV_SIZE = 12
    private const val GCM_TAG_SIZE = 16

    fun extractAsset(
        context: Context,
        assetName: String,
        sharedSecret: String,
        destination: File
    ): List<String> {
        val encryptedPackage = context.assets.open(assetName).use { it.readBytes() }
        return extractPackage(encryptedPackage, sharedSecret, destination)
    }

    fun extractPackage(
        encryptedPackage: ByteArray,
        sharedSecret: String,
        destination: File
    ): List<String> {
        require(sharedSecret.isNotEmpty()) { "SHARED_SECRET is not configured" }
        require(encryptedPackage.size >= HEADER_SIZE + IV_SIZE + GCM_TAG_SIZE) {
            "KMP package is too short"
        }
        require(String(encryptedPackage, 0, HEADER_SIZE, Charsets.US_ASCII) == HEADER) {
            "Invalid KMP header"
        }

        val ivStart = HEADER_SIZE
        val ciphertextStart = ivStart + IV_SIZE
        val iv = encryptedPackage.copyOfRange(ivStart, ciphertextStart)
        val ciphertext = encryptedPackage.copyOfRange(ciphertextStart, encryptedPackage.size)

        val key = MessageDigest.getInstance("SHA-256")
            .digest(sharedSecret.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_SIZE * 8, iv)
        )
        val zipBytes = cipher.doFinal(ciphertext)
        require(isZipSignature(zipBytes)) { "Decrypted KMP data is not a ZIP archive" }

        val root = destination.canonicalFile
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Could not create extraction directory: $root")
        }
        val rootPrefix = root.path + File.separator
        val extractedFiles = mutableListOf<String>()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                if (target.path != root.path && !target.path.startsWith(rootPrefix)) {
                    throw IOException("ZIP entry escapes extraction directory: ${entry.name}")
                }

                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("Could not create directory: $target")
                    }
                } else {
                    val parent = target.parentFile
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw IOException("Could not create directory: $parent")
                    }
                    FileOutputStream(target).use { output -> zip.copyTo(output) }
                    extractedFiles += entry.name
                }
                zip.closeEntry()
            }
        }

        return extractedFiles
    }

    private fun isZipSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return false
        }
        return when {
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte() -> true
            bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte() -> true
            bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte() -> true
            else -> false
        }
    }
}
