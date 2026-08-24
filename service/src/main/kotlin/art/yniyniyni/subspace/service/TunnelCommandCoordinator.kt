// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal sealed interface TunnelCommand {
    data class Connect(val profile: ProfileParcel) : TunnelCommand

    data object Disconnect : TunnelCommand

    data object ReapplyPerApp : TunnelCommand
}

/** The single service-owned ordering point for tunnel session commands. */
internal class TunnelCommandCoordinator(
    scope: CoroutineScope,
    private val connect: suspend (ProfileParcel) -> Unit,
    private val disconnect: suspend () -> Unit,
    private val reapplyPerApp: suspend () -> Unit,
) {
    private val commands = Channel<TunnelCommand>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is TunnelCommand.Connect -> connect(command.profile)
                    TunnelCommand.Disconnect -> disconnect()
                    TunnelCommand.ReapplyPerApp -> reapplyPerApp()
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
