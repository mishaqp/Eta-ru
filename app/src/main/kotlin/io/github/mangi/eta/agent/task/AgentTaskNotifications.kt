package io.github.mangi.eta.agent.task

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.MainActivity

internal object AgentTaskNotifications {
    private const val CHANNEL_ID = "eta_agent_tasks"
    private const val CHANNEL_NAME = "Eta tasks"
    private const val CHANNEL_DESCRIPTION = "Background Eta task results"

    fun show(
        context: Context,
        task: io.github.mangi.eta.data.db.AgentTaskEntity,
        outcome: String,
        detail: String?,
    ) {
        val appContext = context.applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) return

        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = CHANNEL_DESCRIPTION },
            )
        }
        val success = outcome == AgentTaskOutcomes.SUCCESS
        val title = if (success) "Eta: ${task.name}" else "Eta task failed: ${task.name}"
        val body = when {
            success && !detail.isNullOrBlank() -> detail.take(MAX_DETAIL_CHARS)
            success -> "Task completed"
            !detail.isNullOrBlank() -> detail.take(MAX_DETAIL_CHARS)
            else -> "Task did not complete"
        }
        val intent = PendingIntent.getActivity(
            appContext,
            task.id.hashCode(),
            android.content.Intent(appContext, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )
        manager.notify(
            task.id.hashCode(),
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(intent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private const val MAX_DETAIL_CHARS = 500
}
