package com.example.sonicchat

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

// ---------- انواع فریم مطمئن ----------
object ReliableProto {
    const val TYPE_START = 8
    const val TYPE_DATA  = 10
    const val TYPE_END   = 12
    const val TYPE_ACK   = 14
    const val PACKET_SIZE = 20
    const val ACK_TIMEOUT_MS = 2500L
    const val MAX_RETRIES = 5

    fun isReliable(flags: Int) = (flags and 0x08) != 0

    fun startPayload(name: String, totalSize: Long, packetCount: Int): ByteArray {
        val nameB = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(nameB.size + 1 + 8 + 2)
        System.arraycopy(nameB, 0, out, 0, nameB.size)
        out[nameB.size] = 0
        var p = nameB.size + 1
        for (i in 0 until 8) { out[p + i] = ((totalSize shr (8 * (7 - i))) and 0xFF).toByte() }
        p += 8
        out[p] = ((packetCount shr 8) and 0xFF).toByte()
        out[p + 1] = (packetCount and 0xFF).toByte()
        return out
    }

    fun dataPayload(seq: Int, data: ByteArray): ByteArray {
        val out = ByteArray(2 + data.size)
        out[0] = ((seq shr 8) and 0xFF).toByte()
        out[1] = (seq and 0xFF).toByte()
        System.arraycopy(data, 0, out, 2, data.size)
        return out
    }

    fun ackPayload(seq: Int, ok: Boolean): ByteArray = byteArrayOf(
        ((seq shr 8) and 0xFF).toByte(),
        (seq and 0xFF).toByte(),
        if (ok) 1 else 0
    )

    fun parseSeq(payload: ByteArray): Int =
        ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    fun parseAck(payload: ByteArray): Pair<Int, Boolean> {
        val seq = parseSeq(payload)
        val ok = payload.size >= 3 && payload[2].toInt() == 1
        return seq to ok
    }
}

// ---------- سمت فرستنده ----------
class ReliableTx(
    private val sendOnTx: (Int, ByteArray) -> Unit,
    private val waitAck: (Int) -> Boolean,          // seq -> ok
    private val onPacket: (Int, Int, Int) -> Unit,  // current, total, retries
    private val onDone: (lost: List<Int>) -> Unit
) {
    @Volatile var running = false

    fun start(name: String, fileBytes: ByteArray) {
        running = true
        val packets = ArrayList<ByteArray>()
        var off = 0
        while (off < fileBytes.size) {
            val end = minOf(off + ReliableProto.PACKET_SIZE, fileBytes.size)
            packets.add(fileBytes.copyOfRange(off, end))
            off = end
        }
        if (packets.isEmpty()) packets.add(ByteArray(0))
        val total = packets.size
        val lost = ArrayList<Int>()

        try {
            // شروع
            var okStart = false
            for (attempt in 0 until ReliableProto.MAX_RETRIES) {
                sendOnTx(ReliableProto.TYPE_START, ReliableProto.startPayload(name, fileBytes.size.toLong(), total))
                if (waitAck(-1)) { okStart = true; break }
            }
            if (!okStart) { onDone(listOf(-1)); return }

            // داده‌ها
            packets.forEachIndexed { idx, pkt ->
                if (!running) return@forEachIndexed
                var acked = false
                for (attempt in 0 until ReliableProto.MAX_RETRIES) {
                    onPacket(idx + 1, total, attempt + 1)
                    sendOnTx(ReliableProto.TYPE_DATA, ReliableProto.dataPayload(idx, pkt))
                    if (waitAck(idx)) { acked = true; break }
                }
                if (!acked) lost.add(idx)
            }

            // پایان
            for (attempt in 0 until ReliableProto.MAX_RETRIES) {
                sendOnTx(ReliableProto.TYPE_END, ByteArray(0))
                if (waitAck(-2)) break
            }
            onDone(lost)
        } finally {
            running = false
        }
    }

    fun stop() { running = false }
}

// ---------- سمت گیرنده ----------
class ReliableRx(private val ctx: Context) {
    private var fileName: String? = null
    private var expectedSize = -1L
    private var totalPackets = -1
    private val packets = HashMap<Int, ByteArray>()
    private val received = HashSet<Int>()
    private var lastSaved: File? = null

    @Volatile var active = false
    @Volatile var progress = 0

    fun reset() {
        fileName = null; expectedSize = -1; totalPackets = -1
        packets.clear(); received.clear(); active = false; progress = 0
    }

    /** true اگر فریم مطمئن بود */
    fun onFrame(flags: Int, payload: ByteArray,
                sendAck: (Int, Boolean) -> Unit,
                onProgress: (Int, Int) -> Unit,
                onDone: (File) -> Unit): Boolean {
        if (!ReliableProto.isReliable(flags)) return false
        when (flags and 0x06) {
            0 -> { // START (flags=8)
                active = true
                val meta = payload
                val sep: Int = meta.indexOf(0)
                if (sep <= 0) { sendAck(-1, false); return true }
                fileName = String(meta, 0, sep, Charsets.UTF_8)
                var v = 0L
                for (i in 0 until 8) v = (v shl 8) or (meta[sep + 1 + i].toLong() and 0xFF)
                expectedSize = v
                val pc = sep + 1 + 8
                totalPackets = ((meta[pc].toInt() and 0xFF) shl 8) or (meta[pc + 1].toInt() and 0xFF)
                sendAck(-1, true)
                return true
            }
            2 -> { // DATA (flags=10)
                if (!active) { sendAck(-1, false); return true }
                val seq = ReliableProto.parseSeq(payload)
                val data = payload.copyOfRange(2, payload.size)
                if (seq in received) {
                    sendAck(seq, true) // تکراری، ACK بده
                } else {
                    packets[seq] = data
                    received.add(seq)
                    progress = (received.size * 100 / totalPackets.coerceAtLeast(1)).coerceIn(0, 100)
                    onProgress(received.size, totalPackets)
                    sendAck(seq, true)
                }
                return true
            }
            4 -> { // END (flags=12)
                if (!active) { sendAck(-2, false); return true }
                saveFile()
                lastSaved?.let { onDone(it) }
                sendAck(-2, true)
                reset()
                return true
            }
            6 -> return false // ACK، اینجا مصرف نمی‌شود
        }
        return false
    }

    private fun saveFile() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SonicChat")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, fileName ?: "received_${System.currentTimeMillis()}.bin")
        FileOutputStream(f).use { out ->
            var i = 0
            while (i < totalPackets) {
                packets[i]?.let { out.write(it) }
                i++
            }
        }
        lastSaved = f
    }
}
