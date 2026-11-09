package app.familynote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import app.familynote.arrival.ArrivalService
import app.familynote.ui.FamilyNoteRoot
import app.familynote.ui.MainViewModel
import app.familynote.ui.theme.FamilyNoteTheme
import app.familynote.vehicle.DriveState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as FamilyNoteApp

    private val vm: MainViewModel by viewModels {
        MainViewModel.Factory(app.repository, app.vehicle, app.voice, app.cabin, app.settings, app.arrivalMonitor.arrival)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.vehicle.start()
        app.arrivalMonitor.start()
        // The service keeps watching the gear after this screen closes.
        ArrivalService.start(this)
        // Ask for the notification permission only while parked (a system dialog is touchable).
        lifecycleScope.launch {
            vm.drive.first { it == DriveState.PARKED }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        handle(intent)
        setContent {
            FamilyNoteTheme {
                FamilyNoteRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Opened from the arrival notification: go straight to the notes. */
    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_GALLERY, false) == true) {
            intent.removeExtra(EXTRA_OPEN_GALLERY)
            // On a cold start notes and the gear state are still loading; the ViewModel waits.
            vm.openGalleryWhenReady()
        }
    }

    override fun onStart() {
        super.onStart()
        app.appVisible = true
        app.vehicle.start() // retries if an earlier connection attempt gave up
        app.notifier.clear()
        vm.onForegrounded()
    }

    override fun onStop() {
        super.onStop()
        app.appVisible = false
        // Leaving the screen (Home button, another app) must not leave the cabin changed.
        if (!isChangingConfigurations) vm.onBackgrounded()
    }

    companion object {
        const val EXTRA_OPEN_GALLERY = "open_gallery"
    }
}
