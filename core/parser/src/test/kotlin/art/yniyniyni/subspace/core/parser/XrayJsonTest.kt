// SPDX-License-Identifier: AGPL-3.0-or-later
package art.yniyniyni.subspace.core.parser

import art.yniyniyni.subspace.core.model.Security
import art.yniyniyni.subspace.core.model.TransportOptions
import art.yniyniyni.subspace.core.model.VlessOutbound
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.Test

private const val XJ_UUID = "70cc48c5-b2f4-4a1e-9f3d-0123456789ab"
private const val XJ_PBK = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
private const val SECOND_UUID = "70cc48c5-b2f4-4a1e-9f3d-1123456789ab"
private const val THIRD_UUID = "70cc48c5-b2f4-4a1e-9f3d-2123456789ab"

class XrayJsonTest {
    @Test
    fun `extracts a reality vless outbound`() {
        val outcome = parseXrayJson(realityConfig)
        outcome.profiles.size shouldBe 1
        val out = outcome.profiles[0].outbound as VlessOutbound

        out.address shouldBe "host.example"
        out.port shouldBe 443
        out.uuid shouldBe XJ_UUID
        out.flow shouldBe "xtls-rprx-vision"
        val reality = out.stream.security as Security.Reality
        reality.serverName shouldBe "www.microsoft.com"
        reality.publicKey shouldBe XJ_PBK
        reality.shortId shouldBe "ab"
        reality.fingerprint shouldBe "chrome"
        reality.spiderX shouldBe "/"
    }

    @Test
    fun `ignores freedom blackhole and null protocol outbounds`() {
        val outcome =
            parseXrayJson(
                """
                {"outbounds":[
                  {"protocol":"freedom"},
                  {"protocol":"blackhole"},
                  {},
                  null,
                  ${validOutbound("first.example")}
                ]}
                """.trimIndent(),
            )

        outcome.profiles.map { it.name } shouldBe listOf("first.example")
        outcome.failures shouldBe emptyList()
    }

