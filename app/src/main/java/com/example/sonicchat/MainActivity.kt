package com.example.sonicchat

import android.Manifest
import android.app.Activity
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.*
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
    private val SR = Proto.SR
    private val speedMs = intArrayOf(40, 20, 10)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var etFreq: EditText
    private lateinit var spSpeed: Spinner
    private lateinit var etSend: EditText
    private lateinit var tvRx: TextView
    private lateinit var tvStatus: TextView
    private lateinit var pbLevel: ProgressBar
    private lateinit var swRx: Switch
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        prefs = getSharedPreferences("s", MODE_PRIVATE)
        buildUi()
        applySettings()
        swRx.setOnCheckedChangeListener { _, on -> if (on) startListening() else stopListening() }
        swRx.isChecked = true
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        fun lp(w: Int = -1, h: Int = -2, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)

        root.addView(TextView(this).apply { text = "SonicChat — پیام صوتی خارج از شنوایی"; textSize = 18f })
        tvStatus = TextView(this).apply { text = "آماده" }
        root.addView(tvStatus)
        pbLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(pbLevel, lp())

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = "فرکانس پایه (Hz): " })
        etFreq = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("freq", 18000).toString())
        }
        row.addView(etFreq, lp(dp(90), -2))
        spSpeed = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("کند (مطمئن‌تر)", "متوسط", "سریع"))
            setSelection(prefs.getInt("speed", 1))
        }
        row.addView(spSpeed, lp(0, -2, 1f))
        root.addView(row, lp())

        cbMax = CheckBox(this).apply { text = "حداکثر کردن صدا هنگام ارسال"; isChecked = true }
        root.addView(cbMax)

        etSend = EditText(this).apply {
            hint = "متن برای ارسال..."
            minLines = 3; maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP
        }
        root.addView(etSend, lp())

        val btns = LinearLayout(this)
        btns.addView(Button(this).apply { text = "ارسال"; setOnClickListener { send() } }, lp(0, -2, 1f))
        btns.addView(Button(this).apply {
            text = "اعمال تنظیمات"
            setOnClickListener { applySettings(); Toast.makeText(context, "اعمال شد", Toast.LENGTH_SHORT).show() }
        }, lp(0, -2, 1f))
        root.addView(btns, lp())

        swRx = Switch(this).apply { text = "دریافت (گوش دادن با میکروفون)" }
        root.addView(swRx, lp())

        root.addView(TextView(this).apply { text = "دریافت‌شده:" })
        tvRx = TextView(this).apply { textSize = 16f; setTextIsSelectable(true); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        rxScroll = ScrollView(this).apply { setBackgroundColor(0x11000000); addView(tvRx) }
        root.addView(rxScroll, lp(-1, 0, 1f))
        root.addView(Button(this).apply { text = "پاک کردن"; setOnClickListener { tvRx.text = ""; pending.setLength(0) } }, lp())
        setContentView(root)
    }

    private fun applySettings() {
        val fr = (etFreq.text.toString().toIntOrNull() ?: 18000).coerceIn(500, 20500)
        val sp = spSpeed.selectedItemPosition.coerceIn(0, 2)
        etFreq.setText(fr.toString())
        prefs.edit().putInt("freq", fr).putInt("speed", sp).apply()
        f0 = fr.toDouble()
        symN = SR * speedMs[sp] / 1000
        demod = makeDemod()
    }

    private fun makeDemod() = Demodulator(SR, f0, f0 + Proto.SPACING, symN) { flags, payload ->
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
        tvRx.append("[$t] $pending${if (partial) " …(ناقص)" else ""}\n\n")
        pending.setLength(0)
        rxScroll.post { rxScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------- ارسال ----------------
    private fun send() {
        val text = etSend.text.toString().trim()
        if (text.isEmpty() || transmitting) return
        applySettings()
        val chunks = Proto.split(text)
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
                    parts.add(Proto.modulate(Proto.frame(fl, c), a0, a1, n))
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
                    .setBufferSizeInBytes(all.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(all, 0, all.size)
                track.play()
                Thread.sleep(all.size * 1000L / SR + 300)
                track.stop(); track.release()
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "خطا در ارسال: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                transmitting = false
                demod = makeDemod()
                ui.post { tvStatus.text = "ارسال شد ✔"; etSend.setText("") }
            }
        }
    }

    // ---------------- دریافت ----------------
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
                ui.post { pbLevel.progress = min(100, (lv * 3000).toInt()) }
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
        else Toast.makeText(this, "برای دریافت، دسترسی میکروفون لازم است", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { listening = false; super.onDestroy() }
}
