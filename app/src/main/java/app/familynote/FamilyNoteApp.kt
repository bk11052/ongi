package app.familynote

import android.app.Application
import android.util.Log
import androidx.room.Room
import app.familynote.data.AppDatabase
import app.familynote.data.NoteRepository
import app.familynote.vehicle.FakeVehicleGateway
import app.familynote.vehicle.PleosVehicleGateway
import app.familynote.vehicle.VehicleGateway
import app.familynote.voice.PleosVoice
import app.familynote.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Holds the app-wide singletons (database, repository, vehicle gateway). */
class FamilyNoteApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository: NoteRepository by lazy {
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "family_note.db").build()
        NoteRepository(db.notes(), filesDir)
    }

    val vehicle: VehicleGateway by lazy {
        if (PleosVehicleGateway.isAvailable(this)) {
            PleosVehicleGateway(this, appScope)
        } else {
            Log.i("FamilyNoteApp", "Pleos vehicle service not found; using fake gateway")
            FakeVehicleGateway()
        }
    }

    val voice: Voice by lazy { PleosVoice(this) }
}
