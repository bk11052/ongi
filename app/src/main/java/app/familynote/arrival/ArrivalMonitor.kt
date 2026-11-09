package app.familynote.arrival

import app.familynote.data.FamilySettings
import app.familynote.data.Note
import app.familynote.util.Korean
import app.familynote.vehicle.DriveState
import app.familynote.vehicle.TripSummary
import app.familynote.vehicle.VehicleGateway
import app.familynote.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the app shows or posts when the car arrives. */
data class Arrival(val waiting: Int, val trip: TripSummary?, val at: Long)

/** Posts/clears the arrival notification (Android implementation in [AndroidArrivalNotifier]). */
interface ArrivalNotifier {
    fun showArrival(title: String, text: String)
    fun clear()
}

/**
 * Watches the drive state for the whole app, whether or not a screen is open:
 * - setting off (P → D): one short spoken heads-up about notes waiting for the driver;
 * - arriving (driving → P): remembers the trip, and if the app is not on screen, posts a
 *   notification that opens the gallery.
 * There is exactly one of these per process, so nothing is announced twice.
 */
class ArrivalMonitor(
    private val vehicle: VehicleGateway,
    /** Current notes, read fresh (from the database) each time it is needed. */
    private val notes: suspend () -> List<Note>,
    private val settings: FamilySettings,
    private val voice: Voice,
    private val notifier: ArrivalNotifier,
    private val isAppVisible: () -> Boolean,
    private val scope: CoroutineScope,
) {
    private val _arrival = MutableStateFlow<Arrival?>(null)
    /** The latest arrival (null before the first one). */
    val arrival: StateFlow<Arrival?> = _arrival.asStateFlow()

    private var job: Job? = null
    private var hasDriven = false

    fun start() {
        if (job != null) return
        job = scope.launch {
            var previous = vehicle.driveState.value
            vehicle.driveState.collect { now ->
                if (now != DriveState.PARKED) {
                    // Whatever happens, nothing keeps reading aloud once the car leaves P.
                    if (previous == DriveState.PARKED) voice.stopSpeaking()
                }
                if (now == DriveState.DRIVING) {
                    if (previous == DriveState.PARKED) onDepart()
                    hasDriven = true
                } else if (now == DriveState.PARKED && hasDriven) {
                    hasDriven = false
                    onArrive()
                }
                previous = now
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun waitingForDriver() =
        runCatching { notes() }.getOrDefault(emptyList()).filter { !it.seen && settings.isForDriver(it) }

    private suspend fun onDepart() {
        notifier.clear()
        val waiting = waitingForDriver()
        if (waiting.isEmpty()) return
        val senders = waiting.map { it.fromMember }.distinct()
        val who = if (senders.size == 1) Korean.subject(senders.first()) else "가족이"
        // The note lookup above suspends; skip if the car is already back in P.
        if (vehicle.driveState.value != DriveState.DRIVING) return
        voice.speak("$who 남긴 쪽지가 ${waiting.size}장 있어요. 도착하면 보여드릴게요.")
    }

    private suspend fun onArrive() {
        val waiting = waitingForDriver()
        val trip = runCatching { vehicle.tripSummary() }.getOrNull()
        _arrival.value = Arrival(waiting.size, trip, System.currentTimeMillis())
        if (waiting.isNotEmpty() && !isAppVisible()) {
            val senders = waiting.map { it.fromMember }.distinct()
            val who = if (senders.size == 1) Korean.subject(senders.first()) else "가족이"
            notifier.showArrival(
                title = "다녀왔어요",
                text = listOfNotNull("$who 남긴 쪽지 ${waiting.size}장이 기다리고 있어요", tripLine(trip))
                    .joinToString(" · "),
            )
        }
    }

    companion object {
        /** "이번에 12km, 25분 운전했어요" — or null when the trip is too short to mention. */
        fun tripLine(trip: TripSummary?): String? {
            if (trip == null || trip.distanceKm < 0.5f) return null
            val km = if (trip.distanceKm < 10f) "%.1f".format(trip.distanceKm) else trip.distanceKm.toInt().toString()
            val time = trip.minutes?.takeIf { it > 0 }?.let { m ->
                if (m >= 60) ", ${m / 60}시간 ${m % 60}분" else ", ${m}분"
            } ?: ""
            return "이번에 ${km}km$time 운전했어요"
        }
    }
}
