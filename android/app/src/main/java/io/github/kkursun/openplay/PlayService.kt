package io.github.kkursun.openplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import io.github.kkursun.openplay.phone.Phone
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the phone streaming to the TV while the app is in the background or the screen is off, with a
 * notification to stop it. */
class PlayService : Service() {
    private val scope = MainScope()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            Phone.stop()
            return START_NOT_STICKY
        }
        val mirror = intent?.action == MIRROR
        startForeground(1, notification(if (mirror) "Mirroring this phone" else "Playing on the TV"),
            if (mirror) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openplay:stream").apply { acquire(12 * 3600_000L) } // longer than any film
            wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "openplay").apply { acquire() }
        }
        if (mirror) {
            val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(DATA)
            val projection = try {
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra(CODE, 0), data!!)
            } catch (e: RuntimeException) {
                null.also { Phone.fail("Android didn't allow sharing the screen: ${e.message}") }
            }
            if (projection == null) { // the permission was already used or taken back
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // Android 14 wants this before the screen is captured.
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = Phone.ended(projection)
            }, Handler(mainLooper))
            Phone.start("mirror", projection, intent.getBooleanExtra(SOUND, false))
        } else {
            Phone.start("media")
        }
        if (!watching) {
            watching = true
            scope.launch {
                Phone.state.collect { st ->
                    if (st.status != "starting" && st.status != "live" && !Phone.busy) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("stream", "Playing to a TV", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlayService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "stream")
            .setSmallIcon(R.drawable.ic_stream)
            .setContentTitle("openplay")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.release()
        wifi?.release()
        super.onDestroy()
    }

    companion object {
        private const val MIRROR = "mirror"
        private const val PLAY = "play"
        private const val STOP = "stop"
        private const val CODE = "code"
        private const val DATA = "data"
        private const val SOUND = "sound"

        /** Mirrors the screen, given the result of the screen-sharing prompt. */
        fun mirror(context: Context, code: Int, data: Intent, sound: Boolean) = context.startForegroundService(
            Intent(context, PlayService::class.java).setAction(MIRROR).putExtra(CODE, code).putExtra(DATA, data).putExtra(SOUND, sound))

        /** Plays the video in the phone's settings. */
        fun play(context: Context) = context.startForegroundService(Intent(context, PlayService::class.java).setAction(PLAY))
    }
}
