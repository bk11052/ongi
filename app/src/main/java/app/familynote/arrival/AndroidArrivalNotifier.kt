package app.familynote.arrival

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.familynote.MainActivity
import app.familynote.R

class AndroidArrivalNotifier(private val context: Context) : ArrivalNotifier {

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ARRIVAL, "도착 쪽지", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "주차했을 때 가족이 남긴 쪽지를 알려 줍니다"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_WATCH, "도착 감지", NotificationManager.IMPORTANCE_MIN).apply {
                description = "주차를 감지하기 위해 백그라운드에서 실행 중입니다"
            },
        )
    }

    override fun showArrival(title: String, text: String) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_GALLERY, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "쪽지 보기", open)
            .build()
        runCatching { manager.notify(ID_ARRIVAL, n) }
    }

    override fun clear() {
        manager.cancel(ID_ARRIVAL)
    }

    fun watchingNotification() = NotificationCompat.Builder(context, CHANNEL_WATCH)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("온기")
        .setContentText("도착하면 가족의 쪽지를 보여드릴게요")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .build()

    companion object {
        const val CHANNEL_ARRIVAL = "arrival"
        const val CHANNEL_WATCH = "watch"
        const val ID_ARRIVAL = 2
        const val ID_WATCH = 1
    }
}
