package com.example.sonicchat

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

object FileTx {
    data class Chunk(val flags: Int, val data: ByteArray)

    /** flags: bit0=more، bit1..2=type (2=start، 4=data، 6=end) */
    fun prepare(ctx: Context, uri: Uri, chunkSize: Int = Proto.CHUNK): List<Chunk> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        val name = queryName(ctx, uri) ?: "file.bin"
        val list = ArrayList<Chunk>()

        // start: name + 0x00 + طول فایل ۸ بایت
        val startMeta = name.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + longToBytes(bytes.size.toLong())
        splitIntoFrames(startMeta, chunkSize).forEachIndexed { i, c ->
            // اگر بیش از یک تکه است، more=1 بگذار (به‌جز آخرین)
            val more = if (i < splitIntoFrames(startMeta, chunkSize).size - 1) 1 else 0
            list.add(Chunk(2 or more, c))
        }

        var off = 0
        while (off < bytes.size) {
            val end = minOf(off + chunkSize, bytes.size)
            val c = bytes.copyOfRange(off, end)
            off = end
            val more = if (off < bytes.size) 1 else 0
            list.add(Chunk(4 or more, c))
        }

        list.add(Chunk(6, ByteArray(0)))
        return list
    }

    private fun splitIntoFrames(data: ByteArray, size: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < data.size) {
            val end = minOf(i + size, data.size)
            out.add(data.copyOfRange(i, end))
            i = end
        }
        if (out.isEmpty()) out.add(ByteArray(0))
        return out
    }

    private fun longToBytes(v: Long): ByteArray =
        ByteArray(8) { ((v shr (8 * (7 - it))) and 0xFF).toByte() }

    private fun queryName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (e: Exception) { null }
}

class FileRx(private val ctx: Context) {
    private var fileName: String? = null
    private var expectedSize = -1L
    private val buf = java.io.ByteArrayOutputStream()
    private val metaBuf = java.io.ByteArrayOutputStream()
    private var savedFile: File? = null

    @Volatile var active = false
    @Volatile var progress = 0

    fun reset() {
        fileName = null; expectedSize = -1; buf.reset(); metaBuf.reset()
        active = false; progress = 0
    }

    /** flags: bit0=more، bit1..2=type */
    fun onFrame(flags: Int, payload: ByteArray, onProgress: (Int) -> Unit, onDone: (File) -> Unit): Boolean {
        val type = flags and 0x06
        val more = flags and 0x01
        return when (type) {
            2 -> { // start
                active = true
                metaBuf.write(payload)
                if (more == 0) {
                    val meta = metaBuf.toByteArray()
                    val sep: Int = meta.indexOf(0)
                    if (sep > 0 && meta.size >= sep + 9) {
                        fileName = String(meta, 0, sep, Charsets.UTF_8)
                        expectedSize = bytesToLong(meta, sep + 1)
                    }
                    metaBuf.reset()
                }
                true
            }
            4 -> { // data
                if (!active) return true
                buf.write(payload)
                val total = expectedSize.coerceAtLeast(1L)
                progress = ((buf.size().toLong() * 100L / total).coerceIn(0L, 100L)).toInt()
                onProgress(progress)
                true
            }
            6 -> { // end
                if (!active) return true
                saveFile()
                onDone(savedFile ?: File(ctx.filesDir, "last_file"))
                reset()
                true
            }
            else -> false
        }
    }

    private fun saveFile() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SonicChat")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, fileName ?: "received_${System.currentTimeMillis()}.bin")
        FileOutputStream(f).use { it.write(buf.toByteArray()) }
        savedFile = f
    }

    private fun bytesToLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
