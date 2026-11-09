package app.familynote.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.familynote.data.Note
import app.familynote.data.NoteKind
import app.familynote.data.NoteRepository
import app.familynote.util.Korean
import app.familynote.util.ColorMath
import app.familynote.vehicle.DriveState
import app.familynote.vehicle.RestSnapshot
import app.familynote.vehicle.VehicleGateway
import app.familynote.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Screen {
    data object Home : Screen
    data object Draw : Screen
    data class Write(val replyTo: Note? = null) : Screen
    /** [auto] is true when the gallery opened by itself because the car was just parked. */
    data class Gallery(val auto: Boolean) : Screen
}

class MainViewModel(
    private val repo: NoteRepository,
    private val vehicle: VehicleGateway,
    private val voice: Voice,
    /** Outlives this ViewModel; used to restore the cabin after the screen is gone. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    val notes: StateFlow<List<Note>> =
        repo.notes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val drive: StateFlow<DriveState> = vehicle.driveState
    val ambient: StateFlow<Int?> = vehicle.ambientColor

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _resting = MutableStateFlow(false)
    val resting: StateFlow<Boolean> = _resting.asStateFlow()

    private var colorJob: Job? = null
    private var restSnapshot: RestSnapshot? = null
    private val restLock = Mutex()

    init {
        viewModelScope.launch {
            var previous = vehicle.driveState.value
            vehicle.driveState.collect { now ->
                onDriveChanged(previous, now)
                previous = now
            }
        }
    }

    private fun onDriveChanged(previous: DriveState, now: DriveState) {
        when {
            // Just parked after driving: greet with the gallery if there is anything to show.
            previous == DriveState.DRIVING && now == DriveState.PARKED && notes.value.isNotEmpty() ->
                _screen.value = Screen.Gallery(auto = true)
            now == DriveState.DRIVING -> {
                // Leave any editing screen; unsaved strokes are dropped on purpose (safety first).
                _screen.value = Screen.Home
                colorJob?.cancel()
                voice.stopListening()
                if (_resting.value) setResting(false)
                // Setting off: a short spoken heads-up instead of anything to look at.
                if (previous == DriveState.PARKED) announceWaitingNotes()
            }
        }
    }

    fun open(screen: Screen) {
        if (drive.value != DriveState.PARKED) return
        _screen.value = screen
    }

    private fun announceWaitingNotes() {
        val unseen = notes.value.filter { !it.seen }
        if (unseen.isEmpty()) return
        val senders = unseen.map { it.fromMember }.distinct()
        val who = if (senders.size == 1) Korean.subject(senders.first()) else "가족이"
        voice.speak("${who} 남긴 쪽지가 ${unseen.size}장 있어요. 도착하면 보여드릴게요.")
    }

    /** Reads a note aloud (text notes verbatim; drawings get a short introduction). */
    fun readAloud(note: Note) {
        val text = when (note.kind) {
            NoteKind.TEXT -> note.text.orEmpty()
            NoteKind.DRAWING -> "${Korean.subject(note.fromMember)} ${note.toMember}에게 남긴 그림이에요."
        }
        if (text.isNotBlank()) voice.speak(text)
    }

    fun listen(onText: (String, Boolean) -> Unit, onError: (String) -> Unit) {
        if (drive.value != DriveState.PARKED) return
        voice.listen(onText, onError)
    }

    fun stopListening() = voice.stopListening()

    fun closeGallery() {
        voice.stopSpeaking()
        colorJob?.cancel()
        if (_resting.value) setResting(false)
        _screen.value = Screen.Home
    }

    fun back() {
        when (_screen.value) {
            is Screen.Gallery -> closeGallery()
            else -> _screen.value = Screen.Home
        }
    }

    /** Called by the gallery whenever a new note comes on screen. */
    fun onNoteShown(note: Note) {
        if (!note.seen) viewModelScope.launch { repo.markSeen(note.id) }
        fadeAmbientTo(note.accentColor)
    }

    /** Changes the cabin light in small steps so it drifts rather than jumps. */
    fun fadeAmbientTo(target: Int) {
        colorJob?.cancel()
        colorJob = viewModelScope.launch {
            val from = vehicle.ambientColor.value ?: ColorMath.WARM_DEFAULT
            for (c in ColorMath.steps(from, target, AMBIENT_STEPS)) {
                vehicle.setAmbientColor(c)
                delay(AMBIENT_FADE_MS / AMBIENT_STEPS)
            }
        }
    }

    fun setResting(on: Boolean) {
        viewModelScope.launch {
            restLock.withLock {
                if (on == _resting.value) return@withLock
                if (on) {
                    if (drive.value != DriveState.PARKED) return@withLock
                    _resting.value = true
                    restSnapshot = runCatching { vehicle.enterRest() }
                        .onFailure { Log.w(TAG, "enterRest failed", it) }
                        .getOrNull()
                } else {
                    _resting.value = false
                    restSnapshot?.let { s -> runCatching { vehicle.exitRest(s) } }
                    restSnapshot = null
                }
            }
        }
    }

    fun saveDrawing(bitmap: Bitmap, from: String, to: String, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repo.saveDrawing(bitmap, from, to) }
                .onFailure { Log.e(TAG, "saveDrawing failed", it) }
            onDone()
        }
    }

    fun saveText(text: String, from: String, to: String, replyToId: String?, onDone: () -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.saveText(text, from, to, replyToId) }
                .onFailure { Log.e(TAG, "saveText failed", it) }
            onDone()
        }
    }

    fun delete(note: Note) {
        viewModelScope.launch { runCatching { repo.delete(note) } }
    }

    suspend fun loadBitmap(path: String, maxSide: Int) = repo.loadBitmap(path, maxSide)

    override fun onCleared() {
        colorJob?.cancel()
        voice.stopListening()
        val snapshot = restSnapshot
        if (_resting.value && snapshot != null) {
            // viewModelScope is already cancelled here; restore on the app scope.
            appScope.launch { runCatching { vehicle.exitRest(snapshot) } }
        }
    }

    class Factory(
        private val repo: NoteRepository,
        private val vehicle: VehicleGateway,
        private val voice: Voice,
        private val appScope: CoroutineScope,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repo, vehicle, voice, appScope) as T
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val AMBIENT_STEPS = 10
        private const val AMBIENT_FADE_MS = 1000L
    }
}
