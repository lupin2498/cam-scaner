package com.lupin2498.p2p_meet

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.cloudwebrtc.webrtc.audio.AudioSwitchManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var permissionResult: MethodChannel.Result? = null
    private var audioManagementEnabled = true

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> requestCallPermissions(result)
                "startCallService" -> {
                    val screen = call.argument<Boolean>("screen") ?: false
                    CallService.start(this, screen) { ok -> result.success(ok) }
                }
                "stopCallService" -> {
                    CallService.stop(this)
                    result.success(true)
                }
                "isOtherCallActive" -> {
                    val before = call.argument<Boolean>("beforeOurCall") ?: false
                    result.success(isOtherCallActive(before))
                }
                "setAudioManagement" -> {
                    setAudioManagement(call.argument<Boolean>("enabled") ?: true)
                    result.success(true)
                }
                else -> result.notImplemented()
            }
        }
    }

    // ---------------- Quyền ----------------

    private fun requestCallPermissions(result: MethodChannel.Result) {
        val wanted = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            result.success(grantedList())
            return
        }
        permissionResult?.success(grantedList())
        permissionResult = result
        requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    private fun grantedList(): List<String> {
        val out = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) out.add("camera")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) out.add("microphone")
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) out.add("notification")
        return out
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            permissionResult?.success(grantedList())
            permissionResult = null
        }
    }

    // ---------------- Cuộc gọi khác & âm thanh ----------------

    /**
     * Phát hiện cuộc gọi khác (điện thoại thường hoặc VoIP như Zalo/Messenger) mà không cần
     * quyền READ_PHONE_STATE: dựa vào chế độ âm thanh hệ thống và các phiên ghi âm đang chạy.
     */
    private fun isOtherCallActive(beforeOurCall: Boolean): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val mode = am.mode
        val telephonyModes = mutableSetOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_RINGTONE)
        if (Build.VERSION.SDK_INT >= 30) telephonyModes.add(AudioManager.MODE_CALL_SCREENING)
        if (Build.VERSION.SDK_INT >= 33) telephonyModes.add(AudioManager.MODE_CALL_REDIRECT)
        if (mode in telephonyModes) return true
        if (!beforeOurCall) return false
        if (mode == AudioManager.MODE_IN_COMMUNICATION) return true
        if (Build.VERSION.SDK_INT >= 33 && mode == AudioManager.MODE_COMMUNICATION_REDIRECT) return true
        return try {
            am.activeRecordingConfigurations.any {
                it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                    it.clientAudioSource == MediaRecorder.AudioSource.VOICE_CALL
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Khi đang có cuộc gọi khác, P2P Meet không đổi chế độ âm thanh / không giành audio focus
     * để không làm hỏng cuộc gọi đó. Khi cuộc gọi kia kết thúc thì lấy lại chế độ hội thoại.
     */
    private fun setAudioManagement(enabled: Boolean) {
        audioManagementEnabled = enabled
        AudioSwitchManager.setAudioSessionManagementEnabled(enabled)
        if (!enabled || !CallService.running) return
        Handler(Looper.getMainLooper()).post {
            try {
                AudioSwitchManager.instance?.start()
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                if (am.mode == AudioManager.MODE_NORMAL) am.mode = AudioManager.MODE_IN_COMMUNICATION
            } catch (e: Exception) {
                // Bỏ qua: chỉ là cố gắng khôi phục chế độ âm thanh.
            }
        }
    }

    companion object {
        private const val CHANNEL = "p2pmeet/native"
        private const val PERMISSION_REQUEST = 7301
    }
}
