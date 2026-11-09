package app.familynote.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.familynote.data.Note
import app.familynote.data.NoteKind
import app.familynote.data.NoteRepository
import app.familynote.util.ColorMath
import app.familynote.util.Korean
import app.familynote.vehicle.DriveState
import app.familynote.vehicle.RestSnapshot
import app.familynote.vehicle.VehicleGateway
import app.familynote.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data object Draw : Screen

    /** [replyTo] is set when replying from the gallery; going back returns there. */
    data class Write(val replyTo: Note? = null) : Screen

    /**
     * [auto] is true when the gallery opened by itself because the car was just parked.
     * [startId] is the note to begin with (null = newest).
     */
    data class Gallery(val auto: Boolean, val startId: String? = null) : Screen
}

class MainViewModel(
    private val repo: NoteRepository,
    private val vehicle: VehicleGateway,
    private val voice: Voice,
    /**
     * Outlives this ViewModel. Cabin changes (rest mode, ambient restore) run here so that
     * closing the screen can never leave them half done.
     */
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

    /** A short message for the user (e.g. a save failed); cleared after a few seconds. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var colorJob: Job? = null
    private var messageJob: Job? = null
    private var restSnapshot: RestSnapshot? = null

    /** Serializes all cabin changes (rest on/off, ambient restore). */
    private val cabinLock = Mutex()

    /** Set once the car has been driven; the next arrival in P opens the gallery. */
    private var hasDriven = false

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
        if (now == DriveState.DRIVING) hasDriven = true
        if (now != DriveState.PARKED) {
            // Not parked (or not known): drop editing screens and stop anything cabin-related.
            // Unsaved strokes are discarded on purpose; safety comes first.
            _screen.value = Screen.Home
            colorJob?.cancel()
            voice.stopListening()
            leaveCabinAsFound()
            // Setting off: a short spoken heads-up instead of anything to look at.
            if (previous == DriveState.PARKED && now == DriveState.DRIVING) announceWaitingNotes()
            return
        }
        // Arrived in P after driving: greet with the gallery if there is anything to show.
        if (hasDriven) {
            hasDriven = false
            if (notes.value.isNotEmpty()) _screen.value = Screen.Gallery(auto = true)
        }
    }

    fun open(screen: Screen) {
        if (drive.value != DriveState.PARKED) return
        _screen.value = screen
    }

    fun back() {
        when (val s = _screen.value) {
            is Screen.Gallery -> closeGallery()
            is Screen.Write -> _screen.value =
                if (s.replyTo != null) Screen.Gallery(auto = false, startId = s.replyTo.id) else Screen.Home
            else -> _screen.value = Screen.Home
        }
    }

    fun closeGallery() {
        voice.stopSpeaking()
        colorJob?.cancel()
        leaveCabinAsFound()
        _screen.value = Screen.Home
    }

    private fun announceWaitingNotes() {
        val unseen = notes.value.filter { !it.seen }
        if (unseen.isEmpty()) return
        val senders = unseen.map { it.fromMember }.distinct()
        val who = if (senders.size == 1) Korean.subject(senders.first()) else "가족이"
        voice.speak("$who 남긴 쪽지가 ${unseen.size}장 있어요. 도착하면 보여드릴게요.")
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

    /** Called by the gallery whenever a new note comes on screen. */
    fun onNoteShown(note: Note) {
        if (!note.seen) viewModelScope.launch { runCatching { repo.markSeen(note.id) } }
        fadeAmbientTo(note.accentColor)
    }

    /** Changes the cabin light in small steps so it drifts rather than jumps. */
    private fun fadeAmbientTo(target: Int) {
        if (drive.value != DriveState.PARKED) return
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
        if (on && drive.value != DriveState.PARKED) return
        cabin {
            if (on == _resting.value) return@cabin
            if (on) {
                if (drive.value != DriveState.PARKED) return@cabin
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

    /** Ends rest mode and gives the cabin its own ambient light back. */
    private fun leaveCabinAsFound() {
        cabin {
            if (_resting.value) {
                _resting.value = false
                restSnapshot?.let { s -> runCatching { vehicle.exitRest(s) } }
                restSnapshot = null
            }
            runCatching { vehicle.restoreAmbient() }
        }
    }

    /** Runs a cabin change to completion, one at a time, even if this ViewModel is cleared. */
    private fun cabin(block: suspend () -> Unit) {
        appScope.launch {
            withContext(NonCancellable) { cabinLock.withLock { block() } }
        }
    }

    fun saveDrawing(bitmap: Bitmap, from: String, to: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.saveDrawing(bitmap, from, to) }
                .onFailure { Log.e(TAG, "saveDrawing failed", it) }
                .isSuccess
            if (ok) back() else showMessage("그림을 저장하지 못했어요. 다시 한 번 눌러 주세요.")
            onDone(ok)
        }
    }

    fun saveText(text: String, from: String, to: String, replyToId: String?, onDone: (Boolean) -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val ok = runCatching { repo.saveText(text, from, to, replyToId) }
                .onFailure { Log.e(TAG, "saveText failed", it) }
                .isSuccess
            if (ok) back() else showMessage("쪽지를 저장하지 못했어요. 다시 한 번 눌러 주세요.")
            onDone(ok)
        }
    }

    fun delete(note: Note) {
        viewModelScope.launch { runCatching { repo.delete(note) } }
    }

    private fun showMessage(text: String) {
        messageJob?.cancel()
        _message.value = text
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            _message.value = null
        }
    }

    suspend fun loadBitmap(path: String, maxSide: Int) = repo.loadBitmap(path, maxSide)

    override fun onCleared() {
        colorJob?.cancel()
        voice.stopListening()
        voice.stopSpeaking()
        leaveCabinAsFound()
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
        private const val AMBIENT_STEPS = 5
        private const val AMBIENT_FADE_MS = 1000L
        private const val MESSAGE_MS = 4000L
    }
}
