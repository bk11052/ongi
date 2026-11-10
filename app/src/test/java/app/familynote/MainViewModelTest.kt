package app.familynote

import app.familynote.data.Note
import app.familynote.data.NoteDao
import app.familynote.data.NoteKind
import app.familynote.data.NoteRepository
import app.familynote.ui.MainViewModel
import app.familynote.ui.Screen
import app.familynote.util.ColorMath
import app.familynote.vehicle.Cabin
import app.familynote.data.FamilySettings
import app.familynote.arrival.Arrival
import app.familynote.vehicle.DriveState
import app.familynote.vehicle.FakeVehicleGateway
import app.familynote.voice.FakeVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private class FakeDao(initial: List<Note>) : NoteDao {
    val state = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<Note>> = state
    override suspend fun get(id: String) = state.value.find { it.id == id }
    override suspend fun upsert(note: Note) {
        state.value = listOf(note) + state.value.filterNot { it.id == note.id }
    }
    override suspend fun markSeen(id: String) {
        state.value = state.value.map { if (it.id == id) it.copy(seen = true) else it }
    }
    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
    override suspend fun count() = state.value.size
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun note(id: String, color: Int = ColorMath.WARM_DEFAULT) = Note(
        id = id, kind = NoteKind.TEXT, imagePath = null, text = "사랑해",
        fromMember = "딸", toMember = "아빠", createdAt = 1L, accentColor = color,
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(notes: List<Note>, drive: DriveState): Pair<MainViewModel, FakeVehicleGateway> {
        val car = FakeVehicleGateway(drive)
        val repo = NoteRepository(FakeDao(notes), File("build/tmp-test"), dispatcher)
        val vm = MainViewModel(repo, car, FakeVoice(), Cabin(this), FamilySettings("아빠"), MutableStateFlow<Arrival?>(null), gearRecheckMs = null)
        vm.onForegrounded()
        return vm to car
    }

    @Test
    fun parkingAfterDrivingOpensGallery() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.DRIVING)
        advanceUntilIdle()
        car.setDriveState(DriveState.PARKED)
        advanceUntilIdle()
        assertTrue((vm.screen.value as? Screen.Gallery)?.auto == true)
    }

    @Test
    fun parkingWithNoNotesStaysHome() = runTest(dispatcher) {
        val (vm, car) = vm(emptyList(), DriveState.DRIVING)
        advanceUntilIdle()
        car.setDriveState(DriveState.PARKED)
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun appStartingWhileParkedDoesNotAutoOpenGallery() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.UNKNOWN)
        advanceUntilIdle()
        car.setDriveState(DriveState.PARKED)
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun drivingLeavesEditorsAndBlocksOpening() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.open(Screen.Draw)
        assertEquals(Screen.Draw, vm.screen.value)
        car.setDriveState(DriveState.DRIVING)
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.screen.value)
        vm.open(Screen.Draw)
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun showingANoteFadesAmbientToItsColorAndMarksSeen() = runTest(dispatcher) {
        val blue = 0xFF1E88E5.toInt()
        val (vm, car) = vm(listOf(note("a", blue)), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        vm.onNoteShown(vm.notes.value.first())
        advanceUntilIdle()
        assertEquals(blue, car.appliedColors.last())
        assertTrue(car.appliedColors.size > 1)
        assertTrue(vm.notes.value.first().seen)
    }

    @Test
    fun restModeIsUndoneWhenDriving() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.setResting(true)
        advanceUntilIdle()
        assertEquals(1, car.restCalls)
        car.setDriveState(DriveState.DRIVING)
        advanceUntilIdle()
        assertEquals(0, car.restCalls)
        assertEquals(false, vm.resting.value)
    }

    @Test
    fun closingGalleryRestoresCabinLight() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        vm.onNoteShown(vm.notes.value.first())
        advanceUntilIdle()
        vm.closeGallery()
        advanceUntilIdle()
        assertTrue(car.ambientRestored >= 1)
        assertEquals(null, car.ambientColor.value)
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun drivingRestoresCabinLight() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        vm.onNoteShown(vm.notes.value.first())
        advanceUntilIdle()
        val before = car.ambientRestored
        car.setDriveState(DriveState.DRIVING)
        advanceUntilIdle()
        assertTrue(car.ambientRestored > before)
    }

    @Test
    fun unknownAfterDrivingStillGreetsOnPark() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        car.setDriveState(DriveState.DRIVING)
        advanceUntilIdle()
        car.setDriveState(DriveState.UNKNOWN)
        advanceUntilIdle()
        car.setDriveState(DriveState.PARKED)
        advanceUntilIdle()
        assertTrue((vm.screen.value as? Screen.Gallery)?.auto == true)
    }

    @Test
    fun replyGoesBackToGalleryAtThatNote() = runTest(dispatcher) {
        val n = note("a")
        val (vm, _) = vm(listOf(n), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        val before = vm.screen.value as Screen.Gallery
        vm.open(Screen.Write(replyTo = n))
        vm.back()
        val after = vm.screen.value as Screen.Gallery
        assertEquals("a", after.startId)
        // Same frozen order: a reply written meanwhile is not slipped into the show.
        assertEquals(before.order, after.order)
    }

    @Test
    fun noFadeAfterGalleryClosed() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        vm.closeGallery()
        val applied = car.appliedColors.size
        vm.onNoteShown(vm.notes.value.first())
        advanceUntilIdle()
        assertEquals(applied, car.appliedColors.size)
    }

    @Test
    fun noFadeWhileAppInBackground() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        vm.onBackgrounded()
        vm.onNoteShown(vm.notes.value.first())
        advanceUntilIdle()
        assertTrue(car.appliedColors.isEmpty())
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test
    fun failedRestKeepsSnapshotForRetry() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.setResting(true)
        advanceUntilIdle()
        car.failRestores = true
        vm.setResting(false)
        advanceUntilIdle()
        assertEquals(1, car.restCalls)
        car.failRestores = false
        car.setDriveState(DriveState.DRIVING)
        advanceUntilIdle()
        assertEquals(0, car.restCalls)
    }

    @Test
    fun galleryShowsNewNotesFirst() = runTest(dispatcher) {
        val old = note("old").copy(seen = true, createdAt = 5L)
        val fresh = note("new").copy(createdAt = 1L)
        val (vm, _) = vm(listOf(old, fresh), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        val g = vm.screen.value as Screen.Gallery
        assertEquals(listOf("new", "old"), g.order)
        assertEquals(setOf("new"), g.newIds)
    }

    @Test
    fun onlyNotesForTheDriverGreetOnArrival() = runTest(dispatcher) {
        val forMom = note("m").copy(toMember = "엄마")
        val (vm, car) = vm(listOf(forMom), DriveState.DRIVING)
        advanceUntilIdle()
        car.setDriveState(DriveState.PARKED)
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.screen.value)
        vm.setDriver("엄마")
        car.setDriveState(DriveState.DRIVING); advanceUntilIdle()
        car.setDriveState(DriveState.PARKED); advanceUntilIdle()
        assertTrue(vm.screen.value is Screen.Gallery)
    }

    @Test
    fun notesForOthersStayUnseen() = runTest(dispatcher) {
        val forMom = note("m").copy(toMember = "엄마")
        val (vm, _) = vm(listOf(forMom), DriveState.PARKED)
        advanceUntilIdle()
        vm.openGallery()
        vm.onNoteShown(vm.notes.value.first())
        advanceUntilIdle()
        assertEquals(false, vm.notes.value.first().seen)
    }

    @Test
    fun interruptedDrawingResumesOnArrival() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.PARKED)
        advanceUntilIdle()
        vm.keepDraft(app.familynote.ui.DrawDraft(emptyList(), "딸", "아빠"))
        car.setDriveState(DriveState.DRIVING); advanceUntilIdle()
        car.setDriveState(DriveState.PARKED); advanceUntilIdle()
        assertEquals(Screen.Draw, vm.screen.value)
    }

    @Test
    fun autoGallerySkipsNotesForOthers() = runTest(dispatcher) {
        val mine = note("mine")
        val moms = note("moms").copy(toMember = "엄마", seen = true)
        val (vm, car) = vm(listOf(mine, moms), DriveState.DRIVING)
        advanceUntilIdle()
        car.setDriveState(DriveState.PARKED); advanceUntilIdle()
        assertEquals(listOf("mine"), (vm.screen.value as Screen.Gallery).order)
        vm.closeGallery()
        vm.openGallery()
        assertEquals(setOf("mine", "moms"), (vm.screen.value as Screen.Gallery).order.toSet())
    }
}
