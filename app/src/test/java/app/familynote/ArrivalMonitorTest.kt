package app.familynote

import app.familynote.arrival.ArrivalMonitor
import app.familynote.arrival.ArrivalNotifier
import app.familynote.data.FamilySettings
import app.familynote.data.Note
import app.familynote.data.NoteKind
import app.familynote.util.ColorMath
import app.familynote.vehicle.DriveState
import app.familynote.vehicle.FakeVehicleGateway
import app.familynote.vehicle.TripSummary
import app.familynote.voice.FakeVoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArrivalMonitorTest {

    private class Notifier : ArrivalNotifier {
        val shown = mutableListOf<String>()
        var cleared = 0
        override fun showArrival(title: String, text: String) { shown += text }
        override fun clear() { cleared++ }
    }

    private fun note(id: String, to: String = "아빠", seen: Boolean = false) = Note(
        id = id, kind = NoteKind.TEXT, imagePath = null, text = "사랑해",
        fromMember = "딸", toMember = to, createdAt = 1L, accentColor = ColorMath.WARM_DEFAULT, seen = seen,
    )

    private class Rig(notes: List<Note>, visible: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
        val car = FakeVehicleGateway(DriveState.PARKED)
        val voice = FakeVoice()
        val notifier = Notifier()
        val monitor = ArrivalMonitor(car, { notes }, FamilySettings("아빠"), voice, notifier, { visible }, scope)
            .also { it.start() }
    }

    @Test
    fun departureAnnouncesOnlyTheDriversNotes() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(listOf(note("a"), note("b", to = "엄마"), note("c", seen = true)), visible = true, backgroundScope)
        r.car.setDriveState(DriveState.DRIVING)
        assertEquals(listOf("딸이 남긴 쪽지가 1장 있어요. 도착하면 보여드릴게요."), r.voice.spoken)
    }

    @Test
    fun arrivalInBackgroundPostsNotificationWithTrip() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(listOf(note("a")), visible = false, backgroundScope)
        r.car.trip = TripSummary(12.4f, 25)
        r.car.setDriveState(DriveState.DRIVING)
        r.car.setDriveState(DriveState.PARKED)
        assertEquals(1, r.notifier.shown.size)
        assertTrue(r.notifier.shown[0].contains("12km, 25분"))
        assertEquals(1, r.monitor.arrival.value?.waiting)
    }

    @Test
    fun arrivalWhileVisibleDoesNotNotify() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(listOf(note("a")), visible = true, backgroundScope)
        r.car.setDriveState(DriveState.DRIVING)
        r.car.setDriveState(DriveState.PARKED)
        assertTrue(r.notifier.shown.isEmpty())
    }

    @Test
    fun leavingParkStopsReadingAloud() = runTest(UnconfinedTestDispatcher()) {
        val r = Rig(emptyList(), visible = true, backgroundScope)
        r.car.setDriveState(DriveState.UNKNOWN)
        assertEquals(1, r.voice.stopped)
    }

    @Test
    fun tripLineFormats() {
        assertNull(ArrivalMonitor.tripLine(TripSummary(0.2f, 3)))
        assertEquals("이번에 3.5km, 12분 운전했어요", ArrivalMonitor.tripLine(TripSummary(3.5f, 12)))
        assertEquals("이번에 84km, 1시간 30분 운전했어요", ArrivalMonitor.tripLine(TripSummary(84.2f, 90)))
        assertEquals("이번에 3.5km 운전했어요", ArrivalMonitor.tripLine(TripSummary(3.5f, null)))
    }
}
