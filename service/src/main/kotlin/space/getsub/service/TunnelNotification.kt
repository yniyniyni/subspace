// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

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
        showDisconnectAction: Boolean = true,
    ): Notification {
        val builder =
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.tunnel_session_name))
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_tunnel_notification)
                .setOngoing(true)
        if (showDisconnectAction) {
            builder.addAction(disconnectAction(context))
        }
        return builder.build()
    }

    /**
     * Spec §6.3: the way out of a wedged fail-closed session.
     *
     * An explicit intent to this service rather than the binder, because a
     * notification tap has no bound client — `:main` may not even be running.
     * IMMUTABLE because the system holds it and nothing in it needs filling in.
     */
    private fun disconnectAction(context: Context): Notification.Action {
        val intent =
            Intent(context, TunnelService::class.java).setAction(ACTION_DISCONNECT)
        // requestCode 0: this service holds a single disconnect action, never
        // more than one in flight, so there is nothing for a second code to
        // distinguish.
        val pending =
            PendingIntent.getForegroundService(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        // icon null: the platform renders a text-only action; nothing in this
        // codebase supplies a second drawable for it (Task 10's step 5 note).
        return Notification.Action.Builder(
            null,
            context.getString(R.string.notification_action_disconnect),
            pending,
        ).build()
    }
}
