package com.example.sonicchat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
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
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    companion object {
        @Volatile private var instance: MainActivity? = null
        fun postRx(text: String, partial: Boolean) {
            instance?.ui?.post { instance?.appendRx(text, partial) }
        }
    }

    private val SR = Proto.SR
    private val speedMs = intArrayOf(40, 20, 10)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var etFreq: EditText
    private lateinit var spSpeed: Spinner
    private lateinit var etSend: EditText
    private lateinit var etKey: EditText
    private lateinit var tvRx: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pbLevel: ProgressBar
    private lateinit var tvSNR: TextView
    private lateinit var swRx: Switch
    private lateinit var swBg: Switch
    private lateinit var cbMax: CheckBox
    private lateinit var rxScroll: ScrollView

    @Volatile private var demod: Demodulator? = null
    @Volatile private var transmitting = false
    @Volatile private var listening = false
    private val pending = StringBuilder()
    private val flushRunnable = Runnable { flushPending(true) }

    private var f0 = 18000.0
    private var symN = SR * 20 / 1000

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        prefs = getSharedPreferences("s", MODE_PRIVATE)
        buildUi()
        applySettings()
        swRx.setOnCheckedChangeListener { _, on -> if (on) startListening() else stopListening() }
        swRx.isChecked = true
        loadHistory()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        fun lp(w: Int = -1, h: Int = -2, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

        root.addView(TextView(this).apply { text = "SonicChat — نسخه ۲"; textSize = 18f })
        tvStatus = TextView(this).apply { text = "آماده" }
        root.addView(tvStatus)
        pbLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(pbLevel, lp())
        tvSNR = TextView(this).apply { text = "سطح: -" }
        root.addView(tvSNR)

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "فرکانس: " })
        etFreq = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("freq", 18000).toString())
        }
        row.addView(etFreq, lp(dp(90), -2))
        spSpeed = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("کند", "متوسط", "سریع"))
            setSelection(prefs.getInt("speed", 1))
        }
        row.addView(spSpeed, lp(0, -2, 1f))
        root.addView(row, lp())

        val keyRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        keyRow.addView(TextView(this).apply { text = "کلید رمز: " })
        etKey = EditText(this).apply {
            hint = "خالی = بدون رمز"
            setText(prefs.getString("key", ""))
        }
        keyRow.addView(etKey, lp(0, -2, 1f))
        root.addView(keyRow, lp())

        cbMax = CheckBox(this).apply { text = "حداکثر کردن صدا هنگام ارسال"; isChecked = true }
        root.addView(cbMax)

        etSend = EditText(this).apply {
            hint = "متن برای ارسال..."
            minLines = 2; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
        }
        root.addView(etSend, lp())

        val btns = LinearLayout(this)
        btns.addView(Button(this).apply { text = "ارسال"; setOnClickListener { send() } }, lp(0, -2, 1f))
        btns.addView(Button(this).apply {
            text = "اعمال"
            setOnClickListener { applySettings(); Toast.makeText(context, "اعمال شد", Toast.LENGTH_SHORT).show() }
        }, lp(0, -2, 1f))
        root.addView(btns, lp())

        val switches = LinearLayout(this)
        swRx = Switch(this).apply { text = "دریافت" }
        switches.addView(swRx, lp(0, -2, 1f))
        swBg = Switch(this).apply {
            text = "پس‌زمینه"
            setOnCheckedChangeListener { _, on ->
                val i = Intent(this@MainActivity, RxService::class.java)
                if (on) {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                } else {
                    i.action = RxService.ACTION_STOP
                    startService(i)
                }
            }
        }
        switches.addView(swBg, lp(0, -2, 1f))
        root.addView(switches)

        root.addView(TextView(this).apply { text = "دریافت‌شده / تاریخچه:" })
        tvRx = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        rxScroll = ScrollView(this).apply { setBackgroundColor(0x11000000); addView(tvRx) }
        root.addView(rxScroll, lp(-1, 0, 1f))

        val bottom = LinearLayout(this)
        bottom.addView(Button(this).apply {
            text = "پاک کردن نمایش"
            setOnClickListener { tvRx.text = ""; pending.setLength(0) }
        }, lp(0, -2, 1f))
        bottom.addView(Button(this).apply {
            text = "پاک کردن تاریخچه"
            setOnClickListener { History.clear(this@MainActivity); tvRx.text = "" }
        }, lp(0, -2, 1f))
        root.addView(bottom)

        setContentView(root)
    }

    private fun applySettings() {
        val fr = (etFreq.text.toString().toIntOrNull() ?: 18000).coerceIn(500, 20500)
        val sp = spSpeed.selectedItemPosition.coerceIn(0, 2)
        etFreq.setText(fr.toString())
        prefs.edit().putInt("freq", fr).putInt("speed", sp).putString("key", etKey.text.toString()).apply()
        f0 = fr.toDouble()
        symN = SR * speedMs[sp] / 1000
        demod = makeDemod()
    }

    private fun makeDemod() = Demodulator(SR, f0, f0 + Proto.SPACING, symN) { flags, payload ->
        val decrypted = try {
            String(Crypto.decrypt(payload, prefs.getString("key", "") ?: ""), Charsets.UTF_8)
        } catch (e: Exception) {
            "[رمزگشایی ناموفق] " + String(payload, Charsets.UTF_8)
        }
        ui.post { appendRx(decrypted, flags and 1 != 0) }
    }

    private fun appendRx(text: String, partial: Boolean) {
        pending.append(text)
        ui.removeCallbacks(flushRunnable)
        if (!partial) flushPending(false) else ui.postDelayed(flushRunnable, 20000)
    }

    private fun flushPending(partial: Boolean) {
        if (pending.isEmpty()) return
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$t] $pending${if (partial) " …(ناقص)" else ""}\n\n"
        tvRx.append(line)
        History.add(this, HistItem(System.currentTimeMillis(), "rx", pending.toString(), partial))
        pending.setLength(0)
        rxScroll.post { rxScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun loadHistory() {
        val h = History.load(this)
        h.takeLast(50).forEach {
            val t = SimpleDateFormat("HH:mm", Locale.US).format(Date(it.time))
            val d = if (it.dir == "tx") "→" else "←"
            tvRx.append("$d [$t] ${it.text}${if (it.partial) " …" else ""}\n")
        }
    }

    private fun send() {
        val text = etSend.text.toString().trim()
        if (text.isEmpty() || transmitting) return
        applySettings()
        val chunks = Proto.split(text)
        val key = etKey.text.toString()
        val a0 = f0; val a1 = f0 + Proto.SPACING; val n = symN
        if (cbMax.isChecked) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }
        transmitting = true
        tvStatus.text = "در حال ارسال..."
        thread(isDaemon = true) {
            try {
                val parts = ArrayList<ShortArray>()
                parts.add(ShortArray(SR / 10))
                chunks.forEachIndexed { i, c ->
                    val fl = if (i < chunks.size - 1) 1 else 0
                    val enc = Crypto.encrypt(c, key)
                    val fec = Proto.fecEncode(enc)
                    parts.add(Proto.modulate(Proto.frame(fl, fec), a0, a1, n))
                    parts.add(ShortArray(SR * 15 / 100))
                }
                val all = ShortArray(parts.sumOf { it.size })
                var p = 0
                for (s in parts) { System.arraycopy(s, 0, all, p, s.size); p += s.size }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(max(SR, all.size * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track.play()
                var written = 0
                while (written < all.size) {
                    val w = track.write(all, written, all.size - written)
                    if (w <= 0) break
                    written += w
                }
                Thread.sleep(300)
                track.stop(); track.release()
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "خطا: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                transmitting = false
                demod = makeDemod()
                ui.post {
                    tvStatus.text = "ارسال شد ✔"
                    History.add(this, HistItem(System.currentTimeMillis(), "tx", text, false))
                    etSend.setText("")
                }
            }
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
        tvStatus.text = "در حال گوش دادن..."
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
                ui.post { tvStatus.text = "میکروفون در دسترس نیست"; swRx.isChecked = false }
                return@thread
            }
            rec.startRecording()
            val buf = ShortArray(2048)
            while (listening) {
                val r = rec.read(buf, 0, buf.size)
                if (r <= 0) continue
                val d = demod ?: continue
                if (transmitting) continue
                d.feed(buf, r)
                val lv = d.level
                val snr = d.snr
                ui.post {
                    pbLevel.progress = min(100, (lv * 3000).toInt())
                    tvSNR.text = "سطح: %.1f dB".format(snr)
                }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun stopListening() {
        listening = false
        tvStatus.text = "گوش دادن متوقف شد"
        pbLevel.progress = 0
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) swRx.isChecked = true
        else Toast.makeText(this, "دسترسی میکروفون لازم است", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { listening = false; instance = null; super.onDestroy() }
}
