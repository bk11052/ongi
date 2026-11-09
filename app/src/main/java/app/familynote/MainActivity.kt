package app.familynote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.familynote.ui.FamilyNoteRoot
import app.familynote.ui.MainViewModel
import app.familynote.ui.theme.FamilyNoteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as FamilyNoteApp

    private val vm: MainViewModel by viewModels {
        MainViewModel.Factory(app.repository, app.vehicle, app.voice, app.cabin)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.liveActivities++
        app.vehicle.start()
        setContent {
            FamilyNoteTheme {
                FamilyNoteRoot(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForegrounded()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the screen (Home button, another app) must not leave the cabin changed.
        if (!isChangingConfigurations) vm.onBackgrounded()
    }

    override fun onDestroy() {
        app.liveActivities--
        // Keep the vehicle connection across configuration changes. When really leaving,
        // let pending cabin restores finish first, then release the connection.
        if (isFinishing) {
            val app = app
            app.appScope.launch {
                app.cabin.awaitIdle()
                if (app.liveActivities == 0) app.vehicle.stop()
            }
        }
        super.onDestroy()
    }
}
