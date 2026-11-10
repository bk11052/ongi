package app.familynote.arrival

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import app.familynote.FamilyNoteApp

/**
 * Keeps the gear subscription alive while no screen is open, so arriving in P is noticed
 * even when navigation or another app is in front.
 */
class ArrivalService : Service() {

    override fun onCreate() {
        super.onCreate()
        val app = application as FamilyNoteApp
        val notification = app.notifier.watchingNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(AndroidArrivalNotifier.ID_WATCH, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(AndroidArrivalNotifier.ID_WATCH, notification)
        }
        app.vehicle.start()
        app.arrivalMonitor.start()
        // The process may come back without the screen (crash restart, head unit reboot):
        // put back any light or climate change left over, unless the screen is in use.
        app.cabin.run { if (!app.appVisible) runCatching { app.vehicle.recoverPendingCabin() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Each start is a chance to reconnect if the vehicle service was not ready before.
        (application as FamilyNoteApp).vehicle.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ArrivalService::class.java))
            }.onFailure { Log.w("ArrivalService", "could not start", it) }
        }
    }
}

/** Starts watching after the car (head unit) boots. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ArrivalService.start(context)
    }
}
