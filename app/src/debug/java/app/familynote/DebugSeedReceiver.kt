package app.familynote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only: adds a text note.
 * adb shell am broadcast --user 10 -a app.familynote.debug.ADD_TEXT -n app.familynote/.DebugSeedReceiver \
 *   --es text "아빠 힘내" --es from 딸 --es to 아빠
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = intent.getStringExtra("from") ?: "딸"
        val to = intent.getStringExtra("to") ?: "아빠"
        // Optional "daysAgo" (int) back-dates the note, so demo notes show varied dates.
        val at = System.currentTimeMillis() - intent.getIntExtra("daysAgo", 0) * 86_400_000L
        val app = context.applicationContext as FamilyNoteApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_ADD_DRAWING -> {
                        // A picture streamed into files/import/ via run-as (see tools/import_drawings.sh).
                        val name = intent.getStringExtra("file") ?: return@launch
                        val file = File(File(context.filesDir, "import"), File(name).name)
                        val bmp = withContext(Dispatchers.IO) { decodeScaled(file, 1600) }
                        if (bmp == null) {
                            Log.w("DebugSeed", "cannot decode $file")
                        } else {
                            runCatching { app.repository.saveDrawing(bmp, from, to, at) }
                                .onFailure { Log.w("DebugSeed", "save failed", it) }
                        }
                        file.delete()
                    }
                    else -> intent.getStringExtra("text")?.let { text ->
                        runCatching { app.repository.saveText(text, from, to, at = at) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / maxOf(raw.width, raw.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
    }

    companion object {
        const val ACTION_ADD_DRAWING = "app.familynote.debug.ADD_DRAWING"
    }
}
