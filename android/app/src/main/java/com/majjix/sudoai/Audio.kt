package com.majjix.sudoai

import android.media.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

object AudioBytes {
    fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt(36 + pcm.size)
        b.put("WAVEfmt ".toByteArray())
        b.putInt(16)
        b.putShort(1)
        b.putShort(1)
        b.putInt(rate)
        b.putInt(rate * 2)
        b.putShort(2)
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(pcm.size)
        b.put(pcm)
        return b.array()
    }

    fun opusPacket(notification: ByteArray): ByteArray {
        require(notification.size > 3)
        return notification.copyOfRange(3, notification.size)
    }

    fun frameMs(codec: Int): Int =
        when (codec) {
            20 -> 10
            21 -> 20
            else -> throw IllegalArgumentException("Unsupported Omi codec $codec")
        }
}

// Each partial is persisted incrementally, with timing metadata before recording starts.
class PcmChunks(
    private val store: Store,
    private val dir: File,
    private val track: String,
    private val clock: () -> Long,
) {
    private var stream: FileOutputStream? = null
    private var file: File? = null
    private var start = 0L
    private var seq = 0
    private var size = 0

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (stream == null) {
            start = (clock() - bytes.size * 1000L / 32000).coerceAtLeast(0)
            seq = store.nextSequence(dir, track)
            file = File(dir, "partial-$track.pcm")
            atomic(
                File(dir, "partial-$track.json"),
                JSONObject().put("start_ms", start).put("track", track).put("seq", seq).toString(),
            )
            stream = FileOutputStream(file!!)
            size = 0
        }
        stream!!.write(bytes)
        size += bytes.size
        if (size >= 16000 * 2 * 20) flush()
    }

    @Synchronized
    fun flush() {
        val output = stream ?: return
        output.fd.sync()
        output.close()
        stream = null
        if (store.manifest(dir).optBoolean("deleted")) {
            file?.delete()
            File(dir, "partial-$track.json").delete()
            return
        }
        val raw = file!!.readBytes()
        if (raw.isNotEmpty())
            store.chunk(
                dir,
                track,
                start,
                start + raw.size * 1000L / 32000,
                AudioBytes.wav(raw),
                seq,
            )
        file!!.delete()
        File(dir, "partial-$track.json").delete()
        size = 0
    }
}

class OpusDecoder(private val codecId: Int) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType("audio/opus")
    private var pts = 0L
    private var outputRate = 48000

    init {
        AudioBytes.frameMs(codecId)
        val head =
            ByteBuffer.allocate(19)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("OpusHead".toByteArray())
                    put(1)
                    put(1)
                    putShort(0)
                    putInt(16000)
                    putShort(0)
                    put(0)
                }
                .array()
        val format =
            MediaFormat.createAudioFormat("audio/opus", 16000, 1).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(head))
                setByteBuffer("csd-1", ByteBuffer.wrap(ByteArray(8)))
                setByteBuffer("csd-2", ByteBuffer.wrap(ByteArray(8)))
            }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    @Synchronized
    fun decode(packet: ByteArray): ByteArray {
        val i = codec.dequeueInputBuffer(10000)
        check(i >= 0) { "Opus decoder input stalled" }
        codec.getInputBuffer(i)!!.apply {
            clear()
            put(packet)
        }
        codec.queueInputBuffer(i, 0, packet.size, pts, 0)
        pts += AudioBytes.frameMs(codecId) * 1000L
        val result = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var out = codec.dequeueOutputBuffer(info, 10000)
        while (out >= 0 || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat
                outputRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                check(
                    outputRate in listOf(16000, 48000) &&
                        f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1
                ) {
                    "Unsupported Opus output format"
                }
            } else {
                codec.getOutputBuffer(out)?.let { b ->
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    result.write(ByteArray(info.size).also(b::get))
                }
                codec.releaseOutputBuffer(out, false)
            }
            out = codec.dequeueOutputBuffer(info, 0)
        }
        val raw = result.toByteArray()
        if (outputRate == 16000) return raw
        // Android's Opus decoder normally emits 48kHz regardless of OpusHead input rate.
        val samples = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val converted =
            ByteBuffer.allocate((samples.remaining() / 3) * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (samples.remaining() >= 3) converted.putShort(
            ((samples.get().toInt() + samples.get().toInt() + samples.get().toInt()) / 3).toShort()
        )
        return converted.array()
    }

    override fun close() {
        runCatching {
            codec.stop()
            codec.release()
        }
    }
}
