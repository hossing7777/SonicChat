package com.example.sonicchat

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import kotlin.math.max
import kotlin.concurrent.thread

class RxService : Service() {
    companion object {
        const val CHANNEL = "sonicchat_rx"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        @Volatile var running = false
    }

    private var rec: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var listening = false
    private var demod: Demodulator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "SonicChat Receive", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun notif(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("SonicChat")
            .setContentText("در حال گوش دادن در پس‌زمینه...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true
        startForeground(1, notif())
        val prefs = getSharedPreferences("s", Context.MODE_PRIVATE)
        val f0 = prefs.getInt("freq", 18000).toDouble()
        val speed = prefs.getInt("speed", 1)
        val symN = Proto.SR * intArrayOf(40, 20, 10)[speed] / 1000
        demod = Demodulator(Proto.SR, f0, f0 + Proto.SPACING, symN) { flags2, payload ->
            val txt = try { String(Crypto.decrypt(payload, ""), Charsets.UTF_8) } catch (e: Exception) {
                String(payload, Charsets.UTF_8)
            }
            History.add(this, HistItem(System.currentTimeMillis(), "rx", txt, flags2 and 1 != 0))
            MainActivity.postRx(txt, flags2 and 1 != 0)
        }
        startListen()
        return START_STICKY
    }

    private fun startListen() {
        listening = true
        worker = thread(isDaemon = true) {
            val sr = Proto.SR
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = max(minBuf, sr)
            for (src in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                try {
                    val r = AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
                    if (r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break } else r.release()
                } catch (_: Exception) {}
            }
            val r = rec ?: return@thread
            r.startRecording()
            val buf = ShortArray(2048)
            while (listening) {
                val got = r.read(buf, 0, buf.size)
                if (got <= 0) continue
                demod?.feed(buf, got)
            }
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
    }

    override fun onDestroy() {
        listening = false; running = false
        try { worker?.join(500) } catch (_: Exception) {}
        super.onDestroy()
    }
}
