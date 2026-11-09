package app.familynote.vehicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DriveState {
    /** Gear is in P. Reading and writing notes is allowed. */
    PARKED,
    /** Any gear other than P. Only the static "focus on driving" screen is shown. */
    DRIVING,
    /** Not known yet (connecting) or unreadable. Treated as not parked. */
    UNKNOWN,
}

/** Cabin state saved before rest mode, so it can be restored afterwards. */
data class RestSnapshot(
    val temperatureC: Float?,
    val fanSpeed: Int?,
    val backrestPosition: Int?,
)

/**
 * Everything the app needs from the car, behind one interface so the UI and tests
 * do not depend on the Pleos SDK directly.
 */
interface VehicleGateway {
    val driveState: StateFlow<DriveState>

    /** The ambient color the app last asked for (shown as an on-screen glow too). */
    val ambientColor: StateFlow<Int?>

    /** True when the car reported that it can set the ambient light color. */
    val ambientSupported: StateFlow<Boolean>

    fun start()
    fun stop()

    /** Sets the cabin ambient light color. Returns false if the car rejected it or it is unsupported. */
    suspend fun setAmbientColor(argb: Int): Boolean

    /** Warm, quiet cabin: softer climate and a slightly reclined driver seat. Returns what to restore. */
    suspend fun enterRest(): RestSnapshot

    suspend fun exitRest(snapshot: RestSnapshot)
}

/** In-memory gateway for unit tests and for devices without the Pleos vehicle service. */
class FakeVehicleGateway(initial: DriveState = DriveState.PARKED) : VehicleGateway {
    private val _drive = MutableStateFlow(initial)
    private val _ambient = MutableStateFlow<Int?>(null)
    override val driveState: StateFlow<DriveState> = _drive.asStateFlow()
    override val ambientColor: StateFlow<Int?> = _ambient.asStateFlow()
    override val ambientSupported: StateFlow<Boolean> = MutableStateFlow(false)

    val appliedColors = mutableListOf<Int>()
    var restCalls = 0
        private set

    fun setDriveState(state: DriveState) {
        _drive.value = state
    }

    override fun start() = Unit
    override fun stop() = Unit

    override suspend fun setAmbientColor(argb: Int): Boolean {
        appliedColors += argb
        _ambient.value = argb
        return true
    }

    override suspend fun enterRest(): RestSnapshot {
        restCalls++
        return RestSnapshot(null, null, null)
    }

    override suspend fun exitRest(snapshot: RestSnapshot) {
        restCalls--
    }
}
