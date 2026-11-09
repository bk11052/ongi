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
        // The service keeps watching the gear after this screen closes.
        ArrivalService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            vm.openGallery(auto = true)
        }
    }

    override fun onStart() {
        super.onStart()
        app.appVisible = true
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
