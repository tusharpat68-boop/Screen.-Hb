package com.example.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ভিডিও: VirtualDisplay -> H.264 (MediaCodec)
 * অডিও: ফোনের ভেতরের সাউন্ড (AudioPlaybackCapture) + মাইক -> মিক্স -> AAC (MediaCodec)
 * দুটো MediaMuxer দিয়ে একটা MP4 ফাইলে জোড়া লাগে।
 */
class RecorderService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var muxer: MediaMuxer? = null
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var micRec: AudioRecord? = null
    private var sysRec: AudioRecord? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var muxStarted = false
    private val muxLock = Any()
    private var tracksAdded = 0
    private var tracksExpected = 1
    private var baseUs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
            ACTION_START -> {
                startAsForeground() // Android 14: আগে foreground service, তারপর MediaProjection
                if (recording) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                val wantSys = intent.getBooleanExtra(EXTRA_SYS, true)
                val wantMic = intent.getBooleanExtra(EXTRA_MIC, true)
                if (data == null || !startRecording(code, data, wantSys, wantMic)) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "স্ক্রিন রেকর্ডিং", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "■ বন্ধ করুন", stopPi
        ).build()
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("স্ক্রিন রেকর্ডিং চলছে")
            .setContentText("থামাতে \"বন্ধ করুন\" চাপুন")
            .setContentIntent(openPi)
            .addAction(stopAction)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        startForeground(NOTIF_ID, n, type)
    }

    // ---------------------------------------------------------------- শুরু

    @Suppress("DEPRECATION")
    private fun startRecording(code: Int, data: Intent, wantSys: Boolean, wantMic: Boolean): Boolean {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mpm.getMediaProjection(code, data)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    handler.post { stopRecording(); stopSelf() }
                }
            }, handler)

            // স্ক্রিনের আসল সাইজ; ছোট দিক সর্বোচ্চ 1080, আর সংখ্যা জোড়
            val dm = DisplayMetrics()
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
            val scale = minOf(1f, 1080f / minOf(dm.widthPixels, dm.heightPixels))
            val w = (dm.widthPixels * scale).toInt() / 2 * 2
            val h = (dm.heightPixels * scale).toInt() / 2 * 2

            // আউটপুট ফাইল (MediaStore)
            val name = "Screen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("ফাইল তৈরি করা যায়নি")
            uri = u
            val p = contentResolver.openFileDescriptor(u, "rw")
                ?: throw IllegalStateException("ফাইল খোলা যায়নি")
            pfd = p
            muxer = MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ভিডিও এনকোডার
            val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val ve = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            ve.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = ve.createInputSurface()
            ve.start()
            vEnc = ve

            // অডিও ইনপুট: ভেতরের সাউন্ড ও/বা মাইক
            val hasPerm = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (wantMic && hasPerm) {
                try {
                    val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val r = AudioRecord(
                        MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAMES * 2) * 4
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) micRec = r else r.release()
                } catch (_: Exception) {}
            }
            if (wantSys && hasPerm) {
                try {
                    val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val r = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(cfg)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes(FRAMES * 4 * 8)
                        .build()
                    if (r.state == AudioRecord.STATE_INITIALIZED) sysRec = r else r.release()
                } catch (_: Exception) {}
            }
            if (wantSys && sysRec == null)
                Toast.makeText(this, "ফোনের ভেতরের সাউন্ড চালু করা যায়নি", Toast.LENGTH_LONG).show()

            val hasAudio = micRec != null || sysRec != null
            tracksExpected = if (hasAudio) 2 else 1
            tracksAdded = 0
            muxStarted = false

            if (hasAudio) {
                val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val ae = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                ae.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                ae.start()
                aEnc = ae
            }

            baseUs = System.nanoTime() / 1000
            display = proj.createVirtualDisplay(
                "ScreenRecorder", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )

            running = true
            recording = true
            startTime = System.currentTimeMillis()
            vThread = Thread { videoLoop() }.also { it.start() }
            if (hasAudio) aThread = Thread { audioLoop() }.also { it.start() }
            return true
        } catch (e: Exception) {
            Toast.makeText(this, "রেকর্ড শুরু করা যায়নি: ${e.message}", Toast.LENGTH_LONG).show()
            running = false
            cleanup(deleteFile = true)
            return false
        }
    }

    // ---------------------------------------------------------------- muxer

    private fun addTrack(f: MediaFormat): Int = synchronized(muxLock) {
        val t = muxer!!.addTrack(f)
        tracksAdded++
        if (tracksAdded == tracksExpected) {
            muxer!!.start()
            muxStarted = true
        }
        t
    }

    private fun writeSample(track: Int, buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxLock) {
            if (muxStarted) {
                try { muxer?.writeSampleData(track, buf, info) } catch (_: Exception) {}
            }
        }
    }

    private fun awaitMuxer() {
        while (!muxStarted && running) Thread.sleep(10)
    }

    // ---------------------------------------------------------------- ভিডিও থ্রেড

    private fun videoLoop() {
        val enc = vEnc ?: return
        val info = MediaCodec.BufferInfo()
        var track = -1
        var lastPts = 0L
        try {
            while (true) {
                val idx = enc.dequeueOutputBuffer(info, 10_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = addTrack(enc.outputFormat)
                    awaitMuxer()
                } else if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (buf != null && info.size > 0 && track >= 0 && muxStarted) {
                        val pts = maxOf(info.presentationTimeUs - baseUs, lastPts)
                        lastPts = pts
                        info.presentationTimeUs = pts
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        writeSample(track, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- অডিও থ্রেড

    private fun audioLoop() {
        val enc = aEnc ?: return
        val mic = micRec
        val sys = sysRec
        val info = MediaCodec.BufferInfo()
        val micBuf = ShortArray(FRAMES)
        val sysBuf = ShortArray(FRAMES * 2)
        val out = ShortArray(FRAMES * 2)
        var track = -1
        var samples = 0L
        var lastPts = 0L
        var eosSent = false
        var done = false
        try {
            mic?.startRecording()
            sys?.startRecording()
            val startOffsetUs = System.nanoTime() / 1000 - baseUs

            while (!done) {
                if (!eosSent) {
                    val micN = if (mic != null) maxOf(0, mic.read(micBuf, 0, FRAMES)) else 0
                    val sysN = if (sys != null) maxOf(0, sys.read(sysBuf, 0, FRAMES * 2)) else 0
                    val got = micN > 0 || sysN > 0
                    val last = !running
                    if (got) {
                        for (i in 0 until FRAMES) {
                            var l = 0
                            var r = 0
                            if (i * 2 + 1 < sysN) {
                                l = sysBuf[i * 2].toInt()
                                r = sysBuf[i * 2 + 1].toInt()
                            }
                            if (i < micN) {
                                val m = micBuf[i].toInt()
                                l += m
                                r += m
                            }
                            out[i * 2] = l.coerceIn(-32768, 32767).toShort()
                            out[i * 2 + 1] = r.coerceIn(-32768, 32767).toShort()
                        }
                    }
                    if (got || last) {
                        val inIdx = enc.dequeueInputBuffer(100_000)
                        if (inIdx >= 0) {
                            val ib = enc.getInputBuffer(inIdx)!!
                            ib.clear()
                            if (got) ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out)
                            val pts = startOffsetUs + samples * 1_000_000L / RATE
                            enc.queueInputBuffer(
                                inIdx, 0, if (got) FRAMES * 4 else 0, pts,
                                if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            if (got) samples += FRAMES
                            if (last) eosSent = true
                        }
                    } else {
                        Thread.sleep(20)
                    }
                }

                // এনকোড হওয়া ডেটা muxer-এ পাঠানো
                while (true) {
                    val idx = enc.dequeueOutputBuffer(info, if (eosSent) 10_000 else 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = addTrack(enc.outputFormat)
                        awaitMuxer()
                    } else if (idx >= 0) {
                        val buf = enc.getOutputBuffer(idx)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                        if (buf != null && info.size > 0 && track >= 0 && muxStarted) {
                            val pts = maxOf(info.presentationTimeUs, lastPts)
                            lastPts = pts
                            info.presentationTimeUs = pts
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            writeSample(track, buf, info)
                        }
                        enc.releaseOutputBuffer(idx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            done = true
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { mic?.stop() } catch (_: Exception) {}
            try { sys?.stop() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- বন্ধ

    private fun stopRecording() {
        if (!recording) return
        recording = false
        running = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { vEnc?.signalEndOfInputStream() } catch (_: Exception) {}
        try { vThread?.join(3000) } catch (_: Exception) {}
        try { aThread?.join(3000) } catch (_: Exception) {}

        var ok = muxStarted
        try {
            synchronized(muxLock) { muxer?.stop() }
        } catch (e: Exception) {
            ok = false // খুব ছোট রেকর্ডিং হলে stop() ব্যর্থ হয়
        }
        cleanup(deleteFile = !ok)
        Toast.makeText(
            this,
            if (ok) "ভিডিও সেভ হয়েছে: Movies/ScreenRecorder" else "রেকর্ডিং খুব ছোট, সেভ হয়নি",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun cleanup(deleteFile: Boolean) {
        try { display?.release() } catch (_: Exception) {}
        try { vEnc?.stop() } catch (_: Exception) {}
        try { vEnc?.release() } catch (_: Exception) {}
        try { aEnc?.stop() } catch (_: Exception) {}
        try { aEnc?.release() } catch (_: Exception) {}
        try { micRec?.release() } catch (_: Exception) {}
        try { sysRec?.release() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; vEnc = null; aEnc = null; micRec = null; sysRec = null
        muxer = null; pfd = null; projection = null; vThread = null; aThread = null
        uri?.let { u ->
            try {
                if (deleteFile) contentResolver.delete(u, null, null)
                else contentResolver.update(u, ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }, null, null)
            } catch (_: Exception) {}
        }
        uri = null
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.screenrec.START"
        const val ACTION_STOP = "com.example.screenrec.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SYS = "sys"
        const val EXTRA_MIC = "mic"
        private const val CHANNEL = "rec"
        private const val NOTIF_ID = 1
        private const val RATE = 44100
        private const val FRAMES = 1024

        @Volatile var recording = false
        @Volatile var startTime = 0L
    }
}
