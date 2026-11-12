package com.example.sonicchat

import java.io.ByteArrayOutputStream
import kotlin.math.*

object Proto {
    const val SR = 44100
    const val SYNC = 0xD391
    const val MAX_PAYLOAD = 200
    const val CHUNK = 48          // بایت در هر فریم
    const val SPACING = 600.0     // فاصله دو فرکانس FSK (Hz)

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** فریم: AA AA AA AA | D3 91 | flags | len | payload | crc16 */
    fun frame(flags: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(2 + payload.size)
        body[0] = flags.toByte(); body[1] = payload.size.toByte()
        System.arraycopy(payload, 0, body, 2, payload.size)
        val crc = crc16(body)
        val o = ByteArrayOutputStream()
        repeat(4) { o.write(0xAA) }
        o.write(SYNC shr 8); o.write(SYNC and 0xFF)
        o.write(body)
        o.write(crc shr 8); o.write(crc and 0xFF)
        return o.toByteArray()
    }

    /** تقسیم متن به تکه‌های UTF-8 بدون شکستن کاراکتر */
    fun split(text: String): List<ByteArray> {
        val res = ArrayList<ByteArray>()
        val sb = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (sb.size() + ch.size > CHUNK) { res.add(sb.toByteArray()); sb.reset() }
            sb.write(ch)
            i += Character.charCount(cp)
        }
        if (sb.size() > 0) res.add(sb.toByteArray())
        return res
    }

    /** FSK دوتایی با فاز پیوسته */
    fun modulate(frame: ByteArray, f0: Double, f1: Double, n: Int): ShortArray {
        val total = frame.size * 8 * n
        val out = ShortArray(total)
        val fade = min(n, SR / 200)
        var phase = 0.0
        var idx = 0
        for (byte in frame) {
            for (k in 7 downTo 0) {
                val bit = (byte.toInt() shr k) and 1
                val dp = 2 * PI * (if (bit == 1) f1 else f0) / SR
                for (j in 0 until n) {
                    val g = min(1.0, min((idx + 1).toDouble() / fade, (total - idx).toDouble() / fade))
                    out[idx++] = (sin(phase) * 0.9 * g * 32767).toInt().toShort()
                    phase += dp
                    if (phase > 2 * PI) phase -= 2 * PI
                }
            }
        }
        return out
    }
}

class Demodulator(
    private val sr: Int,
    private val f0: Double,
    private val f1: Double,
    private val n: Int,
    private val onFrame: (flags: Int, payload: ByteArray) -> Unit
) {
    private val size = 1 shl 18
    private val mask = (size - 1).toLong()
    private val ring = FloatArray(size)
    private var total = 0L
    @Volatile var level = 0.0

    private inner class Dec(off: Int) {
        var next = off.toLong()
        var state = 0
        var shift = 0
        val buf = ByteArray(Proto.MAX_PAYLOAD + 8)
        var cnt = 0; var cur = 0; var bc = 0; var need = 0

        fun reset() { state = 0; shift = 0 }

        fun bit(b: Int) {
            if (state == 0) {
                shift = ((shift shl 1) or b) and 0xFFFF
                if (shift == Proto.SYNC) { state = 1; cnt = 0; cur = 0; bc = 0; need = 0 }
                return
            }
            cur = (cur shl 1) or b; bc++
            if (bc < 8) return
            buf[cnt++] = cur.toByte(); cur = 0; bc = 0
            if (cnt == 2) {
                val len = buf[1].toInt() and 0xFF
                if (len > Proto.MAX_PAYLOAD) { reset(); return }
                need = 2 + len + 2
            }
            if (need > 0 && cnt == need) {
                val body = buf.copyOfRange(0, need - 2)
                val crc = ((buf[need - 2].toInt() and 0xFF) shl 8) or (buf[need - 1].toInt() and 0xFF)
                if (Proto.crc16(body) == crc) {
                    val fl = body[0].toInt() and 0xFF
                    val pl = body.copyOfRange(2, body.size)
                    resetAll()
                    onFrame(fl, pl)
                } else reset()
            }
        }
    }

    private val dec = Array(4) { Dec(it * n / 4) }
    private fun resetAll() { for (d in dec) d.reset() }

    private fun power(start: Long, f: Double): Double {
        val coeff = 2.0 * cos(2.0 * PI * f / sr)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) {
            val s = ring[((start + i) and mask).toInt()] + coeff * s1 - s2
            s2 = s1; s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }

    fun feed(s: ShortArray, len: Int) {
        for (i in 0 until len) ring[((total + i) and mask).toInt()] = s[i] / 32768f
        total += len
        for (d in dec) {
            while (d.next + n <= total) {
                val p0 = power(d.next, f0)
                val p1 = power(d.next, f1)
                d.next += n
                d.bit(if (p1 > p0) 1 else 0)
            }
        }
        if (total >= n) {
            val st = total - n
            val a = max(power(st, f0), power(st, f1))
            level = 2.0 * sqrt(max(a, 0.0)) / n
        }
    }
}
