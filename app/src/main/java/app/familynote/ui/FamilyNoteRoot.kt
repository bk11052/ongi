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

@Composable
fun FamilyNoteRoot(vm: MainViewModel) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val drive by vm.drive.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val ambient by vm.ambient.collectAsStateWithLifecycle()
    val resting by vm.resting.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val glow = animatedGlowColor(ambient)
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

    Box(Modifier.fillMaxSize().background(Palette.Night).ambientGlow(glow)) {
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
                    unseen = notes.count { !it.seen },
                    connecting = target == DriveState.UNKNOWN,
                )
                Screen.Home -> HomeScreen(
                    notes = notes,
                    loadBitmap = vm::loadBitmap,
                    onDraw = { vm.open(Screen.Draw) },
                    onWrite = { vm.open(Screen.Write()) },
                    onGallery = vm::openGallery,
                    onDelete = vm::delete,
                )
                Screen.Draw -> DrawScreen(
                    onCancel = vm::back,
                    onSave = { bmp, from, to, done -> vm.saveDrawing(bmp, from, to, done) },
                )
                is Screen.Write -> WriteScreen(
                    replyTo = target.replyTo,
                    onListen = vm::listen,
                    onStopListening = vm::stopListening,
                    onCancel = vm::back,
                    onSave = { text, from, to, done ->
                        vm.saveText(text, from, to, target.replyTo?.id, done)
                    },
                )
                is Screen.Gallery -> GalleryScreen(
                    notes = notes,
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
                style = Type.Label,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}
