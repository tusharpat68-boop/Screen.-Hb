package com.example.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var btn: Button
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var cbSys: CheckBox
    private lateinit var cbMic: CheckBox
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            refreshUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "স্ক্রিন রেকর্ডার"
            textSize = 28f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad / 2)
        }
        timer = TextView(this).apply {
            textSize = 40f
            setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad)
        }
        btn = Button(this).apply {
            textSize = 20f
            setOnClickListener { onButton() }
        }
        val hint = TextView(this).apply {
            text = "রেকর্ড শুরু হলে অ্যাপ নিজে থেকে পেছনে চলে যাবে। তখন ব্রাউজার/ভিডিও খুলুন। থামাতে নোটিফিকেশনের \"বন্ধ করুন\" চাপুন।\n\nভিডিও সেভ হবে: Movies/ScreenRecorder\n\nটিপ: ভেতরের সাউন্ড ও মাইক একসাথে নিলে ইয়ারফোন লাগান, নইলে ইকো হতে পারে।"
            textSize = 13f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        root.addView(title)
        root.addView(status)
        cbSys = CheckBox(this).apply {
            text = "ফোনের ভেতরের সাউন্ড (ভিডিও/মিউজিক)"
            isChecked = true
            textSize = 16f
        }
        cbMic = CheckBox(this).apply {
            text = "মাইক (আমার ভয়েস)"
            isChecked = true
            textSize = 16f
        }
        root.addView(timer)
        root.addView(cbSys)
        root.addView(cbMic)
        root.addView(btn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (64 * resources.displayMetrics.density).toInt()))
        root.addView(hint)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun refreshUi() {
        if (RecorderService.recording) {
            btn.text = "■  রেকর্ড বন্ধ করুন"
            cbSys.isEnabled = false
            cbMic.isEnabled = false
            status.text = "রেকর্ডিং চলছে"
            val s = (System.currentTimeMillis() - RecorderService.startTime) / 1000
            timer.text = "%02d:%02d".format(s / 60, s % 60)
        } else {
            btn.text = "●  রেকর্ড শুরু করুন"
            cbSys.isEnabled = true
            cbMic.isEnabled = true
            status.text = "প্রস্তুত"
            timer.text = "00:00"
        }
    }

    private fun onButton() {
        if (RecorderService.recording) {
            startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
        } else {
            val needed = mutableListOf<String>()
            if ((cbSys.isChecked || cbMic.isChecked) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.RECORD_AUDIO
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed += Manifest.permission.POST_NOTIFICATIONS
            if (needed.isEmpty()) requestCapture() else requestPermissions(needed.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) {
            val needAudio = cbSys.isChecked || cbMic.isChecked
            if (!needAudio || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                requestCapture()
            else
                Toast.makeText(this, "ভয়েস রেকর্ড করতে মাইক্রোফোনের অনুমতি দরকার", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, RecorderService::class.java)
                    .setAction(RecorderService.ACTION_START)
                    .putExtra(RecorderService.EXTRA_CODE, resultCode)
                    .putExtra(RecorderService.EXTRA_DATA, data)
                    .putExtra(RecorderService.EXTRA_SYS, cbSys.isChecked)
                    .putExtra(RecorderService.EXTRA_MIC, cbMic.isChecked)
                startForegroundService(i)
                moveTaskToBack(true) // ব্রাউজার/ভিডিও খোলার জন্য অ্যাপ পেছনে পাঠানো
            } else {
                Toast.makeText(this, "রেকর্ডিং বাতিল হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_CAPTURE = 2
    }
}
