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
import app.familynote.vehicle.Cabin
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Draw : Screen

    /** [replyTo] is set when replying from the gallery; going back returns there. */
    data class Write(val replyTo: Note? = null) : Screen

    /**
     * [auto] is true when the gallery opened by itself because the car was just parked.
     * [order] is frozen when the gallery opens (new notes first) so "seen" updates and
     * replies written meanwhile never reshuffle it; [newIds] are the notes that were new then.
     * [startId] is the note to begin with (null = the first in [order]).
     */
    data class Gallery(
        val auto: Boolean,
        val order: List<String>,
        val newIds: Set<String>,
        val startId: String? = null,
    ) : Screen
}

class MainViewModel(
    private val repo: NoteRepository,
    private val vehicle: VehicleGateway,
    private val voice: Voice,
    /** App-wide serializer for cabin changes; outlives this ViewModel. */
    private val cabin: Cabin,
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

    /** The gallery to return to after replying from it. */
    private var lastGallery: Screen.Gallery? = null

    /** Set once the car has been driven; the next arrival in P opens the gallery. */
    private var hasDriven = false

    init {
        // Undo anything a previous run left changed (crash, failed restore).
        cabin.run { runCatching { vehicle.recoverPendingCabin() } }
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
            lastGallery = null
            colorJob?.cancel()
            voice.stopListening()
            voice.stopSpeaking()
            leaveCabinAsFound()
            // Setting off: a short spoken heads-up instead of anything to look at.
            if (previous == DriveState.PARKED && now == DriveState.DRIVING) announceWaitingNotes()
            return
        }
        // Arrived in P after driving: greet with the gallery if there is anything to show.
        if (hasDriven) {
            hasDriven = false
            if (notes.value.isNotEmpty()) _screen.value = newGallery(auto = true)
        }
    }

    /** Freezes the show order now: new notes first (oldest first), then older ones. */
    private fun newGallery(auto: Boolean, startId: String? = null): Screen.Gallery {
        val all = notes.value
        val fresh = all.filter { !it.seen }.sortedBy { it.createdAt }
        val newIds = fresh.map { it.id }.toSet()
        val order = fresh.map { it.id } + all.filter { it.id !in newIds }.map { it.id }
        return Screen.Gallery(auto, order, newIds, startId).also { lastGallery = it }
    }

    fun openGallery(startId: String? = null) {
        if (drive.value != DriveState.PARKED || notes.value.isEmpty()) return
        _screen.value = newGallery(auto = false, startId = startId)
    }

    fun open(screen: Screen) {
        if (drive.value != DriveState.PARKED) return
        _screen.value = screen
    }

    fun back() {
        when (val s = _screen.value) {
            is Screen.Gallery -> closeGallery()
            is Screen.Write -> _screen.value =
                lastGallery?.takeIf { s.replyTo != null }?.copy(startId = s.replyTo?.id) ?: Screen.Home
            else -> _screen.value = Screen.Home
        }
    }

    fun closeGallery() {
        // Leave the gallery first so any in-flight fade step sees it closed.
        _screen.value = Screen.Home
        lastGallery = null
        voice.stopSpeaking()
        colorJob?.cancel()
        leaveCabinAsFound()
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

    /** True between onStart and onStop of the activity. */
    @Volatile private var visible = false

    private fun galleryShowing() =
        visible && _screen.value is Screen.Gallery && drive.value == DriveState.PARKED

    fun onForegrounded() {
        visible = true
    }

    /** Changes the cabin light in small steps so it drifts rather than jumps. */
    private fun fadeAmbientTo(target: Int) {
        if (!galleryShowing()) return
        colorJob?.cancel()
        colorJob = viewModelScope.launch {
            val from = vehicle.ambientColor.value ?: ColorMath.WARM_DEFAULT
            for (c in ColorMath.steps(from, target, AMBIENT_STEPS)) {
                // Same lock as the restore, and re-checked each step: a late step after the
                // gallery closed would otherwise change the light again after it was restored.
                val applied = cabin.locked {
                    if (galleryShowing()) {
                        vehicle.setAmbientColor(c)
                        true
                    } else {
                        false
                    }
                }
                if (!applied) return@launch
                delay(AMBIENT_FADE_MS / AMBIENT_STEPS)
            }
        }
    }

    fun setResting(on: Boolean) {
        if (on && drive.value != DriveState.PARKED) return
        cabin.run {
            if (on == _resting.value) return@run
            if (on) {
                if (drive.value != DriveState.PARKED) return@run
                _resting.value = true
                restSnapshot = runCatching { vehicle.enterRest() }
                    .onFailure { Log.w(TAG, "enterRest failed", it) }
                    .getOrNull()
            } else {
                _resting.value = false
                endRest()
            }
        }
    }

    /** Must run inside [Cabin.run]. Keeps the snapshot if the restore failed (retried later). */
    private suspend fun endRest() {
        val s = restSnapshot ?: return
        val ok = runCatching { vehicle.exitRest(s) }.getOrDefault(false)
        if (ok) restSnapshot = null
    }

    /** Ends rest mode and gives the cabin its own ambient light back. */
    private fun leaveCabinAsFound() {
        cabin.run {
            _resting.value = false
            endRest()
            runCatching { vehicle.restoreAmbient() }
        }
    }

    /** The app left the screen (Home button, another app) while parked: undo cabin changes. */
    fun onBackgrounded() {
        visible = false
        voice.stopListening()
        voice.stopSpeaking()
        if (_screen.value is Screen.Gallery) closeGallery() else leaveCabinAsFound()
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
        private val cabin: Cabin,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repo, vehicle, voice, cabin) as T
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val AMBIENT_STEPS = 5
        private const val AMBIENT_FADE_MS = 1000L
        private const val MESSAGE_MS = 4000L
    }
}
