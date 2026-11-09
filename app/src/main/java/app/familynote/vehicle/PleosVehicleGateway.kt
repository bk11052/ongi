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
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Talks to the Pleos Vehicle Service. Every SDK call is asynchronous IPC, so each one is
 * wrapped into a suspend function with a timeout; failures are logged and reported as
 * null/false instead of crashing the app.
 *
 * Safety: the drive state fails closed. Anything unexpected (no reading, listener failure,
 * a fresh start) is UNKNOWN, which the UI treats as "not parked".
 *
 * Cabin settings the app changes (ambient light, climate) are saved to disk before the first
 * change and cleared only after they were written back successfully, so a crash or a failed
 * restore is retried on the next start ([recoverPendingCabin]).
 */
class PleosVehicleGateway(
    context: Context,
    private val scope: CoroutineScope,
) : VehicleGateway {

    private val vehicle = Vehicle(context.applicationContext)
    private val driver = SeatArea(SeatValue.ROW_1_LEFT)
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cabin_restore", Context.MODE_PRIVATE)

    private val _drive = MutableStateFlow(DriveState.UNKNOWN)
    private val _ambient = MutableStateFlow<Int?>(null)
    private val _ambientSupported = MutableStateFlow(false)
    override val driveState: StateFlow<DriveState> = _drive.asStateFlow()
    override val ambientColor: StateFlow<Int?> = _ambient.asStateFlow()
    override val ambientSupported: StateFlow<Boolean> = _ambientSupported.asStateFlow()

    /** Seat areas whose ambient color can be set; filled by the capability check. */
    @Volatile private var ambientAreas: List<SeatArea> = emptyList()

    /** SDK calls whose callback has not arrived yet; release() waits for zero. */
    private val inFlight = AtomicInteger(0)
    private var startJob: Job? = null
    private var started = false

    /** Set by the listener; the initial read must not overwrite a newer listener value. */
    @Volatile private var listenerReported = false

    /** Completes once the service answered (or gave up), so recovery can wait for it. */
    private val ready = MutableStateFlow(false)

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            listenerReported = true
            _drive.value = gear?.toDriveState() ?: DriveState.UNKNOWN
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "gear listener failed", e)
            listenerReported = true
            _drive.value = DriveState.UNKNOWN
        }
    }

    override fun start() {
        if (started) return
        started = true
        // Never trust a value from a previous session: the car may have moved since.
        _drive.value = DriveState.UNKNOWN
        listenerReported = false
        ready.value = false
        vehicle.initialize()
        startJob = scope.launch {
            // Subscribe first so a gear change during the initial read is not missed.
            var registered = false
            var gear: VehicleGear? = null
            // The service connection is set up asynchronously, and at boot the vehicle service
            // may come up late: keep retrying with backoff (about 2 minutes in total).
            var wait = 500L
            for (attempt in 1..30) {
                if (!registered) {
                    registered = runCatching { vehicle.getGear().registerGearSelection(gearListener) }
                        .onFailure { Log.w(TAG, "registerGearSelection failed (attempt $attempt)", it) }
                        .isSuccess
                }
                gear = call<VehicleGear> { ok, fail -> vehicle.getGear().getCurrentGearSelectionState(ok, fail) }
                if (registered && gear != null) break
                delay(wait)
                wait = (wait * 3 / 2).coerceAtMost(8_000L)
            }
            if (!registered) {
                Log.w(TAG, "gear updates unavailable; staying UNKNOWN until the next start()")
                _drive.value = DriveState.UNKNOWN
                // Allow a later start() (e.g. when the screen comes back) to try again.
                started = false
                runCatching { vehicle.release() }
                return@launch
            } else if (gear != null && !listenerReported) {
                _drive.value = gear.toDriveState()
            }
            detectAmbientAreas()
            ready.value = true
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        startJob?.cancel()
        _drive.value = DriveState.UNKNOWN
        runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
        // release() must not run while callbacks are pending.
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

    // ---- Ambient light ----------------------------------------------------------------

    override suspend fun setAmbientColor(argb: Int): Boolean {
        val color = argb or OPAQUE
        _ambient.value = color
        val areas = ambientAreas
        if (areas.isEmpty()) return false
        if (!prefs.contains(KEY_AMBIENT)) {
            // Remember the cabin's own setting once (on disk), so it can always be put back.
            val saved = areas.map { area ->
                val c = call<Int> { ok, fail -> vehicle.getLight().getAmbientLightBrightnessColor(area, ok, fail) }
                val s = call<LightSwitch> { ok, fail -> vehicle.getLight().getAmbientLightSwitch(area, ok, fail) }
                SavedAmbient(area.values.map { it.name }, c, s?.name)
            }
            prefs.edit().putString(KEY_AMBIENT, SavedAmbient.encode(saved)).commit()
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

    override suspend fun restoreAmbient(): Boolean {
        val encoded = prefs.getString(KEY_AMBIENT, null) ?: run {
            _ambient.value = null
            return true
        }
        var allOk = true
        for (saved in SavedAmbient.decode(encoded)) {
            val area = saved.toArea() ?: continue
            if (saved.color != null) {
                // The car reports the color without alpha (e.g. 0x00FFFFFF) but only accepts
                // opaque values, so force alpha to 0xFF when writing it back.
                val r = call<Int> { ok, fail ->
                    vehicle.getLight().setAmbientLightBrightnessColor(area, saved.color or OPAQUE, { _, v -> ok(v) }, fail)
                }
                if (r == null) allOk = false
            }
            val switch = saved.switch?.let { runCatching { LightSwitch.valueOf(it) }.getOrNull() }
            if (switch != null) {
                val r = call<LightSwitch> { ok, fail ->
                    vehicle.getLight().setAmbientLightSwitch(area, switch, { _, v -> ok(v) }, fail)
                }
                if (r == null) allOk = false
            }
        }
        if (allOk) {
            prefs.edit().remove(KEY_AMBIENT).commit()
            _ambient.value = null
        } else {
            Log.w(TAG, "ambient restore incomplete; will retry")
        }
        return allOk
    }

    // ---- Rest mode (climate) ----------------------------------------------------------

    override suspend fun enterRest(): RestSnapshot {
        val hvac = vehicle.getHVAC()
        val temp = call<Float> { ok, fail -> hvac.getHvacTemperature(driver, TemperatureUnit.CELSIUS, ok, fail) }
        val fan = call<Int> { ok, fail -> hvac.getHvacFanSpeed(driver, ok, fail) }
        val snapshot = RestSnapshot(temperatureC = temp, fanSpeed = fan)
        // Save before changing anything, so a crash in between can still be undone.
        prefs.edit()
            .putString(KEY_REST, "${temp ?: ""}|${fan ?: ""}")
            .commit()

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
        return snapshot
    }

    override suspend fun exitRest(snapshot: RestSnapshot): Boolean {
        val hvac = vehicle.getHVAC()
        var allOk = true
        snapshot.temperatureC?.let { t ->
            val r = call<Float> { ok, fail -> hvac.setHvacTemperature(driver, t, TemperatureUnit.CELSIUS, { _, v -> ok(v) }, fail) }
            if (r == null) allOk = false
        }
        snapshot.fanSpeed?.let { f ->
            val r = call<Int> { ok, fail -> hvac.setHvacFanSpeed(driver, f, { _, v -> ok(v) }, fail) }
            if (r == null) allOk = false
        }
        if (allOk) prefs.edit().remove(KEY_REST).commit() else Log.w(TAG, "climate restore incomplete; will retry")
        return allOk
    }

    override suspend fun recoverPendingCabin() {
        if (!prefs.contains(KEY_REST) && !prefs.contains(KEY_AMBIENT)) return
        // Wait (briefly) for the service connection before writing anything.
        withTimeoutOrNull(RECOVER_WAIT_MS) { ready.first { it } }
        prefs.getString(KEY_REST, null)?.let { s ->
            val (t, f) = s.split('|').let { it.getOrNull(0) to it.getOrNull(1) }
            exitRest(RestSnapshot(t?.toFloatOrNull(), f?.toIntOrNull()))
        }
        restoreAmbient()
    }

    // ---- Trip -------------------------------------------------------------------------

    override suspend fun tripSummary(): TripSummary? {
        val odo = vehicle.getOdometer()
        val km = call<Float> { ok, fail -> odo.getTripCurrentDrivingDistance(ok, fail) } ?: return null
        // Trip time is reported in seconds.
        val seconds = call<Int> { ok, fail -> odo.getTripCurrentDrivingTime(ok, fail) }
        Log.i(TAG, "trip: ${km}km, ${seconds}s")
        return TripSummary(km, seconds?.let { it / 60 })
    }

    // ---- Plumbing ---------------------------------------------------------------------

    /**
     * Runs one SDK call and waits for its onComplete/onFailed callback.
     * Returns null on failure, timeout, a null result, or if the SDK throws synchronously.
     */
    private suspend fun <T> call(block: (ok: (T?) -> Unit, fail: (Exception) -> Unit) -> Unit): T? {
        inFlight.incrementAndGet()
        // Decrement exactly once: on the callback, or on timeout/throw if no callback ever comes.
        val settled = AtomicBoolean(false)
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

    /** One area's original ambient setting, in a form that survives process death. */
    private data class SavedAmbient(val seats: List<String>, val color: Int?, val switch: String?) {
        fun toArea(): SeatArea? = runCatching {
            SeatArea(*seats.map { SeatValue.valueOf(it) }.toTypedArray())
        }.getOrNull()

        companion object {
            // Format per area: SEAT+SEAT;color;switch — areas separated by '/'.
            fun encode(list: List<SavedAmbient>) =
                list.joinToString("/") { "${it.seats.joinToString("+")};${it.color ?: ""};${it.switch ?: ""}" }

            fun decode(s: String): List<SavedAmbient> = s.split('/').filter { it.isNotBlank() }.mapNotNull { part ->
                val f = part.split(';')
                if (f.size != 3) return@mapNotNull null
                SavedAmbient(f[0].split('+').filter { it.isNotBlank() }, f[1].toIntOrNull(), f[2].ifBlank { null })
            }
        }
    }

    companion object {
        private const val TAG = "PleosVehicle"
        private const val CALL_TIMEOUT_MS = 1500L
        private const val RELEASE_WAIT_MS = 10_000L
        private const val RECOVER_WAIT_MS = 8_000L
        private const val OPAQUE = 0xFF000000.toInt()
        private const val REST_TEMPERATURE_C = 24f
        private const val KEY_AMBIENT = "ambient"
        private const val KEY_REST = "rest"

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
