package com.lupin2498.p2p_meet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Dịch vụ nền giữ cuộc họp hoạt động khi người dùng chuyển sang ứng dụng khác
 * (micro, camera) và bắt buộc phải có khi chia sẻ màn hình (Android 10+/14+).
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val screen = intent?.getBooleanExtra(EXTRA_SCREEN, false) ?: false
        var ok = startInForeground(screen)
        if (!ok && screen) {
            // Thử lại không kèm loại mediaProjection để ít nhất giữ được cuộc gọi.
            startInForeground(false)
            ok = false
        }
        running = true
        val cb = pending
        pending = null
        if (cb != null) mainHandler.post { cb(ok) }
        return START_NOT_STICKY
    }

    private fun startInForeground(screen: Boolean): Boolean {
        return try {
            val notification = buildNotification(screen)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, serviceTypes(screen))
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground thất bại (screen=$screen)", e)
            false
        }
    }

    private fun serviceTypes(screen: Boolean): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= 30) {
            if (granted(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (screen) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return type
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(screen: Boolean): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Cuộc họp đang diễn ra", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Hiển thị khi đang trong cuộc họp P2P Meet"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pi = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        builder
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle(if (screen) "Đang chia sẻ màn hình" else "Đang trong cuộc họp P2P Meet")
            .setContentText(if (screen) "Mọi nội dung trên màn hình đang được gửi tới người tham gia" else "Chạm để quay lại cuộc họp")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
        if (pi != null) builder.setContentIntent(pi)
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "P2PMeetCallService"
        private const val CHANNEL_ID = "p2pmeet_call"
        private const val NOTIFICATION_ID = 4711
        private const val EXTRA_SCREEN = "screen"
        private val mainHandler = Handler(Looper.getMainLooper())
        private var pending: ((Boolean) -> Unit)? = null

        @Volatile
        var running = false
            private set

        fun start(context: Context, screen: Boolean, callback: (Boolean) -> Unit) {
            pending?.invoke(false)
            pending = callback
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_SCREEN, screen)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Không khởi động được dịch vụ", e)
                pending = null
                callback(false)
                return
            }
            // Dự phòng nếu onStartCommand không được gọi.
            mainHandler.postDelayed({
                val cb = pending
                if (cb === callback) {
                    pending = null
                    cb(running)
                }
            }, 3000)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
            running = false
        }
    }
}
