package com.majjix.sudoai

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class AudioBytesTest {
    @Test
    fun wavHeaderMatchesSamples() {
        val pcm = ByteArray(640) { it.toByte() }
        val wav = AudioBytes.wav(pcm)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(676, b.getInt(4))
        assertEquals(16000, b.getInt(24))
        assertEquals(640, b.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }

    @Test
    fun fragmentedOmiFrameReassemblesAndReportsLoss() {
        var loss = 0
        val framer = OmiFramer(4) { loss++ }
        assertTrue(framer.offer(byteArrayOf(0, 0, 0, 1, 2, 3, 4)).isEmpty())
        val completed = framer.offer(byteArrayOf(1, 0, 1, 5, 6))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), completed.single())
        framer.offer(byteArrayOf(3, 0, 0, 7))
        assertEquals(1, loss)
    }

    @Test
    fun omiNegotiatesBothFirmwareDurations() {
        assertEquals(10, AudioBytes.frameMs(20))
        assertEquals(20, AudioBytes.frameMs(21))
        assertArrayEquals(byteArrayOf(4, 5), AudioBytes.opusPacket(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun truncatedNotificationDropsPendingFrameAndReportsGap() {
        var losses = 0
        val framer = OmiFramer(4) { losses++ }
        framer.offer(byteArrayOf(0, 0, 0, 1, 2, 3, 4))
        assertTrue(framer.offer(byteArrayOf(1, 0, 1)).isEmpty())
        assertEquals(1, losses)
        assertArrayEquals(byteArrayOf(9), framer.offer(byteArrayOf(2, 0, 0, 9)).single())
    }

    @Test
    fun sequenceWrapAndReconnectResetDoNotReportSpuriousLoss() {
        var losses = 0
        val framer = OmiFramer(4) { losses++ }
        framer.offer(byteArrayOf(-1, -1, 0, 1, 2, 3, 4))
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5),
            framer.offer(byteArrayOf(0, 0, 1, 5)).single(),
        )
        framer.reset()
        assertArrayEquals(byteArrayOf(8), framer.offer(byteArrayOf(45, 0, 0, 8)).single())
        assertEquals(0, losses)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownOmiCodecIsRejected() {
        AudioBytes.frameMs(99)
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedOmiPacketIsRejected() {
        AudioBytes.opusPacket(byteArrayOf(0, 0, 0))
    }
}
