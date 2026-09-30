package app.escudo.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.escudo.android.MainActivity
import app.escudo.android.R

object ApprovalNotifications {
  const val CHANNEL_ID = "approvals"

  /** Deep link into the approvals screen; also usable from adb for rehearsals. */
  fun deepLink(approvalId: String?): Uri =
    Uri.parse(if (approvalId == null) "escudo://approvals" else "escudo://approvals/$approvalId")

  fun createChannel(context: Context) {
    val channel = NotificationChannel(
      CHANNEL_ID,
      context.getString(R.string.notification_channel_approvals),
      NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = context.getString(R.string.notification_channel_approvals_description) }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  fun show(context: Context, push: ApprovalPush) {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val intent = Intent(Intent.ACTION_VIEW, deepLink(push.approvalId), context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(
      context, push.approvalId.hashCode(), intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(push.title ?: context.getString(R.string.notification_approval_title))
      .setContentText(push.body ?: context.getString(R.string.notification_approval_body))
      .setStyle(NotificationCompat.BigTextStyle().bigText(push.body ?: context.getString(R.string.notification_approval_body)))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_REMINDER)
      .setAutoCancel(true)
      .setContentIntent(pending)
      .build()
    NotificationManagerCompat.from(context).notify(push.approvalId.hashCode(), notification)
  }
}
