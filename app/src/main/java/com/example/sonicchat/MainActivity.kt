package com.example.sonicchat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val SR = Proto.SR
    private val speedMs = intArrayOf(40, 20, 10)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var etFreq: EditText
    private lateinit var etAckFreq: EditText
    private lateinit var spSpeed: Spinner
    private lateinit var etSend: EditText
    private lateinit var tvRx: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var pbLevel: ProgressBar
    private lateinit var swRx: Switch
    private lateinit var swAck: Switch
    private lateinit var cbMax: CheckBox
    private lateinit var rxScroll: ScrollView

    @Volatile private var demodTx: Demodulator? = null
    @Volatile private var demodAck: Demodulator? = null
    @Volatile private var playing = false   // فقط هنگام پخش صدا
    @Volatile private var listening = false
    private val pending = StringBuilder()
    private val flushRunnable = Runnable { flushPending(true) }

    private var f0 = 2000.0
    private var fAck = 3000.0
    private var symN = SR * 20 / 1000
    private lateinit var fileRx: FileRx
    private lateinit var relRx: ReliableRx
    private lateinit var relTx: ReliableTx
    private val REQ_FILE_NORMAL = 42
    private val REQ_FILE_RELIABLE = 43
    private val ackQueue = LinkedBlockingQueue<Pair<Int, Boolean>>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        prefs = getSharedPreferences("s", MODE_PRIVATE)
        fileRx = FileRx(this)
        relRx = ReliableRx(this)
        relTx = ReliableTx(
            sendOnTx = { flags, payload -> sendOneFrame(flags, payload, f0) },
            waitAck = { seq ->
                val deadline = System.currentTimeMillis() + ReliableProto.ACK_TIMEOUT_MS
                var result = false
                while (System.currentTimeMillis() < deadline) {
                    val item = ackQueue.poll(200, TimeUnit.MILLISECONDS)
                    if (item != null) {
                        val (ackSeq, ok) = item
                        if ((seq == -1 && ackSeq == -1) || (seq == -2 && ackSeq == -2) || ackSeq == seq) {
                            result = ok
                            break
                        }
                    }
                }
                result
            },
            onPacket = { cur, total, tries -> ui.post { tvProgress.text = "پکت $cur/$total — تلاش $tries" } },
            onDone = { lost ->
                ui.post {
                    tvStatus.text = "ارسال مطمئن تمام شد"
                    tvProgress.text = if (lost.isEmpty()) "همه پکت‌ها سالم ✔" else "گم‌شده: ${lost.joinToString(",")}"
                }
            }
        )
        buildUi()
        applySettings()
        swRx.setOnCheckedChangeListener { _, on -> if (on) startListening() else stopListening() }
        swRx.isChecked = true
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        fun lp(w: Int = -1, h: Int = -2, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

        root.addView(TextView(this).apply { text = "SonicChat — 7.1 (ARQ دو فرکانس)"; textSize = 16f })
        tvStatus = TextView(this).apply { text = "آماده" }
        root.addView(tvStatus)
        pbLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(pbLevel, lp())
        tvProgress = TextView(this).apply { text = ""; textSize = 13f }
        root.addView(tvProgress)

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "TX: " })
        etFreq = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("freq", 2000).toString())
        }
        row.addView(etFreq, lp(dp(70), -2))
        row.addView(TextView(this).apply { text = " ACK: " })
        etAckFreq = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("ackfreq", 3000).toString())
        }
        row.addView(etAckFreq, lp(dp(70), -2))
        spSpeed = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("کند", "متوسط", "سریع"))
            setSelection(prefs.getInt("speed", 1))
        }
        row.addView(spSpeed, lp(0, -2, 1f))
        root.addView(row, lp())

        cbMax = CheckBox(this).apply { text = "حداکثر صدا"; isChecked = true }
        root.addView(cbMax)

        etSend = EditText(this).apply {
            hint = "متن..."
            minLines = 2; maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
        }
        root.addView(etSend, lp())

        val b1 = LinearLayout(this)
        b1.addView(Button(this).apply { text = "متن"; setOnClickListener { send() } }, lp(0, -2, 1f))
        b1.addView(Button(this).apply { text = "فایل عادی"; setOnClickListener { pickFile(REQ_FILE_NORMAL) } }, lp(0, -2, 1f))
        root.addView(b1, lp())

        val b2 = LinearLayout(this)
        b2.addView(Button(this).apply {
            text = "فایل مطمئن (ARQ)"
            setOnClickListener { pickFile(REQ_FILE_RELIABLE) }
        }, lp(0, -2, 1f))
        b2.addView(Button(this).apply {
            text = "اعمال"
            setOnClickListener { applySettings(); Toast.makeText(context, "ok", Toast.LENGTH_SHORT).show() }
        }, lp(0, -2, 1f))
        root.addView(b2, lp())

        val sw = LinearLayout(this)
        swRx = Switch(this).apply { text = "دریافت" }
        sw.addView(swRx, lp(0, -2, 1f))
        swAck = Switch(this).apply { text = "ACK خودکار"; isChecked = prefs.getBoolean("autoack", true) }
        sw.addView(swAck, lp(0, -2, 1f))
        root.addView(sw)

        root.addView(TextView(this).apply { text = "دریافت‌شده:" })
        tvRx = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        rxScroll = ScrollView(this).apply { setBackgroundColor(0x11000000); addView(tvRx) }
        root.addView(rxScroll, lp(-1, 0, 1f))

        root.addView(Button(this).apply {
            text = "پاک کردن"
            setOnClickListener { tvRx.text = ""; pending.setLength(0) }
        }, lp())
        setContentView(root)
    }

    private fun pickFile(req: Int) {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "*/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, "انتخاب فایل"), req)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        if (requestCode == REQ_FILE_NORMAL) sendFileNormal(data.data!!)
        if (requestCode == REQ_FILE_RELIABLE) sendFileReliable(data.data!!)
    }

    private fun applySettings() {
        val fr = (etFreq.text.toString().toIntOrNull() ?: 2000).coerceIn(500, 20500)
        val ack = (etAckFreq.text.toString().toIntOrNull() ?: 3000).coerceIn(500, 20500)
        val sp = spSpeed.selectedItemPosition.coerceIn(0, 2)
        etFreq.setText(fr.toString()); etAckFreq.setText(ack.toString())
        prefs.edit().putInt("freq", fr).putInt("ackfreq", ack).putInt("speed", sp)
            .putBoolean("autoack", swAck.isChecked).apply()
        f0 = fr.toDouble(); fAck = ack.toDouble()
        symN = SR * speedMs[sp] / 1000
        rebuildDemods()
    }

    private fun rebuildDemods() {
        demodTx = Demodulator(SR, f0, f0 + Proto.SPACING, symN) { flags, payload ->
            handleIncoming(flags, payload, false)
        }
        demodAck = Demodulator(SR, fAck, fAck + Proto.SPACING, symN) { flags, payload ->
            handleIncoming(flags, payload, true)
        }
    }

    private fun handleIncoming(flags: Int, payload: ByteArray, fromAckChannel: Boolean) {
        if (fromAckChannel) {
            if ((flags and 0x0E) == ReliableProto.TYPE_ACK) {
                val (seq, ok) = ReliableProto.parseAck(payload)
                ackQueue.offer(seq to ok)
            }
            return
        }
        val type = flags and 0x06
        if (ReliableProto.isReliable(flags)) {
            relRx.onFrame(flags, payload,
                sendAck = { seq, ok ->
                    if (swAck.isChecked) sendOneFrame(ReliableProto.TYPE_ACK, ReliableProto.ackPayload(seq, ok), fAck)
                },
                onProgress = { got, total -> ui.post { tvProgress.text = "دریافت مطمئن: $got/$total" } },
                onDone = { f ->
                    ui.post {
                        tvProgress.text = "ذخیره شد: ${f.name}"
                        tvRx.append("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] 📎 ${f.name} (ARQ)\n")
                    }
                }
            )
            return
        }
        if (type != 0) {
            fileRx.onFrame(flags, payload,
                onProgress = { p -> ui.post { tvProgress.text = "دریافت: $p%" } },
                onDone = { f ->
                    ui.post {
                        tvProgress.text = "ذخیره شد: ${f.name}"
                        tvRx.append("[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] 📎 ${f.name}\n")
                    }
                }
            )
            return
        }
        val text = String(payload, Charsets.UTF_8)
        ui.post {
            pending.append(text)
            ui.removeCallbacks(flushRunnable)
            if (flags and 1 == 0) flushPending(false) else ui.postDelayed(flushRunnable, 20000)
        }
    }

    private fun flushPending(partial: Boolean) {
        if (pending.isEmpty()) return
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvRx.append("[$t] $pending${if (partial) " …" else ""}\n\n")
        pending.setLength(0)
        rxScroll.post { rxScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun send() {
        val text = etSend.text.toString().trim()
        if (text.isEmpty() || playing) return
        applySettings()
        val chunks = Proto.split(text)
        val frames = ArrayList<Pair<Int, ByteArray>>()
        chunks.forEachIndexed { i, c ->
            frames.add(Pair(if (i < chunks.size - 1) 1 else 0, c))
        }
        transmitOneShot(frames, "متن", f0)
    }

    private fun sendFileNormal(uri: Uri) {
        if (playing) return
        applySettings()
        val prepared = FileTx.prepare(this, uri)
        if (prepared.isEmpty()) { Toast.makeText(this, "فایل خوانده نشد", Toast.LENGTH_SHORT).show(); return }
        val frames = prepared.map { Pair(it.flags, it.data) }
        transmitOneShot(frames, "فایل", f0)
    }

    private fun sendFileReliable(uri: Uri) {
        if (playing) return
        applySettings()
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val name = queryName(uri) ?: "file.bin"
        ackQueue.clear()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (cbMax.isChecked) am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        tvStatus.text = "ARQ..."
        thread(isDaemon = true) {
            relTx.start(name, bytes)
            ui.post { tvStatus.text = "ARQ پایان" }
        }
    }

    private fun transmitOneShot(frames: List<Pair<Int, ByteArray>>, label: String, freq: Double) {
        val a0 = freq; val a1 = freq + Proto.SPACING; val n = symN
        if (cbMax.isChecked) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }
        tvStatus.text = "ارسال $label..."
        thread(isDaemon = true) {
            try {
                val parts = ArrayList<ShortArray>()
                parts.add(ShortArray(SR / 10))
                val total = frames.size
                frames.forEachIndexed { idx, (flags, payload) ->
                    parts.add(Proto.modulate(Proto.frame(flags, payload), a0, a1, n))
                    parts.add(ShortArray(SR * 15 / 100))
                    ui.post { tvProgress.text = "ارسال $label: ${(idx + 1) * 100 / total}%" }
                }
                val all = ShortArray(parts.sumOf { it.size })
                var p = 0
                for (s in parts) { System.arraycopy(s, 0, all, p, s.size); p += s.size }
                playPcm(all)
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "خطا: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                ui.post { tvStatus.text = "پایان"; tvProgress.text = ""; etSend.setText("") }
            }
        }
    }

    /** پخش یک فریم کوچک (ACK یا پکت ARQ) — تا پایان پخش، گوش نمی‌دهیم */
    private fun sendOneFrame(flags: Int, payload: ByteArray, freq: Double) {
        val n = symN
        val pcm = Proto.modulate(Proto.frame(flags, payload), freq, freq + Proto.SPACING, n)
        playPcm(pcm)
    }

    private fun playPcm(pcm: ShortArray) {
        val old = playing
        playing = true
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(SR, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track.play()
            var w = 0
            while (w < pcm.size) {
                val k = track.write(pcm, w, pcm.size - w)
                if (k <= 0) break
                w += k
            }
            Thread.sleep(pcm.size * 1000L / SR + 100)
            track.stop(); track.release()
        } finally {
            playing = old
        }
    }

    private fun startListening() {
        if (listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            swRx.isChecked = false
            return
        }
        listening = true
        tvStatus.text = "گوش دادن..."
        thread(isDaemon = true) {
            val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = max(minBuf, SR)
            var rec: AudioRecord? = null
            for (src in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                try {
                    val r = AudioRecord(src, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
                } catch (_: Exception) {}
            }
            if (rec == null) {
                listening = false
                ui.post { tvStatus.text = "میکروفون نیست"; swRx.isChecked = false }
                return@thread
            }
            rec.startRecording()
            val buf = ShortArray(2048)
            while (listening) {
                val r = rec.read(buf, 0, buf.size)
                if (r <= 0) continue
                // هنگام پخش، ACK را مصرف نکن (echo)، ولی همچنان feed کن
                demodTx?.feed(buf, r)
                demodAck?.feed(buf, r)
                val lv = max(demodTx?.level ?: 0.0, demodAck?.level ?: 0.0)
                ui.post { pbLevel.progress = min(100, (lv * 3000).toInt()) }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun stopListening() {
        listening = false
        tvStatus.text = "متوقف"
        pbLevel.progress = 0
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (e: Exception) { null }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) swRx.isChecked = true
        else Toast.makeText(this, "دسترسی میکروفون لازم است", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { listening = false; relTx.stop(); super.onDestroy() }
}
