// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.service

/** Debug-native synchronization used only by [Tun2SocksTest]. */
internal object Tun2SocksNativeTestHook {
    init {
        System.loadLibrary("tun2socks")
    }

    @JvmStatic
    private external fun nativeArmPause()

    @JvmStatic
    private external fun nativeAwaitPaused(timeoutMillis: Long): Boolean

    @JvmStatic
    private external fun nativeAwaitQuitReturned(timeoutMillis: Long): Boolean

    @JvmStatic
    private external fun nativeRelease()

    fun armPause(): Unit = nativeArmPause()

    fun awaitPaused(timeoutMillis: Long): Boolean = nativeAwaitPaused(timeoutMillis)

    fun awaitQuitReturned(timeoutMillis: Long): Boolean = nativeAwaitQuitReturned(timeoutMillis)

    fun release(): Unit = nativeRelease()
}
