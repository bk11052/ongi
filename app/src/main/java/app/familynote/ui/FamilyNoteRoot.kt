package app.familynote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familynote.ui.screens.DrawScreen
import app.familynote.ui.screens.DrivingScreen
import app.familynote.ui.screens.GalleryScreen
import app.familynote.ui.screens.HomeScreen
import app.familynote.ui.screens.WriteScreen
import app.familynote.ui.theme.Palette
import app.familynote.vehicle.DriveState

@Composable
fun FamilyNoteRoot(vm: MainViewModel) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val drive by vm.drive.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val ambient by vm.ambient.collectAsStateWithLifecycle()
    val resting by vm.resting.collectAsStateWithLifecycle()
    val glow = animatedGlowColor(ambient)

    // Anything but P (including "not known yet") shows only the static driving screen.
    val view: Any = if (drive != DriveState.PARKED) drive else screen

    BackHandler(enabled = drive == DriveState.PARKED && screen != Screen.Home) { vm.back() }

    Box(Modifier.fillMaxSize().background(Palette.Night).ambientGlow(glow)) {
        AnimatedContent(
            targetState = view,
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
            contentKey = { it::class },
            label = "screen",
        ) { target ->
            when (target) {
                is DriveState -> DrivingScreen(
                    unseen = notes.count { !it.seen },
                    connecting = target == DriveState.UNKNOWN,
                )
                Screen.Home -> HomeScreen(
                    notes = notes,
                    loadBitmap = vm::loadBitmap,
                    onDraw = { vm.open(Screen.Draw) },
                    onWrite = { vm.open(Screen.Write()) },
                    onGallery = { vm.open(Screen.Gallery(auto = false)) },
                    onDelete = vm::delete,
                )
                Screen.Draw -> DrawScreen(
                    onCancel = vm::back,
                    onSave = { bmp, from, to -> vm.saveDrawing(bmp, from, to) { vm.back() } },
                )
                is Screen.Write -> WriteScreen(
                    replyTo = target.replyTo,
                    onListen = vm::listen,
                    onStopListening = vm::stopListening,
                    onCancel = vm::back,
                    onSave = { text, from, to ->
                        vm.saveText(text, from, to, target.replyTo?.id) { vm.back() }
                    },
                )
                is Screen.Gallery -> GalleryScreen(
                    notes = notes,
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
    }
}
