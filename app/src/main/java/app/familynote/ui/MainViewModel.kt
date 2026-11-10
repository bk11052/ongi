package app.familynote.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.familynote.arrival.Arrival
import app.familynote.data.FamilySettings
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    private val settings: FamilySettings,
    /** Latest arrival from the app-wide monitor (trip summary for the gallery). */
    val arrival: StateFlow<Arrival?>,
    /** How often to re-confirm the gear while on screen; null turns it off (unit tests). */
    private val gearRecheckMs: Long? = GEAR_RECHECK_MS,
) : ViewModel() {

    /** Who mainly drives this car; notes to them (or the whole family) greet on arrival. */
    val driver: StateFlow<String> = settings.driver

    fun setDriver(name: String) = settings.setDriver(name)

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

    /** A drawing left unfinished (e.g. the car left P mid-drawing), offered again next time. */
    private val _draft = MutableStateFlow<DrawDraft?>(null)
    val draft: StateFlow<DrawDraft?> = _draft.asStateFlow()

    fun keepDraft(d: DrawDraft?) {
        _draft.value = d
    }

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
            // Speaking is owned by the app-wide ArrivalMonitor (departure announcement), so
            // only stop listening here.
            voice.stopListening()
            leaveCabinAsFound()
            return
        }
        // Arrived in P after driving: greet with the gallery if something new is waiting.
        // When the screen is not visible, the arrival notification handles it instead.
        if (hasDriven) {
            hasDriven = false
            when {
                !visible -> Unit
                // A drawing was interrupted by driving (e.g. a short move at a rest stop):
                // let the child carry on rather than starting the driver's slideshow.
                _draft.value != null -> _screen.value = Screen.Draw
                waitingForDriver().isNotEmpty() -> _screen.value = newGallery(auto = true)
            }
        }
    }

    private fun waitingForDriver() = notes.value.filter { !it.seen && settings.isForDriver(it) }

    /**
     * Freezes the show order now: notes waiting for the driver first (oldest first), then
     * everything else to revisit.
     */
    private fun newGallery(auto: Boolean, startId: String? = null): Screen.Gallery {
        val all = notes.value
        val fresh = waitingForDriver().sortedBy { it.createdAt }
        val newIds = fresh.map { it.id }.toSet()
        // On arrival, revisit only the driver's own notes; notes for others stay theirs.
        val rest = all.filter { it.id !in newIds && (!auto || settings.isForDriver(it)) }
        val order = fresh.map { it.id } + rest.map { it.id }
        return Screen.Gallery(auto, order, newIds, startId).also { lastGallery = it }
    }

    /**
     * Opened from the arrival notification. On a cold start the notes and the gear state may
     * still be loading, so wait (briefly) until notes are loaded and the car is in P.
     */
    fun openGalleryWhenReady() {
        viewModelScope.launch {
            repo.notes.first()
            val parked = withTimeoutOrNull(5_000) { drive.first { it == DriveState.PARKED } }
            if (parked != null) {
                notes.first { it.isNotEmpty() || repo.isEmpty() }
                openGallery(auto = true)
            }
        }
    }

    fun openGallery(startId: String? = null, auto: Boolean = false) {
        if (drive.value != DriveState.PARKED || notes.value.isEmpty()) return
        _screen.value = newGallery(auto = auto, startId = startId)
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
        // Only the driver's own notes become "seen"; notes for others keep waiting for them.
        if (!note.seen && settings.isForDriver(note)) {
            viewModelScope.launch { runCatching { repo.markSeen(note.id) } }
        }
        fadeAmbientTo(note.accentColor)
    }

    /** True between onStart and onStop of the activity. */
    @Volatile private var visible = false

    private fun galleryShowing() =
        visible && _screen.value is Screen.Gallery && drive.value == DriveState.PARKED

    private var gearCheck: Job? = null

    fun onForegrounded() {
        visible = true
        // While the screen is in use, confirm the gear now and then so a silent vehicle
        // service restart can never leave the UI unlocked on a stale "parked".
        gearCheck?.cancel()
        val every = gearRecheckMs ?: return
        gearCheck = viewModelScope.launch {
            while (true) {
                delay(every)
                runCatching { vehicle.recheckGear() }
            }
        }
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
                    // Not cancellable once started: a step cut off mid-call could still land
                    // after the restore that follows.
                    withContext(NonCancellable) {
                        if (galleryShowing()) {
                            vehicle.setAmbientColor(c)
                            true
                        } else {
                            false
                        }
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
        gearCheck?.cancel()
        voice.stopListening()
        voice.stopSpeaking()
        if (_screen.value is Screen.Gallery) closeGallery() else leaveCabinAsFound()
    }

    fun saveDrawing(bitmap: Bitmap, from: String, to: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.saveDrawing(bitmap, from, to) }
                .onFailure { Log.e(TAG, "saveDrawing failed", it) }
                .isSuccess
            if (ok) {
                _draft.value = null
                back()
                showMessage("${to}에게 그림을 보냈습니다")
            } else {
                showMessage("그림을 저장하지 못했습니다. 다시 시도하세요.")
            }
            onDone(ok)
        }
    }

    fun saveText(text: String, from: String, to: String, replyToId: String?, onDone: (Boolean) -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val ok = runCatching { repo.saveText(text, from, to, replyToId) }
                .onFailure { Log.e(TAG, "saveText failed", it) }
                .isSuccess
            if (ok) {
                back()
                showMessage(if (replyToId != null) "${to}에게 답장을 보냈습니다" else "${to}에게 쪽지를 보냈습니다")
            } else {
                showMessage("쪽지를 저장하지 못했습니다. 다시 시도하세요.")
            }
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
        private val settings: FamilySettings,
        private val arrival: StateFlow<Arrival?>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repo, vehicle, voice, cabin, settings, arrival) as T
    }

    companion object {
        private const val TAG = "MainViewModel"
        private const val AMBIENT_STEPS = 5
        const val GEAR_RECHECK_MS = 15_000L
        private const val AMBIENT_FADE_MS = 2000L
        private const val MESSAGE_MS = 4000L
    }
}
