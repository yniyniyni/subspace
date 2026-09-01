// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
package space.getsub.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads `ANDROID_ID` and hashes it (§A.4.1).
 *
 * The only Android-coupled part of HWID derivation, kept separate so
 * [deriveHwid]'s panel-pattern guarantees are provable on the JVM.
 */
@Singleton
public class AndroidHwidProvider
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
) : HwidProvider {
    // HardwareIds: this is exactly the identifier §A.4.1 specifies, and it is
    // hashed before transmission. ANDROID_ID is scoped to app-signing-key +
    // user + device — it is not a hardware serial, IMEI, MAC or advertising ID,
    // all of which §A.4.1 rules out.
    @SuppressLint("HardwareIds")
    override fun hwid(): String =
        deriveHwid(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                .orEmpty(),
        )
}
