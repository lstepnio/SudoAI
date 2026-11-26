package com.majjix.sudoai

import java.io.ByteArrayOutputStream

/** Firmware transport: LE packet counter, then fragment index within one codec frame. */
class OmiFramer(var payloadLimit: Int = 511, private val lost: () -> Unit) {
    private var last = -1
    private var expectedFragment = 0
    private var pending = ByteArrayOutputStream()

    fun reset() {
        last = -1
        expectedFragment = 0
        pending.reset()
    }

    @Synchronized
    fun offer(packet: ByteArray): List<ByteArray> {
        if (packet.size <= 3) {
            reset()
            lost()
            return emptyList()
        }
        val counter = (packet[0].toInt() and 255) or ((packet[1].toInt() and 255) shl 8)
        val fragment = packet[2].toInt() and 255
        if (last >= 0 && counter != ((last + 1) and 65535)) {
            pending.reset()
            expectedFragment = 0
            lost()
        }
        last = counter
        val out = mutableListOf<ByteArray>()
        if (fragment == 0) {
            if (pending.size() > 0) out += pending.toByteArray()
            pending.reset()
            expectedFragment = 0
        }
        if (fragment != expectedFragment) {
            pending.reset()
            expectedFragment = 0
            lost()
            return out
        }
        pending.write(packet, 3, packet.size - 3)
        expectedFragment++
        if (pending.size() > 32768) {
            pending.reset()
            expectedFragment = 0
            lost()
            return out
        }
        if (packet.size - 3 < payloadLimit) {
            out += pending.toByteArray()
            pending.reset()
            expectedFragment = 0
        }
        return out
    }
}
