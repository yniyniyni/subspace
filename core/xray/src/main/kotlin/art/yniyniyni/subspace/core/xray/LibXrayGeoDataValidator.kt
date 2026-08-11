// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.xray

import art.yniyniyni.subspace.core.model.GeoDataKind
import art.yniyniyni.subspace.core.model.GeoDataValidator
import art.yniyniyni.subspace.core.model.GeoValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * [GeoDataValidator] over libXray's `countGeoData`.
 *
 * `geo/count.go` reads `path.Join(datDir, name+".dat")`, `proto.Unmarshal`s it
 * into a `GeoSiteList` or `GeoIPList`, and writes `path.Join(datDir, name+".json")`
 * listing every code with its rule count — see research §5, which quotes it.
 *
 * That second effect is why this method is worth calling rather than parsing the
 * protobuf ourselves: the emitted JSON is the rule editor's category list, and
 * getting it here costs nothing. Parsing it ourselves would mean a protobuf
 * dependency (§10.7) for a job libXray already does.
 *
 * §5.3: `countGeoData` parses up to 74 MB of protobuf, so it runs on IO.
 */
public class LibXrayGeoDataValidator
@Inject
constructor() : GeoDataValidator {
    override suspend fun validate(
        datDir: File,
        name: String,
        kind: GeoDataKind,
    ): GeoValidation =
        withContext(Dispatchers.IO) {
            val dat = File(datDir, "$name.dat")
            // Two separate reasons this check exists, and the second is the
            // important one:
            //
            //  - libXray reports an absent file and a corrupt one as the same
            //    failed envelope, and §5.6 forbids inspecting its error string,
            //    which can quote the path.
            //  - **protobuf accepts zero bytes as a valid empty message.** A
            //    truncated or empty download would therefore pass countGeoData
            //    and be installed as a geo database containing nothing — every
            //    rule silently matching no traffic. Removing this line
            //    reintroduces exactly that.
            if (!dat.isFile || dat.length() == 0L) return@withContext GeoValidation.Unreadable

            val payload =
                JSONObject()
                    .put("datDir", datDir.absolutePath)
                    .put("name", name)
                    .put("geoType", kind.wireValue)

            try {
                LibXrayInvoke.call("countGeoData", payload)
                GeoValidation.Valid
            } catch (e: XrayException) {
                // §5.6: the class name only. libXray's message quotes the path it
                // failed to parse, and a custom source's filename is user data.
                android.util.Log.w(TAG, "geo validation rejected a file: ${e.javaClass.simpleName}")
                GeoValidation.NotGeoData
            }
        }

    private companion object {
        const val TAG = "GeoDataValidator"
    }
}
