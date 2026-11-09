package app.familynote

import app.familynote.data.Note
import app.familynote.data.NoteDao
import app.familynote.data.NoteKind
import app.familynote.data.NoteRepository
import app.familynote.ui.MainViewModel
import app.familynote.ui.Screen
import app.familynote.util.ColorMath
import app.familynote.vehicle.DriveState
import app.familynote.vehicle.FakeVehicleGateway
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
        val vm = MainViewModel(repo, car, this)
        return vm to car
    }

    @Test
    fun parkingAfterDrivingOpensGallery() = runTest(dispatcher) {
        val (vm, car) = vm(listOf(note("a")), DriveState.DRIVING)
        advanceUntilIdle()
        car.setDriveState(DriveState.PARKED)
        advanceUntilIdle()
        assertEquals(Screen.Gallery(auto = true), vm.screen.value)
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
}
