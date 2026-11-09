package app.familynote.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Talks to the Pleos Vehicle Service. Every SDK call is asynchronous IPC, so each one is
 * wrapped into a suspend function with a timeout; failures are logged and reported as
 * null/false instead of crashing the app.
 *
 * Safety: the drive state fails closed. Anything unexpected (no reading, listener failure)
 * becomes UNKNOWN, which the UI treats as "not parked".
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

    /** The cabin's own ambient color/switch per area before the app first changed it. */
    private var savedAmbient: Map<SeatArea, Pair<Int?, LightSwitch?>>? = null

    /** SDK calls whose callback has not arrived yet; release() waits for zero. */
    private val inFlight = AtomicInteger(0)
    private var startJob: Job? = null
    private var started = false

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            _drive.value = gear?.toDriveState() ?: DriveState.UNKNOWN
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "gear listener failed", e)
            _drive.value = DriveState.UNKNOWN
        }
    }

    override fun start() {
        if (started) return
        started = true
        vehicle.initialize()
        startJob = scope.launch {
            // Subscribe first so a gear change during the initial read is not missed.
            var registered = false
            var gear: VehicleGear? = null
            // The service connection is set up asynchronously; retry briefly.
            for (attempt in 1..10) {
                if (!registered) {
                    registered = runCatching { vehicle.getGear().registerGearSelection(gearListener) }
                        .onFailure { Log.w(TAG, "registerGearSelection failed (attempt $attempt)", it) }
                        .isSuccess
                }
                gear = call<VehicleGear> { ok, fail -> vehicle.getGear().getCurrentGearSelectionState(ok, fail) }
                if (registered && gear != null) break
                delay(500)
            }
            if (!registered) {
                Log.w(TAG, "gear updates unavailable; staying UNKNOWN")
                _drive.value = DriveState.UNKNOWN
            } else if (gear != null && _drive.value == DriveState.UNKNOWN) {
                // Only fill in if the listener has not already reported something newer.
                _drive.value = gear.toDriveState()
            }
            detectAmbientAreas()
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        startJob?.cancel()
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        // release() must not run while callbacks are pending (e.g. a cabin restore).
        scope.launch {
            val deadline = System.currentTimeMillis() + RELEASE_WAIT_MS
            while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) delay(100)
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
        if (savedAmbient == null) {
            // Remember the cabin's own setting once, so it can be put back later.
            savedAmbient = areas.associateWith { area ->
                val c = call<Int> { ok, fail -> vehicle.getLight().getAmbientLightBrightnessColor(area, ok, fail) }
                val s = call<LightSwitch> { ok, fail -> vehicle.getLight().getAmbientLightSwitch(area, ok, fail) }
                c to s
            }
            for (area in areas) {
                call<LightSwitch> { ok, fail ->
                    vehicle.getLight().setAmbientLightSwitch(area, LightSwitch.ON, { _, v -> ok(v) }, fail)
                }
            }
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

    override suspend fun restoreAmbient() {
        val saved = savedAmbient ?: return
        savedAmbient = null
        _ambient.value = null
        for ((area, value) in saved) {
            val (color, switch) = value
            if (color != null) {
                // The car reports the color without alpha (e.g. 0x00FFFFFF) but only accepts
                // opaque values, so force alpha to 0xFF when writing it back.
                call<Int> { ok, fail ->
                    vehicle.getLight().setAmbientLightBrightnessColor(area, color or OPAQUE, { _, v -> ok(v) }, fail)
                }
            }
            if (switch != null) {
                call<LightSwitch> { ok, fail ->
                    vehicle.getLight().setAmbientLightSwitch(area, switch, { _, v -> ok(v) }, fail)
                }
            }
        }
    }

    override suspend fun enterRest(): RestSnapshot {
        val hvac = vehicle.getHVAC()
        val temp = call<Float> { ok, fail -> hvac.getHvacTemperature(driver, TemperatureUnit.CELSIUS, ok, fail) }
        val fan = call<Int> { ok, fail -> hvac.getHvacFanSpeed(driver, ok, fail) }

        // Change a setting only if we could read it first, so it can always be restored.
        if (temp != null) {
            call<Float> { ok, fail ->
                hvac.setHvacTemperature(driver, REST_TEMPERATURE_C, TemperatureUnit.CELSIUS, { _, v -> ok(v) }, fail)
            }
        }
        if (fan != null) {
            val bounds = call<Map<SeatArea, AreaBounds<Int>>> { ok, fail ->
                hvac.checkHvacFanSpeedCapabilities({ b: Map<SeatArea, AreaBounds<Int>> -> ok(b) }, fail)
            }
            val driverBounds = bounds?.entries
                ?.firstOrNull { (area, _) -> SeatValue.ROW_1_LEFT in area.values }?.value
            val quiet = driverBounds?.let { (it.minValue + 1).coerceAtMost(it.maxValue) }
            if (quiet != null && fan > quiet) {
                call<Int> { ok, fail -> hvac.setHvacFanSpeed(driver, quiet, { _, v -> ok(v) }, fail) }
            }
        }
        return RestSnapshot(temperatureC = temp, fanSpeed = fan)
    }

    override suspend fun exitRest(snapshot: RestSnapshot) {
        val hvac = vehicle.getHVAC()
        snapshot.temperatureC?.let { t ->
            call<Float> { ok, fail -> hvac.setHvacTemperature(driver, t, TemperatureUnit.CELSIUS, { _, v -> ok(v) }, fail) }
        }
        snapshot.fanSpeed?.let { f ->
            call<Int> { ok, fail -> hvac.setHvacFanSpeed(driver, f, { _, v -> ok(v) }, fail) }
        }
    }

    /**
     * Runs one SDK call and waits for its onComplete/onFailed callback.
     * Returns null on failure, timeout, a null result, or if the SDK throws synchronously.
     */
    private suspend fun <T> call(block: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit): T? {
        inFlight.incrementAndGet()
        // Decrement exactly once: on the callback, or on timeout/throw if no callback ever comes.
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        fun settle() { if (settled.compareAndSet(false, true)) inFlight.decrementAndGet() }
        return try {
            withTimeoutOrNull(CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine<T?> { cont ->
                    // A null value from the SDK is treated like a failure.
                    val ok: (T?) -> Unit = { v -> settle(); if (cont.isActive) cont.resume(v) }
                    val fail: (Exception) -> Unit = { e ->
                        settle()
                        Log.w(TAG, "vehicle call failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                    try {
                        block(ok, fail)
                    } catch (e: Exception) {
                        Log.w(TAG, "vehicle call threw", e)
                        settle()
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        } finally {
            // On timeout the callback may still arrive later; it is harmless then.
            settle()
        }
    }

    companion object {
        private const val TAG = "PleosVehicle"
        private const val CALL_TIMEOUT_MS = 1500L
        private const val RELEASE_WAIT_MS = 10_000L
        private const val OPAQUE = 0xFF000000.toInt()
        private const val REST_TEMPERATURE_C = 24f

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
