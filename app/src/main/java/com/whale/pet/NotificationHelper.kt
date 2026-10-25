package com.whale.pet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val SERVICE_CHANNEL = "whale_pet_service"
    const val CHAT_CHANNEL = "whale_pet_chat"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL,
                "桌宠陪伴服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持鲸鱼娘在手机桌面上陪伴你"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHAT_CHANNEL,
                "鲸鱼娘的通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "对话回复、碎碎念与养成提醒"
                setShowBadge(true)
            }
        )
    }

    fun canNotify(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun serviceNotification(context: Context, petCount: Int): Notification {
        ensureChannels(context)
        val openPi = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            context,
            11,
            Intent(context, PetOverlayService::class.java).setAction(PetOverlayService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(context, SERVICE_CHANNEL)
            .setContentTitle("鲸鱼娘正在桌面上陪着你")
            .setContentText("$petCount 只桌宠 · 单击摸头 · 长按打开动作菜单")
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "让她休息", stopPi).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    fun showChatReply(context: Context, petId: String, petName: String, text: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, ChatActivity::class.java)
            .putExtra(ChatActivity.EXTRA_PET_ID, petId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context,
            ("chat_$petId").hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, CHAT_CHANNEL)
            .setContentTitle(petName)
            .setContentText(text.take(80))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(("reply_$petId").hashCode(), n)
    }

    fun showCompanion(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context,
            12,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(context, CHAT_CHANNEL)
            .setContentTitle(title)
            .setContentText(text.take(80))
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(title.hashCode(), n)
    }
}
