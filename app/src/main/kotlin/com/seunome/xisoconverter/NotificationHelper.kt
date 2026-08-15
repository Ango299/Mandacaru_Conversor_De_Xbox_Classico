package com.seunome.xisoconverter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Centraliza a criação do canal de notificação e a montagem das
 * notificações usadas pelo [ConversionService]: uma de progresso
 * (atualizada continuamente, presa ao serviço em primeiro plano) e uma
 * final, avisando que o processo terminou (concluído, cancelado ou com
 * erro), que é a notificação que aparece na área de notificações do
 * celular mesmo com o app em segundo plano.
 */
object NotificationHelper {

    const val CHANNEL_ID = "mandacaru_conversao"
    const val PROGRESS_NOTIFICATION_ID = 1001
    const val RESULT_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Conversão XISO",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progresso e resultado das conversões do Mandacaru"
        }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    /** Notificação "em andamento", usada pelo startForeground() do serviço. */
    fun buildProgressNotification(
        context: Context,
        title: String,
        message: String,
        percent: Int
    ): android.app.Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (percent in 0..100) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true) // indeterminado
        }

        return builder.build()
    }

    /** Notificação final: processo concluído, cancelado ou com erro. */
    fun showResultNotification(context: Context, title: String, message: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentIntent(context))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(RESULT_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permissão de notificação (POST_NOTIFICATIONS) não concedida;
            // o app continua funcionando normalmente, só não mostra o aviso.
        }
    }
}
