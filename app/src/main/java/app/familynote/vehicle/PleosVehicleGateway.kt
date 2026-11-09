package app.familynote.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Talks to the Pleos Vehicle Service. Every SDK call is asynchronous IPC, so each one is
 * wrapped into a suspend function with a timeout; failures are logged and reported as
 * null/false instead of crashing the app.
 */
class PleosVehicleGateway(
    context: Context,
    private val scope: CoroutineScope,
) : VehicleGateway {

    private val vehicle = Vehicle(context.applicationContext)
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)

    private val _drive = MutableStateFlow(DriveState.UNKNOWN)
    private val _ambient = MutableStateFlow<Int?>(null)
    private val _ambientSupported = MutableStateFlow(false)
    override val driveState: StateFlow<DriveState> = _drive.asStateFlow()
    override val ambientColor: StateFlow<Int?> = _ambient.asStateFlow()
    override val ambientSupported: StateFlow<Boolean> = _ambientSupported.asStateFlow()

    /** Seat areas whose ambient color can be set; filled by the capability check. */
    @Volatile private var ambientAreas: List<SeatArea> = emptyList()
    @Volatile private var ambientSwitchedOn = false
    private var startJob: Job? = null
    private var started = false

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            _drive.value = gear?.toDriveState() ?: DriveState.UNKNOWN
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "gear listener failed", e)
        }
    }

    override fun start() {
        if (started) return
        started = true
        vehicle.initialize()
        startJob = scope.launch {
            // The service connection is set up asynchronously; retry the first reads briefly.
            var gear: VehicleGear? = null
            for (attempt in 1..10) {
                gear = call<VehicleGear> { ok, fail -> vehicle.getGear().getCurrentGearSelectionState(ok, fail) }
                if (gear != null) break
                delay(500)
            }
            if (gear != null) _drive.value = gear.toDriveState()
            else Log.w(TAG, "gear state unavailable; staying UNKNOWN")
            runCatching { vehicle.getGear().registerGearSelection(gearListener) }
                .onFailure { Log.w(TAG, "registerGearSelection failed", it) }
            detectAmbientAreas()
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        startJob?.cancel()
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        // release() must not run while callbacks are pending; give in-flight calls a moment.
        scope.launch {
            delay(CALL_TIMEOUT_MS)
            if (!started) runCatching { vehicle.release() }
        }
    }

    private suspend fun detectAmbientAreas() {
        val areas = call<List<SeatArea>> { ok, fail ->
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ _, list -> ok(list) }, fail)
        }.orEmpty()
        ambientAreas = areas
        _ambientSupported.value = areas.isNotEmpty()
        Log.i(TAG, "ambient color areas: ${areas.size}")
    }

    override suspend fun setAmbientColor(argb: Int): Boolean {
        val color = argb or OPAQUE
        _ambient.value = color
        val areas = ambientAreas
        if (areas.isEmpty()) return false
        if (!ambientSwitchedOn) {
            for (area in areas) {
                call<Unit> { ok, fail ->
                    vehicle.getLight().setAmbientLightSwitch(area, LightSwitch.ON, { _, _ -> ok(Unit) }, fail)
                }
            }
            ambientSwitchedOn = true
        }
        var allOk = true
        for (area in areas) {
            val r = call<Int> { ok, fail ->
                vehicle.getLight().setAmbientLightBrightnessColor(area, color, { _, v -> ok(v) }, fail)
            }
            if (r == null) allOk = false
        }
        return allOk
    }

    override suspend fun enterRest(): RestSnapshot {
        val hvac = vehicle.getHVAC()
        val temp = call<Float> { ok, fail -> hvac.getHvacTemperature(driver, TemperatureUnit.CELSIUS, ok, fail) }
        val fan = call<Int> { ok, fail -> hvac.getHvacFanSpeed(driver, ok, fail) }
        val backrest = call<Int> { ok, fail -> vehicle.getSeat().getCurrentSeatBackrestAnglePosition(driver, ok, fail) }

        call<Float> { ok, fail ->
            hvac.setHvacTemperature(driver, REST_TEMPERATURE_C, TemperatureUnit.CELSIUS, { _, v -> ok(v) }, fail)
        }
        val fanBounds = call<Map<SeatArea, AreaBounds<Int>>> { ok, fail ->
            hvac.checkHvacFanSpeedCapabilities({ bounds: Map<SeatArea, AreaBounds<Int>> -> ok(bounds) }, fail)
        }
        val quietFan = fanBounds?.values?.firstOrNull()?.let { (it.minValue + 1).coerceAtMost(it.maxValue) }
        if (quietFan != null && (fan == null || fan > quietFan)) {
            call<Int> { ok, fail -> hvac.setHvacFanSpeed(driver, quietFan, { _, v -> ok(v) }, fail) }
        }
        moveBackrest(MoveControl.BACKWARD, RECLINE_MS)
        return RestSnapshot(temp, fan, backrest)
    }

    override suspend fun exitRest(snapshot: RestSnapshot) {
        val hvac = vehicle.getHVAC()
        snapshot.temperatureC?.let { t ->
            call<Float> { ok, fail -> hvac.setHvacTemperature(driver, t, TemperatureUnit.CELSIUS, { _, v -> ok(v) }, fail) }
        }
        snapshot.fanSpeed?.let { f ->
            call<Int> { ok, fail -> hvac.setHvacFanSpeed(driver, f, { _, v -> ok(v) }, fail) }
        }
        moveBackrest(MoveControl.FORWARD, RECLINE_MS)
    }

    private suspend fun moveBackrest(direction: MoveControl, durationMs: Long) {
        val seat = vehicle.getSeat()
        val moved = call<MoveControl> { ok, fail -> seat.moveSeatBackrestAngle(driver, direction, { _, v -> ok(v) }, fail) }
        if (moved == null) return
        delay(durationMs)
        call<MoveControl> { ok, fail -> seat.moveSeatBackrestAngle(driver, MoveControl.STOP, { _, v -> ok(v) }, fail) }
    }

    /**
     * Runs one SDK call and waits for its onComplete/onFailed callback.
     * Returns null on failure, timeout, a null result, or if the SDK throws synchronously.
     */
    private suspend fun <T> call(block: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine<T?> { cont ->
                // A null value from the SDK is treated like a failure.
                val ok: (T?) -> Unit = { v -> if (cont.isActive) cont.resume(v) }
                val fail: (Exception) -> Unit = { e ->
                    Log.w(TAG, "vehicle call failed: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
                try {
                    block(ok, fail)
                } catch (e: Exception) {
                    Log.w(TAG, "vehicle call threw", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    companion object {
        private const val TAG = "PleosVehicle"
        private const val CALL_TIMEOUT_MS = 1500L
        private const val OPAQUE = 0xFF000000.toInt()
        private const val REST_TEMPERATURE_C = 24f
        private const val RECLINE_MS = 1200L

        private const val VEHICLE_SERVICE_ACTION = "ai.pleos.playground.vehicle.VEHICLE_START"

        /** True when the Pleos Vehicle Service is installed (a Pleos car or emulator). */
        fun isAvailable(context: Context): Boolean =
            context.packageManager.queryIntentServices(Intent(VEHICLE_SERVICE_ACTION), 0).isNotEmpty()

        fun VehicleGear.toDriveState(): DriveState = when (this) {
            VehicleGear.GEAR_PARK -> DriveState.PARKED
            VehicleGear.GEAR_UNKNOWN -> DriveState.UNKNOWN
            else -> DriveState.DRIVING
        }
    }
}
