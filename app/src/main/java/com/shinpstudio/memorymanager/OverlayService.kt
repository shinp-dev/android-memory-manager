package com.shinpstudio.memorymanager

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView

class OverlayService : Service() {
    companion object { @Volatile var running = false; const val STOP = "stop_overlay" }
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val tick = object : Runnable { override fun run() {
        if (!Settings.canDrawOverlays(this@OverlayService)) { stopSelf(); return }
        val r = ram(this@OverlayService)
        view?.let { v ->
            v.text = "RAM ${r.percent}%${if (r.low) " !" else ""}"
            val x=params.x.coerceIn(0,(resources.displayMetrics.widthPixels-v.width).coerceAtLeast(0))
            val y=params.y.coerceIn(0,(resources.displayMetrics.heightPixels-v.height).coerceAtLeast(0))
            if(x!=params.x||y!=params.y){params.x=x;params.y=y;runCatching{wm.updateViewLayout(v,params)}}
        }
        handler.postDelayed(this, 3000)
    } }
    override fun onBind(intent: Intent?) : IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (view != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "RAMオーバーレイ", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(this, 2, Intent(this, OverlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(42, Notification.Builder(this, "overlay").setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("RAMモニター ON").setContentText("タップで一覧を開く").setContentIntent(open).addAction(Notification.Action.Builder(null, "OFF", off).build()).setOngoing(true).build())
        wm = getSystemService(WindowManager::class.java)
        val prefs = getSharedPreferences("overlay", MODE_PRIVATE)
        params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = prefs.getInt("x", dp(8)).coerceIn(0,(resources.displayMetrics.widthPixels-dp(110)).coerceAtLeast(0)); y = prefs.getInt("y", dp(100)).coerceIn(0,(resources.displayMetrics.heightPixels-dp(60)).coerceAtLeast(0)) }
        val v = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(10),dp(7),dp(10),dp(7)); background = GradientDrawable().apply { setColor(0xE8223332.toInt()); cornerRadius = dp(16).toFloat() }; contentDescription = "RAM使用率。タップで一覧、長押しでOFF、ドラッグで移動" }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false; var down = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=params.x; oy=params.y; moved=false; down=SystemClock.uptimeMillis(); true }
                MotionEvent.ACTION_MOVE -> { val dx=e.rawX-sx; val dy=e.rawY-sy; if (kotlin.math.abs(dx)>dp(5)||kotlin.math.abs(dy)>dp(5)) moved=true; if(moved){params.x=(ox+dx.toInt()).coerceIn(0,(resources.displayMetrics.widthPixels-v.width).coerceAtLeast(0));params.y=(oy+dy.toInt()).coerceIn(0,(resources.displayMetrics.heightPixels-v.height).coerceAtLeast(0));runCatching{wm.updateViewLayout(v,params)}};true }
                MotionEvent.ACTION_UP -> { if(moved) prefs.edit().putInt("x",params.x).putInt("y",params.y).apply() else if(SystemClock.uptimeMillis()-down>650) stopSelf() else { v.performClick(); runCatching { startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } }; true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try { wm.addView(v,params); view=v; running=true; handler.post(tick) } catch (e: Exception) { stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); view?.let { runCatching { wm.removeView(it) } }; view=null; running=false; stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
