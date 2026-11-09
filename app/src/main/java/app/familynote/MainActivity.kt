package app.familynote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.familynote.ui.FamilyNoteRoot
import app.familynote.ui.MainViewModel
import app.familynote.ui.theme.FamilyNoteTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as FamilyNoteApp

    private val vm: MainViewModel by viewModels {
        MainViewModel.Factory(app.repository, app.vehicle, app.appScope)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.vehicle.start()
        setContent {
            FamilyNoteTheme {
                FamilyNoteRoot(vm)
            }
        }
    }

    override fun onDestroy() {
        // Keep the vehicle connection across configuration changes; release it when really leaving.
        if (isFinishing) app.vehicle.stop()
        super.onDestroy()
    }
}
