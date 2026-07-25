// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The ongoing notification a foreground service must show for the whole life of
 * the tunnel (§9).
 */
internal object TunnelNotification {
    const val CHANNEL_ID = "tunnel"
    const val ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_tunnel),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /**
     * §5.6: [contentText] comes from `strings.xml` only. Never the profile name,
     * which is user-supplied and often the server's hostname, and never the
     * address.
     */
    fun build(
        context: Context,
        contentText: String,
    ): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.tunnel_session_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_tunnel_notification)
            .setOngoing(true)
            .build()
}
