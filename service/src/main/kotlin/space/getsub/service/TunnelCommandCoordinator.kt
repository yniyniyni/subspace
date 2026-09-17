// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

import android.os.Messenger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal sealed interface TunnelCommand {
    data class Connect(
        val profile: ProfileParcel,
        val startId: Int,
        val testObserver: Messenger? = null,
    ) : TunnelCommand

    data class RejectConnect(
        val startId: Int,
        val rowId: Long,
    ) : TunnelCommand

    data class Disconnect(val startId: Int) : TunnelCommand

    data object ReapplyPerApp : TunnelCommand

    /** Spec §3.1: re-decide what should be running, on the one ordering point. */
    data class Reconcile(val trigger: ReconcileTrigger) : TunnelCommand
}

/**
 * The single service-owned ordering point for tunnel session commands.
 *
 * One lambda per [TunnelCommand] variant plus [observeConnect]'s debug-test hook —
 * seven genuinely distinct handlers, not one bundle hiding as several. Spec §3.1
 * added [reconcile] here rather than a second channel precisely so a reconnect is
 * a session mutation like any other, ordered against the rest by this one class.
 */
@Suppress("LongParameterList")
internal class TunnelCommandCoordinator(
    scope: CoroutineScope,
    private val connect: suspend (ProfileParcel, Int) -> Unit,
    private val rejectConnect: suspend (Int, Long) -> Unit,
    private val disconnect: suspend (Int) -> Unit,
    private val reapplyPerApp: suspend () -> Unit,
    private val reconcile: suspend (ReconcileTrigger) -> Unit = {},
    private val observeConnect: (TunnelCommand.Connect) -> Unit = {},
) {
    private val commands = Channel<TunnelCommand>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is TunnelCommand.Connect -> {
                        observeConnect(command)
                        connect(command.profile, command.startId)
                    }
                    is TunnelCommand.RejectConnect -> rejectConnect(command.startId, command.rowId)
                    is TunnelCommand.Disconnect -> disconnect(command.startId)
                    TunnelCommand.ReapplyPerApp -> reapplyPerApp()
                    is TunnelCommand.Reconcile -> reconcile(command.trigger)
                }
            }
        }
    }

    /** Enqueues [command] without running session mutations on the calling Binder thread. */
    fun enqueue(command: TunnelCommand): Boolean = commands.trySend(command).isSuccess

    fun close() {
        commands.close()
    }
}

/**
 * Makes framework start-token ownership and command enqueue order one decision.
 *
 * A Binder disconnect snapshots the latest start id while holding the same lock
 * that publishes a later started-service command. Consequently, a disconnect
 * ordered before a later connect can only call `stopSelfResult` for the older
 * token; Android will refuse to stop the newer started-service lifetime.
 */
internal class TunnelCommandIngress(
    private val enqueue: (TunnelCommand) -> Boolean,
) {
    private val lock = Any()
    private var latestStartId = 0

    fun started(
        profile: ProfileParcel?,
        startId: Int,
        testObserver: Messenger? = null,
    ): Boolean =
        synchronized(lock) {
            latestStartId = startId
            val command =
                if (profile?.toProfile() == null) {
                    TunnelCommand.RejectConnect(
                        startId = startId,
                        rowId = profile?.rowId ?: ProfileParcel.UNASSIGNED_ROW_ID,
                    )
                } else {
                    TunnelCommand.Connect(profile, startId, testObserver)
                }
            enqueue(command)
        }

    fun disconnect(): Boolean =
        synchronized(lock) {
            enqueue(TunnelCommand.Disconnect(latestStartId))
        }

    fun reapplyPerApp(): Boolean =
        synchronized(lock) {
            enqueue(TunnelCommand.ReapplyPerApp)
        }

    /**
     * A framework start with no connect request in hand: always-on, boot, or the
     * sticky restart after `:bg` dies (spec §1.3/§4). Records [startId] the same
     * way [started] does, under the same lock, so a later disconnect or a
     * reconcile-decided start resolves against this framework lifetime rather
     * than a stale one.
     */
    fun reconcile(
        trigger: ReconcileTrigger,
        startId: Int,
    ): Boolean =
        synchronized(lock) {
            latestStartId = startId
            enqueue(TunnelCommand.Reconcile(trigger))
        }

    /**
     * The most recent framework start token — what a reconcile-decided
     * [TunnelCommand.Connect] resumes under. Exposed rather than duplicated: a
     * second copy of this field would reintroduce the ordering bug this class
     * exists to prevent.
     */
    fun latestStartId(): Int = synchronized(lock) { latestStartId }
}
