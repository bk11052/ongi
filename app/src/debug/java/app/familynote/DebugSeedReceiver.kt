package app.familynote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Debug-only: adds a text note.
 * adb shell am broadcast --user 10 -a app.familynote.debug.ADD_TEXT -n app.familynote/.DebugSeedReceiver \
 *   --es text "아빠 힘내" --es from 딸 --es to 아빠
 */
class DebugSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val from = intent.getStringExtra("from") ?: "딸"
        val to = intent.getStringExtra("to") ?: "아빠"
        val app = context.applicationContext as FamilyNoteApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                runCatching { app.repository.saveText(text, from, to) }
            } finally {
                pending.finish()
            }
        }
    }
}
