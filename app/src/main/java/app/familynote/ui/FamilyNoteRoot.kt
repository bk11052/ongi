package app.familynote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familynote.ui.screens.DrawScreen
import app.familynote.ui.screens.DrivingScreen
import app.familynote.ui.screens.GalleryScreen
import app.familynote.ui.screens.HomeScreen
import app.familynote.ui.screens.WriteScreen
import app.familynote.ui.theme.Palette
import app.familynote.ui.theme.Type
import app.familynote.vehicle.DriveState
import app.familynote.arrival.ArrivalMonitor
import app.familynote.data.FamilySettings

@Composable
fun FamilyNoteRoot(vm: MainViewModel) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val drive by vm.drive.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val ambient by vm.ambient.collectAsStateWithLifecycle()
    val resting by vm.resting.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val driver by vm.driver.collectAsStateWithLifecycle()
    val arrival by vm.arrival.collectAsStateWithLifecycle()
    val waiting = notes.count { !it.seen && FamilySettings.isFor(it, driver) }
    val glow = animatedGlowColor(ambient)

    // One line per screen change; the emulator scenario tests read it (tools/scenarios.sh).
    LaunchedEffect(drive, screen, draft != null, resting) {
        // Fixed names (not class names), so they survive code shrinking in the release build.
        val name = if (drive != DriveState.PARKED) "Driving" else when (screen) {
            Screen.Home -> "Home"
            Screen.Draw -> "Draw"
            is Screen.Write -> "Write"
            is Screen.Gallery -> "Gallery"
        }
        android.util.Log.i("OngiState", "screen=$name draft=${draft != null} resting=$resting")
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(drive) {
        if (drive != DriveState.PARKED) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    // Anything but P (including "not known yet") shows only the static driving screen.
    val view: Any = if (drive != DriveState.PARKED) drive else screen

    BackHandler(enabled = drive == DriveState.PARKED && screen != Screen.Home) { vm.back() }

    Box(Modifier.fillMaxSize().background(Palette.Night).ambientGlow({ glow.value })) {
        AnimatedContent(
            targetState = view,
            transitionSpec = {
                if (targetState is DriveState) {
                    // Leaving P: the old screen must disappear at once so nothing stays touchable.
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(tween(700)) togetherWith fadeOut(tween(500))
                }
            },
            contentKey = { it::class },
            label = "screen",
        ) { target ->
            when (target) {
                is DriveState -> DrivingScreen(
                    unseen = waiting,
                    connecting = target == DriveState.UNKNOWN,
                    hasDraft = draft != null,
                )
                Screen.Home -> HomeScreen(
                    notes = notes,
                    waiting = waiting,
                    driver = driver,
                    onDriverChange = vm::setDriver,
                    loadBitmap = vm::loadBitmap,
                    onDraw = { vm.open(Screen.Draw) },
                    onWrite = { vm.open(Screen.Write()) },
                    onGallery = { vm.openGallery(it) },
                    hasDraft = draft != null,
                    onDelete = vm::delete,
                )
                Screen.Draw -> DrawScreen(
                    draft = draft,
                    onLeave = vm::keepDraft,
                    onCancel = vm::back,
                    onSave = { bmp, from, to, done -> vm.saveDrawing(bmp, from, to, done) },
                )
                is Screen.Write -> WriteScreen(
                    replyTo = target.replyTo,
                    driver = driver,
                    onListen = vm::listen,
                    onStopListening = vm::stopListening,
                    onCancel = vm::back,
                    onSave = { text, from, to, done ->
                        vm.saveText(text, from, to, target.replyTo?.id, done)
                    },
                )
                is Screen.Gallery -> GalleryScreen(
                    notes = notes,
                    // Show the trip line only right after an arrival that opened the gallery.
                    greeting = arrival
                        ?.takeIf { target.auto && System.currentTimeMillis() - it.at < 5 * 60_000 }
                        ?.let { ArrivalMonitor.tripLine(it.trip) }
                        ?.let { "$it. 수고했어요" },
                    order = target.order,
                    newIds = target.newIds,
                    startId = target.startId,
                    resting = resting,
                    loadBitmap = vm::loadBitmap,
                    onNoteShown = vm::onNoteShown,
                    onToggleRest = { vm.setResting(!resting) },
                    onRead = vm::readAloud,
                    onReply = { vm.open(Screen.Write(replyTo = it)) },
                    onClose = vm::closeGallery,
                )
            }
        }
        message?.let {
            Text(
                it,
                style = Type.Label.copy(color = Palette.Night),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Palette.Amber)
                    .padding(horizontal = 32.dp, vertical = 16.dp),
            )
        }
    }
}
