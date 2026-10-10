package com.kolammaster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class GalleryPublishImageOptimizerTest {
    @Test
    fun retainsLosslessOutputWhenItFits() {
        val encoder = TestEncoder(sizeFor = { _, lossless, _ -> if (lossless) 500 else 400 })
        val output = temporaryFile()

        try {
            val result = GalleryPublishWebpOptimizer.optimize(
                FakeFrame(1200, 800),
                output,
                encoder,
                maxBytes = 1000
            )

            assertTrue(result.isLossless)
            assertEquals(500, result.byteSize)
            assertEquals(1200, result.width)
            assertEquals(800, result.height)
            assertEquals(listOf(true), encoder.attempts.map { it.lossless })
        } finally {
            output.delete()
        }
    }

    @Test
    fun oversizedLosslessFallsBackToHighestQualityLossyCandidateThatFits() {
        val encoder = TestEncoder(sizeFor = { _, lossless, quality ->
            when {
                lossless -> 1200
                quality == 96 -> 1100
                quality == 92 -> 900
                else -> 800
            }
        })
        val output = temporaryFile()

        try {
            val result = GalleryPublishWebpOptimizer.optimize(
                FakeFrame(1200, 800),
                output,
                encoder,
                maxBytes = 1000
            )

            assertFalse(result.isLossless)
            assertEquals(900, result.byteSize)
            assertEquals(92, encoder.attempts.last().quality)
            assertEquals(1200, result.width)
            assertEquals(800, result.height)
        } finally {
            output.delete()
        }
    }

    @Test
    fun progressivelyResizesWhilePreservingAspectRatio() {
        val encoder = TestEncoder(sizeFor = { frame, lossless, _ ->
            when {
                lossless -> 1200
                frame.width > 700 -> 1100
                else -> 800
            }
        })
        val output = temporaryFile()

        try {
            val result = GalleryPublishWebpOptimizer.optimize(
                FakeFrame(1200, 600),
                output,
                encoder,
                maxBytes = 1000
            )

            assertFalse(result.isLossless)
            assertTrue(result.byteSize < 1000)
            assertTrue(result.width <= 700)
            assertEquals(2.0, result.width.toDouble() / result.height, 0.02)
        } finally {
            output.delete()
        }
    }

    @Test(expected = IOException::class)
    fun invalidOutputIsNeverAccepted() {
        val encoder = TestEncoder(
            sizeFor = { _, _, _ -> 400 },
            validWebp = false
        )
        val output = temporaryFile()
        try {
            GalleryPublishWebpOptimizer.optimize(
                FakeFrame(512, 256),
                output,
                encoder,
                maxBytes = 1000
            )
        } finally {
            output.delete()
        }
    }

    private fun temporaryFile() = File.createTempFile("publish-optimizer-test-", ".webp")

    private data class EncodeAttempt(
        val width: Int,
        val height: Int,
        val lossless: Boolean,
        val quality: Int
    )

    private class FakeFrame(
        override val width: Int,
        override val height: Int
    ) : GalleryPublishImageFrame {
        override fun resize(width: Int, height: Int): GalleryPublishImageFrame =
            FakeFrame(width, height)

        override fun recycle() = Unit
    }

    private class TestEncoder(
        private val sizeFor: (GalleryPublishImageFrame, Boolean, Int) -> Int,
        private val validWebp: Boolean = true
    ) : GalleryPublishWebpEncoder {
        val attempts = mutableListOf<EncodeAttempt>()

        override fun encode(
            frame: GalleryPublishImageFrame,
            destination: File,
            lossless: Boolean,
            quality: Int
        ): Boolean {
            attempts += EncodeAttempt(frame.width, frame.height, lossless, quality)
            val targetSize = sizeFor(frame, lossless, quality)
            destination.writeBytes(
                if (validWebp) createWebp(lossless, targetSize) else ByteArray(targetSize)
            )
            return true
        }
    }

    private companion object {
        fun createWebp(lossless: Boolean, totalSize: Int): ByteArray {
            require(totalSize >= 30 && totalSize % 2 == 0)
            val chunkType = if (lossless) "VP8L" else "VP8 "
            val payload = ByteArray(totalSize - 20)
            val riffSize = totalSize - 8
            return byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                riffSize.toByte(), (riffSize shr 8).toByte(),
                (riffSize shr 16).toByte(), (riffSize shr 24).toByte(),
                'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
                chunkType[0].code.toByte(), chunkType[1].code.toByte(),
                chunkType[2].code.toByte(), chunkType[3].code.toByte(),
                payload.size.toByte(), (payload.size shr 8).toByte(),
                (payload.size shr 16).toByte(), (payload.size shr 24).toByte(),
                *payload
            ).apply {
                if (lossless) {
                    this[20] = 0x2f
                } else {
                    this[23] = 0x9d.toByte()
                    this[24] = 0x01
                    this[25] = 0x2a
                    this[26] = 1
                    this[28] = 1
                }
            }
        }
    }
}