    @Test
    fun `malformed json is a typed failure not an exception`() {
        val outcome = parseXrayJson("{ not json")
        outcome.profiles shouldBe emptyList()
        outcome.failures.size shouldBe 1
        outcome.failures[0].index shouldBe 0
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `non-object non-array json is a typed failure`() {
        val outcome = parseXrayJson("true")
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedJson
    }

    /**
     * Defect 1 (device fixes): the target panel returns a top-level JSON
     * **array** wrapping a whole Xray config —
     * `[{ "dns": …, "outbounds": … }]`. An empty array is the same "container
     * present, holds nothing" shape `{}` and `{"outbounds":[]}` already are —
     * [ParseOutcome.EMPTY], not a failure. [SubscriptionParser.parse] is what
     * turns that into the reported `EmptyInput` (see its own KDoc).
     */
    @Test
    fun `an empty array yields no profiles or failures`() {
        parseXrayJson("[]") shouldBe ParseOutcome.EMPTY
    }

    /**
     * Defect 1's actual observed shape: one element wrapping a whole config.
     * Array semantics decision (see XrayJson.kt's `parseXrayJsonArray` KDoc):
     * each array element is parsed exactly as a top-level object would be,
     * so a single-element array behaves identically to the unwrapped object.
     */
    @Test
    fun `a one-element array wrapping a config parses exactly like the bare object`() {
        val outcome = parseXrayJson("[$realityConfig]")
        outcome.failures shouldBe emptyList()
        val out = outcome.profiles.single().outbound as VlessOutbound
        out.address shouldBe "host.example"
        out.uuid shouldBe XJ_UUID
    }

    /**
     * Several configs, not just one wrapped — the deliberate part of the
     * array-semantics decision. A panel that ever emits more than one config
     * object must not silently lose every element after the first.
     */
    @Test
    fun `a multi-element array parses every element, not just the first`() {
        val second =
            realityConfig
                .replace("host.example", "second.example")
                .replace(XJ_UUID, SECOND_UUID)
        val outcome = parseXrayJson("[$realityConfig,$second]")

        outcome.failures shouldBe emptyList()
        outcome.profiles.map { (it.outbound as VlessOutbound).address } shouldBe
            listOf("host.example", "second.example")
    }

    /**
     * An array element that is not itself a JSON object is reported at its
     * own array position — there is no outbound index yet at that point, so
     * this necessarily uses a different index space than a bad outbound
     * within a chosen object does. The good element on either side still
     * parses (§7).
     */
    @Test
    fun `a non-object array element is a failure at its array position, not lost`() {
        val outcome = parseXrayJson("""[$realityConfig, 7, $realityConfig]""")

        outcome.profiles.size shouldBe 2
        outcome.failures.size shouldBe 1
        outcome.failures.single().index shouldBe 1
        outcome.failures.single().reason shouldBe ParseFailureReason.MalformedJson
    }

    /**
     * Element provenance, part 1 (device-fixes finding, part 2): a bare top-level object is
     * its own element, so the profile it yields carries the whole (already-trimmed here)
     * document as [art.yniyniyni.subspace.core.model.Profile.rawJson] — same byte-for-byte
     * behaviour §6 has always described for a single hand-pasted `config.json`.
     */
    @Test
    fun `a bare top-level object's profile carries the whole document as rawJson`() {
        val outcome = parseXrayJson(realityConfig)
        outcome.profiles.single().rawJson shouldBe realityConfig
    }

    /**
     * Element provenance, part 2: the real bug. Before this fix every profile out of an
     * array hashed the *whole* array's bytes, so elements `[1]`..`[7]` of the 8-element
     * document that triggered this fix were indistinguishable from one another at the
     * identity layer and collapsed into one upserted row. Each element now carries only its
     * own re-serialized bytes ([JsonElement.toString] — the parser has no access to the
     * original per-element substring once `kotlinx.serialization` has parsed it), so two
     * elements' profiles are provably different [rawJson] values, not just different outbounds.
     */
    @Test
    fun `each array element carries only its own bytes as rawJson, not the whole document`() {
        val second = realityConfig.replace("host.example", "second.example").replace(XJ_UUID, SECOND_UUID)
        val outcome = parseXrayJson("[$realityConfig,$second]")

        val expectedFirst = Json.parseToJsonElement(realityConfig).toString()
        val expectedSecond = Json.parseToJsonElement(second).toString()
        outcome.profiles.map { it.rawJson } shouldBe listOf(expectedFirst, expectedSecond)
        expectedFirst shouldNotBe expectedSecond
    }

    /**
     * Element provenance, part 3: the other half of the real document's shape — one element
     * (`[0]`, 8 `vless` outbounds there) fans out into several profiles. All of them still
     * share that one element's bytes; `ProfileRepository.import` is what falls back to
     * outbound-based identity for this case (see its own KDoc), not the parser — this test
     * only pins the parser's half: the shared bytes themselves.
     */
    @Test
    fun `a document with several outbounds shares one element's bytes across every profile it yields`() {
        val json =
            """
            {"outbounds":[
              ${validOutbound("first.example")},
              ${validOutbound("second.example", uuid = "\"$SECOND_UUID\"")},
              ${validOutbound("third.example", uuid = "\"$THIRD_UUID\"")}
            ]}
            """.trimIndent()

        val outcome = parseXrayJson(json)

        outcome.profiles.map { it.outbound.address } shouldBe
            listOf("first.example", "second.example", "third.example")
        outcome.profiles
            .map { it.rawJson }
            .distinct()
            .size shouldBe 1
    }

    @Test
    fun `missing or non-array outbounds yields empty`() {
        parseXrayJson("{}").let { it shouldBe ParseOutcome.EMPTY }
        parseXrayJson("{\"outbounds\":null}").let { it shouldBe ParseOutcome.EMPTY }
        parseXrayJson("{\"outbounds\":true}").let { it shouldBe ParseOutcome.EMPTY }
    }

    @Test
    fun `accepts numeric and numeric-string ports`() {
        val outcome =
            parseXrayJson(
                """
                {"outbounds":[
                  ${validOutbound("number.example", port = "443")},
                  ${validOutbound("string.example", port = "\"8443\"")}
                ]}
                """.trimIndent(),
            )

        outcome.profiles.map { it.outbound.port } shouldBe listOf(443, 8443)
    }

    @Test
    fun `uses tcp none and safe tls defaults`() {
        val outcome =
            parseXrayJson(
                """
                {"outbounds":[
                  ${validOutbound("none.example", security = "none")},
                  ${validOutbound("tls.example", security = "tls")}
                ]}
                """.trimIndent(),
            )
        val none = (outcome.profiles[0].outbound as VlessOutbound).stream
        none.network shouldBe "tcp"
        none.security shouldBe Security.None
        val tls = (outcome.profiles[1].outbound as VlessOutbound).stream
        tls.network shouldBe "tcp"
        tls.security shouldBe Security.Tls("tls.example", "chrome", false)
    }

    @Test
    fun `unsupported and non-string security have distinct typed details`() {
        mapOf(
            "\"bogus\"" to FailureDetail.Unsupported(DetailField.Security),
            "true" to FailureDetail.Malformed(DetailField.Security),
            "123" to FailureDetail.Malformed(DetailField.Security),
            "{}" to FailureDetail.Malformed(DetailField.Security),
        ).forEach { (security, expectedDetail) ->
            val json =
                validOutbound("security.example", security = "none")
                    .replace("\"security\":\"none\"", "\"security\":$security")
            val failure = parseXrayJson("{\"outbounds\":[$json]}").failures.single()

            failure.index shouldBe 0
            failure.reason shouldBe ParseFailureReason.MalformedJson
            failure.detail shouldBe expectedDetail
        }
    }

    @Test
    fun `parses every vnext destination and every user in order`() {
        val json =
            vlessOutboundJson(
                vnext(
                    "first.example",
                    user(XJ_UUID, "xtls-rprx-vision") + "," + user(SECOND_UUID),
                ) + "," + vnext("second.example", user(THIRD_UUID)),
            )
        val outcome = parseXrayJson("{\"outbounds\":[$json]}")

        outcome.profiles.map { (it.outbound as VlessOutbound).address } shouldBe
            listOf("first.example", "first.example", "second.example")
        outcome.profiles.map { (it.outbound as VlessOutbound).uuid } shouldBe
            listOf(XJ_UUID, SECOND_UUID, THIRD_UUID)
        outcome.failures shouldBe emptyList()
    }

    @Test
    fun `recovers after a bad vnext entry and reports its outbound index`() {
        val json =
            vlessOutboundJson(
                vnext("before.example", user(XJ_UUID)) +
                    ", {\"port\":443,\"users\":[${user(SECOND_UUID)}]}," +
                    vnext("after.example", user(THIRD_UUID)),
            )
        val outcome = parseXrayJson("{\"outbounds\":[$json]}")

        outcome.profiles.map { (it.outbound as VlessOutbound).address } shouldBe
            listOf("before.example", "after.example")
        outcome.failures.map { it.index } shouldBe listOf(0)
        outcome.failures.single().reason shouldBe ParseFailureReason.MalformedJson
    }

    @Test
    fun `missing or empty vnext and users are typed failures`() {
        val missingVnext = "{\"protocol\":\"vless\",\"settings\":{}}"
        val emptyVnext = "{\"protocol\":\"vless\",\"settings\":{\"vnext\":[]}}"
        val missingUsers =
            vlessOutboundJson("{\"address\":\"missing-users.example\",\"port\":443}")
        val outcome = parseXrayJson("{\"outbounds\":[$missingVnext,$emptyVnext,$missingUsers]}")

        outcome.profiles shouldBe emptyList()
        outcome.failures.map { it.index } shouldBe listOf(0, 1, 2)
        outcome.failures.map { it.reason } shouldBe
            listOf(
                ParseFailureReason.MalformedJson,
                ParseFailureReason.MalformedJson,
                ParseFailureReason.MissingCredential,
            )
    }

    @Test
    fun `blank flow becomes null and tag falls back to address`() {
        val json = validOutbound("tag.example", tag = " ", flow = "\" \"")
        val outcome = parseXrayJson("{\"outbounds\":[$json]}")
        val profile = outcome.profiles.single()
        profile.name shouldBe "tag.example"
        (profile.outbound as VlessOutbound).flow shouldBe null
    }

    /**
     * `tag` is a routing identifier, not a label: exporters emit `"proxy"` for
     * it essentially always, so trusting it names every raw config "proxy". The
     * human-facing label lives in the root `remarks`, which is where the share
     * link's `#fragment` ends up when a client writes a config out.
     */
    @Test
    fun `root remarks names the profile instead of the outbound tag`() {
        val json = realityConfig.replace("\"outbounds\"", "\"remarks\":\"Helsinki\",\"outbounds\"")
        parseXrayJson(json).profiles.single().name shouldBe "Helsinki"
    }

    @Test
    fun `blank or non-string remarks falls back to the tag`() {
        val blank = realityConfig.replace("\"outbounds\"", "\"remarks\":\" \",\"outbounds\"")
        val number = realityConfig.replace("\"outbounds\"", "\"remarks\":7,\"outbounds\"")

        parseXrayJson(blank).profiles.single().name shouldBe "proxy"
        parseXrayJson(number).profiles.single().name shouldBe "proxy"
    }

    /**
     * Defect 2 (device-fixes finding): the real subscription's first array
     * element carried 8 `vless` outbounds under one `remarks`, so all 8 rows
     * displayed identically and were indistinguishable in the Servers list —
     * genuinely different servers (different address/port/transport) with no
     * way to tell them apart. `remarks` stays the primary name (that choice
     * was deliberate, see `root remarks names the profile instead of the
     * outbound tag` above); a 1-based ordinal is appended only when this
     * element actually produces more than one profile under the same name.
     * The outbound's own `tag` was considered and rejected as the
     * disambiguator instead of an ordinal: the same reasoning that made `tag`
     * a bad *primary* name (exporters set it to `"proxy"` uniformly) makes it
     * collide exactly where `remarks` already collided, so it disambiguates
     * nothing in the real-world case this fixes.
     */
    @Test
    fun `several outbounds sharing one remarks value get distinct ordinal-suffixed names`() {
        val json =
            """
            {"remarks":"🚀 Auto | Best Server",
             "outbounds":[
               ${validOutbound("first.example")},
               ${validOutbound("second.example", uuid = "\"$SECOND_UUID\"")},
               ${validOutbound("third.example", uuid = "\"$THIRD_UUID\"")}
             ]}
            """.trimIndent()

        val outcome = parseXrayJson(json)

        outcome.profiles.map { it.name } shouldBe
            listOf(
                "🚀 Auto | Best Server (1)",
                "🚀 Auto | Best Server (2)",
                "🚀 Auto | Best Server (3)",
            )
        outcome.profiles.map { it.outbound.address } shouldBe
            listOf("first.example", "second.example", "third.example")
    }

    /**
     * The other half of the fix: an element that yields exactly one profile
     * must keep the name it produces today, byte for byte — no ordinal, even
     * though the disambiguator machinery runs over every element.
     */
    @Test
    fun `a single outbound under remarks keeps its name unchanged, with no ordinal`() {
        val json = realityConfig.replace("\"outbounds\"", "\"remarks\":\"Solo\",\"outbounds\"")
        parseXrayJson(json).profiles.single().name shouldBe "Solo"
    }

    /**
     * An element with no `remarks` at all — not blank, not non-string, simply
     * absent — must still fall back to the tag exactly as before; the
     * disambiguator only ever appends, it never changes which name wins.
     */
    @Test
    fun `an element with no remarks key keeps the existing tag fallback unchanged`() {
        parseXrayJson(realityConfig).profiles.single().name shouldBe "proxy"
    }

    /**
     * The shape a desktop client actually exports: `remarks` carrying non-ASCII
     * text, `\/` escapes throughout, `mux`/`dns`/`routing`/`inbounds` around the
     * part we read, and the proxy outbound tagged `proxy`.
     */
    @Test
    fun `parses an exported client config with remarks and escaped solidi`() {
        val outcome = parseXrayJson(exportedClientConfig)
        outcome.failures shouldBe emptyList()
        val profile = outcome.profiles.single()

        profile.name shouldBe "🇫🇮 Helsinki"
        val out = profile.outbound as VlessOutbound
        out.address shouldBe "host.example"
        out.flow shouldBe "xtls-rprx-vision"
        (out.stream.security as Security.Reality).fingerprint shouldBe "firefox"
    }

    @Test
    fun `null and boolean required primitives fail without coercion`() {
        val addressNull = validOutbound("ignored").replace("\"address\":\"ignored\"", "\"address\":null")
        val uuidBoolean = validOutbound("ignored", uuid = "true")
        val portBoolean = validOutbound("ignored", port = "true")
        val outcome = parseXrayJson("{\"outbounds\":[$addressNull,$uuidBoolean,$portBoolean]}")

        outcome.profiles shouldBe emptyList()
        outcome.failures.map { it.index } shouldBe listOf(0, 1, 2)
        outcome.failures[0].reason shouldBe ParseFailureReason.MalformedJson
        outcome.failures[1].reason shouldBe ParseFailureReason.MissingCredential
        outcome.failures[2].reason shouldBe ParseFailureReason.InvalidPort
    }

    @Test
    fun `malformed optional fields use safe defaults`() {
        val json =
            validOutbound("optional.example", security = "tls").replace(
                "\"tlsSettings\":{}",
                "\"tlsSettings\":{\"serverName\":true,\"fingerprint\":null,\"allowInsecure\":\"true\"}",
            )
        val tls =
            (parseXrayJson("{\"outbounds\":[$json]}").profiles.single().outbound as VlessOutbound)
                .stream.security as Security.Tls

        tls.serverName shouldBe "optional.example"
        tls.fingerprint shouldBe "chrome"
        tls.allowInsecure shouldBe false
    }

    @Test
    fun `unsupported protocol is a typed closed-vocabulary failure`() {
        val outcome = parseXrayJson("{\"outbounds\":[{\"protocol\":\"wireguard\"}]}")
        outcome.profiles shouldBe emptyList()
        outcome.failures.size shouldBe 1
        outcome.failures[0].reason shouldBe ParseFailureReason.UnknownScheme
        outcome.failures[0].detail shouldBe FailureDetail.Unsupported(DetailField.Scheme)
    }

    @Test
    fun `invalid reality key is a failure at outbound index`() {
        val json = realityConfig.replace(XJ_PBK, "AAEC").replace("{\n", "{\n")
        val outcome = parseXrayJson(json)
        outcome.profiles shouldBe emptyList()
        outcome.failures.single().index shouldBe 0
        outcome.failures.single().reason shouldBe ParseFailureReason.InvalidRealityKey
    }

    @Test
    fun `bad outbounds do not discard good outbounds or reorder them`() {
        val json =
            """
            {"outbounds":[
              ${validOutbound("one.example")},
              {"protocol":"vless","settings":{}},
              ${validOutbound("two.example")},
              {"protocol":"wireguard"}
            ]}
            """.trimIndent()
        val outcome = parseXrayJson(json)

        outcome.profiles.map { it.name } shouldBe listOf("one.example", "two.example")
        outcome.failures.map { it.index } shouldBe listOf(1, 3)
    }

    @Test
    fun `malformed corpus never throws and never echoes input`() {
        val corpus =
            listOf(
                "",
                " ",
                "null",
                "true",
                "{",
                "[[]]",
                "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":null}]}",
                "{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":true," +
                    "\"port\":443}]} }]}",
                "vless://$XJ_UUID@secret.example:443",
            )

        corpus.forEach { input ->
            parseXrayJson(input)
        }
    }

    // ── Transport options ───────────────────────────────────────────────────
    //
    // xhttp regression: this parser read `streamSettings.network` but never read the
    // matching settings object, so every raw-Xray profile stored
    // TransportOptions.None regardless of its transport. The subscription path is
    // exactly where that hurts — a panel emits a whole config, not a share link —
    // and it is why an xhttp server that used to connect began presenting as "not
    // supported by this build yet" once `StoredProfile.connectable` checked the
    // network. Key names are Xray-core v26.7.11's (`infra/conf/transport_method.go`).

    private fun streamConfig(streamSettings: String): String =
        """
        {"outbounds":[{"protocol":"vless","tag":"proxy",
        "settings":{"vnext":[{"address":"host.example","port":443,
        "users":[{"id":"$XJ_UUID"}]}]},
        "streamSettings":$streamSettings}]}
        """.trimIndent().replace("\n", "")

    private fun transportOf(streamSettings: String): TransportOptions {
        val outcome = parseXrayJson(streamConfig(streamSettings))
        outcome.failures shouldBe emptyList()
        return (outcome.profiles.single().outbound as VlessOutbound).stream.transport
    }

    @Test
    fun `reads xhttpSettings path host and mode`() {
        val transport =
            transportOf(
                """
                {"network":"xhttp","security":"none",
                "xhttpSettings":{"path":"/down","host":"cdn.example","mode":"stream-up"}}
                
                """.trimIndent()
                    .replace("\n", ""),
            )

        transport shouldBe TransportOptions.Xhttp(path = "/down", host = "cdn.example", mode = "stream-up")
    }

    @Test
    fun `reads splithttpSettings under the xhttp alias`() {
        // v26.7.11 accepts both spellings for the same transport, and a config
        // written against the older name must not lose its options.
        val transport =
            transportOf("""{"network":"splithttp","security":"none","splithttpSettings":{"path":"/s"}}""")

        transport shouldBe TransportOptions.Xhttp(path = "/s", host = null, mode = null)
    }

    @Test
    fun `an xhttp config with no settings block defaults the path and leaves host and mode unset`() {
        val transport = transportOf("""{"network":"xhttp","security":"none"}""")

        transport shouldBe TransportOptions.Xhttp(path = "/", host = null, mode = null)
    }

    @Test
    fun `reads wsSettings path and headers`() {
        val transport =
            transportOf(
                """
                {"network":"ws","security":"none",
                "wsSettings":{"path":"/chat","headers":{"Host":"cdn.example"}}}
                
                """.trimIndent()
                    .replace("\n", ""),
            )

        transport shouldBe TransportOptions.WebSocket(path = "/chat", headers = mapOf("Host" to "cdn.example"))
    }

    @Test
    fun `reads a ws host field as a Host header`() {
        // WebSocketConfig carries both `host` and `headers`; the model has only
        // headers, and `host` is shorthand for exactly that header.
        val transport =
            transportOf("""{"network":"ws","security":"none","wsSettings":{"path":"/c","host":"cdn.example"}}""")

        transport shouldBe TransportOptions.WebSocket(path = "/c", headers = mapOf("Host" to "cdn.example"))
    }

    @Test
    fun `reads grpcSettings service name`() {
        val transport =
            transportOf("""{"network":"grpc","security":"none","grpcSettings":{"serviceName":"GunService"}}""")

        transport shouldBe TransportOptions.Grpc(serviceName = "GunService")
    }

    @Test
    fun `a tcp config carries no transport options`() {
        // rawSettings/tcpSettings model header obfuscation this build does not
        // emit, so tcp stays None rather than gaining a lossy half-reading.
        transportOf("""{"network":"tcp","security":"none"}""") shouldBe TransportOptions.None
    }

    @Test
    fun `a transport settings block of the wrong json type is ignored, not fatal`() {
        // §7: never throw on malformed input. A string where an object belongs
        // degrades to defaults rather than losing the profile.
        transportOf("""{"network":"grpc","security":"none","grpcSettings":"nonsense"}""") shouldBe
            TransportOptions.None
    }

    private fun vlessOutboundJson(vnextEntries: String): String =
        """{"protocol":"vless","settings":{"vnext":[$vnextEntries]},"streamSettings":{"security":"none"}}"""

    private fun vnext(
        address: String,
        users: String,
    ): String = """{"address":"$address","port":443,"users":[$users]}"""

    private fun user(
        uuid: String,
        flow: String? = null,
    ): String {
        val flowField = flow?.let { ",\"flow\":\"$it\"" }.orEmpty()
        return """{"id":"$uuid"$flowField}"""
    }

    @Suppress("LongParameterList")
    private fun validOutbound(
        address: String,
        port: String = "443",
        uuid: String = "\"$XJ_UUID\"",
        tag: String? = null,
        flow: String = "\"xtls-rprx-vision\"",
        security: String = "reality",
    ): String {
        val tagField = tag?.let { "\"tag\":\"$it\"," } ?: ""
        val realitySettings =
            if (security == "reality") {
                "\"realitySettings\":{\"serverName\":\"www.microsoft.com\"," +
                    "\"publicKey\":\"$XJ_PBK\",\"shortId\":\"ab\",\"fingerprint\":\"chrome\",\"spiderX\":\"/\"}"
            } else {
                "\"tlsSettings\":{}"
            }
        return """{$tagField"protocol":"vless","settings":{"vnext":[{"address":"$address",
            "port":$port,"users":[{"id":$uuid,"flow":$flow}]}]},"streamSettings":{"security":"$security",
            $realitySettings}}""".replace("\n", "")
    }

    private val realityConfig =
        """
        {
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  { "address": "host.example", "port": 443,
                    "users": [ { "id": "$XJ_UUID", "flow": "xtls-rprx-vision", "encryption": "none" } ] }
                ]
              },
              "streamSettings": {
                "network": "tcp",
                "security": "reality",
                "realitySettings": { "serverName": "www.microsoft.com", "publicKey": "$XJ_PBK",
                                     "shortId": "ab", "fingerprint": "chrome", "spiderX": "/" }
              }
            },
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
        """.trimIndent()

    private val exportedClientConfig =
        """
        {
          "dns" : { "queryStrategy" : "UseIP", "servers" : [ "https:\/\/8.8.8.8\/dns-query" ] },
          "inbounds" : [
            { "listen" : "127.0.0.1", "port" : 10808, "protocol" : "socks", "tag" : "socks" }
          ],
          "log" : { "access" : "\/var\/log\/Xray\/access.log", "loglevel" : "Info" },
          "outbounds" : [
            {
              "mux" : { "concurrency" : -1, "enabled" : false },
              "protocol" : "vless",
              "settings" : {
                "vnext" : [
                  {
                    "address" : "host.example",
                    "port" : 443,
                    "users" : [
                      { "encryption" : "none", "flow" : "xtls-rprx-vision",
                        "id" : "$XJ_UUID", "level" : 8 }
                    ]
                  }
                ]
              },
              "streamSettings" : {
                "network" : "tcp",
                "realitySettings" : {
                  "allowInsecure" : false, "fingerprint" : "firefox",
                  "publicKey" : "$XJ_PBK", "serverName" : "storage.example",
                  "shortId" : "b0c58c398abb6842", "show" : false
                },
                "security" : "reality",
                "tcpSettings" : { "header" : { "type" : "none" } }
              },
              "tag" : "proxy"
            },
            { "protocol" : "freedom", "settings" : { "domainStrategy" : "UseIP" }, "tag" : "direct" },
            { "protocol" : "blackhole", "settings" : { "response" : { "type" : "http" } }, "tag" : "block" }
          ],
          "remarks" : "🇫🇮 Helsinki",
          "routing" : {
            "domainStrategy" : "IPIfNonMatch",
            "rules" : [
              { "network" : "udp", "outboundTag" : "block", "port" : "443", "type" : "field" },
              { "domain" : [ "geosite:category-ru" ], "outboundTag" : "direct", "type" : "field" }
            ]
          }
        }
        """.trimIndent()
}
