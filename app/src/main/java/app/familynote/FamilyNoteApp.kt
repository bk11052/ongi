package app.familynote

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.room.Room
import app.familynote.arrival.AndroidArrivalNotifier
import app.familynote.arrival.ArrivalMonitor
import app.familynote.data.AppDatabase
import app.familynote.data.FamilySettings
import app.familynote.data.Note
import app.familynote.data.NoteRepository
import app.familynote.vehicle.Cabin
import app.familynote.vehicle.DriveState
import app.familynote.vehicle.FakeVehicleGateway
import app.familynote.vehicle.PleosVehicleGateway
import app.familynote.vehicle.VehicleGateway
import app.familynote.voice.PleosVoice
import app.familynote.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Holds the app-wide singletons (database, repository, vehicle, voice, arrival monitor). */
class FamilyNoteApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository: NoteRepository by lazy {
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "family_note.db").build()
        NoteRepository(db.notes(), filesDir)
    }

    /** Always-current note list for code that needs a snapshot (e.g. the arrival monitor). */
    val notes: StateFlow<List<Note>> by lazy {
        repository.notes.stateIn(appScope, SharingStarted.Eagerly, emptyList())
    }

    val vehicle: VehicleGateway by lazy {
        if (PleosVehicleGateway.isAvailable(this)) {
            PleosVehicleGateway(this, appScope)
        } else {
            Log.i("FamilyNoteApp", "Pleos vehicle service not found; using fake gateway")
            // Fail closed in release builds: without gear information the app stays locked.
            // Debug builds start parked so the UI can be tried on a plain emulator.
            FakeVehicleGateway(if (BuildConfig.DEBUG) DriveState.PARKED else DriveState.UNKNOWN)
        }
    }

    val voice: Voice by lazy { PleosVoice(this) }

    /** Serializes cabin changes app-wide (see [Cabin]). */
    val cabin = Cabin(appScope)

    val settings: FamilySettings by lazy {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        FamilySettings(prefs.getString("driver", "아빠") ?: "아빠") { prefs.edit().putString("driver", it).apply() }
    }

    val notifier: AndroidArrivalNotifier by lazy { AndroidArrivalNotifier(this) }

    /** True while the main screen is visible (between onStart and onStop). */
    @Volatile var appVisible = false

    val arrivalMonitor: ArrivalMonitor by lazy {
        ArrivalMonitor(vehicle, { notes.value }, settings, voice, notifier, { appVisible }, appScope)
    }
}
