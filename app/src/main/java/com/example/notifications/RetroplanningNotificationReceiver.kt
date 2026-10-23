package com.example.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.RetroplanningStep
import java.util.Calendar

class RetroplanningNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val stepTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Étape Rétroplanning Course"
        val stepSubtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: "Il est l'heure de votre prochaine étape !"
        val stepTime = intent.getStringExtra(EXTRA_TIME) ?: ""
        val notificationId = intent.getIntExtra(EXTRA_NOTIF_ID, 1001)

        showStepNotification(
            context = context,
            notificationId = notificationId,
            title = "[$stepTime] $stepTitle",
            message = stepSubtitle
        )
    }

    companion object {
        const val CHANNEL_ID = "retroplanning_race_channel"
        const val EXTRA_TITLE = "extra_step_title"
        const val EXTRA_SUBTITLE = "extra_step_subtitle"
        const val EXTRA_TIME = "extra_step_time"
        const val EXTRA_NOTIF_ID = "extra_notif_id"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Rétroplanning Course",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Rappels à chaque étape du rétroplanning avant une course (réveil, petit-déjeuner, trajet, arrivée, départ)."
                }
                manager.createNotificationChannel(channel)
            }
        }

        fun showStepNotification(
            context: Context,
            notificationId: Int,
            title: String,
            message: String
        ) {
            ensureChannel(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val tapIntent = Intent(context, MainActivity::class.java)
            val pendingTap = PendingIntent.getActivity(
                context,
                notificationId,
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingTap)
                .setAutoCancel(true)
                .build()

            manager.notify(notificationId, notification)
        }

        fun scheduleRetroplanningNotifications(
            context: Context,
            steps: List<RetroplanningStep>,
            dayOffsetFromToday: Int
        ): Int {
            ensureChannel(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val nowMillis = System.currentTimeMillis()
            var scheduledCount = 0

            steps.forEachIndexed { index, step ->
                val notifId = 2000 + index
                val intent = Intent(context, RetroplanningNotificationReceiver::class.java).apply {
                    putExtra(EXTRA_TITLE, step.title)
                    putExtra(EXTRA_SUBTITLE, step.subtitle)
                    putExtra(EXTRA_TIME, step.timeFormatted)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    notifId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val targetCal = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, dayOffsetFromToday)
                    set(Calendar.HOUR_OF_DAY, step.minutesFromMidnight / 60)
                    set(Calendar.MINUTE, step.minutesFromMidnight % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val triggerAtMillis = if (targetCal.timeInMillis <= nowMillis) {
                    // Si l'heure est déjà passée aujourd'hui, planifier pour le lendemain ou déclencher un rappel immédiat de confirmation
                    nowMillis + (index + 1) * 3000L
                } else {
                    targetCal.timeInMillis
                }

                try {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    scheduledCount++
                } catch (_: SecurityException) {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                    scheduledCount++
                }
            }

            // Envoie également une notification récapitulative immédiate confirmant la programmation des étapes
            val summaryText = steps.joinToString(" • ") { "${it.timeFormatted} ${it.title}" }
            showStepNotification(
                context = context,
                notificationId = 1999,
                title = "Rétroplanning activé (${steps.size} étapes)",
                message = summaryText
            )

            return scheduledCount
        }
    }
}
