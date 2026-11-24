package com.v2ray.ang.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil

object CustomOutboundFmt : FmtBase() {
    fun parse(str: String): Pair<ProfileItem, String> {
        val config = ProfileItem.create(EConfigType.CUSTOM_OUTBOUND)

        val outbound = JsonUtil.fromJson(str, V2rayConfig.OutboundBean::class.java)

        config.remarks = outbound?.tag ?: System.currentTimeMillis().toString()
        config.server = outbound?.getServerAddress()
        config.serverPort = outbound?.getServerPort()?.toString()

        // PattNG: the outbound as written, tagged as the proxy, rather than as the outbound model reads it, which holds
        // only some of its keys and adds defaults of its own, see toOutbound
        val rawOutbound = outbound?.let {
            JsonUtil.toJsonPretty(taggedAsProxy(JsonParser.parseString(str).asJsonObject))
        } ?: ""
        return Pair(config, rawOutbound)
    }

    /**
     * PattNG: [outbound] with the proxy's tag first, where the outbound model writes it, and its other keys as written.
     */
    private fun taggedAsProxy(outbound: JsonObject): JsonObject =
        JsonObject().apply {
            addProperty("tag", AppConfig.TAG_PROXY)
            for ((key, value) in outbound.entrySet()) {
                if (key != "tag") add(key, value)
            }
        }

    /**
     * PattNG: the outbound of the custom outbound [raw], as its profile stores it, carrying [raw] itself: the outbound
     * model holds only some of its keys, so its JSON goes into the configuration as written, see [asWritten]. Null when
     * [raw] holds no outbound; a failure to read it is logged.
     */
    fun toOutbound(raw: String): V2rayConfig.OutboundBean? =
        JsonUtil.fromJsonSafe(raw, V2rayConfig.OutboundBean::class.java)?.apply { customOutbound = raw }

    /**
     * PattNG: the custom outbound [written], as its profile stores it, with what the app changed on its outbound, which
     * the configuration has as [built]: what the app set there or removed is set or removed in it as well, and every
     * other key stays as written, whether the outbound model holds it or not, without the defaults the model adds. An
     * object is compared key by key; anything else, an array say, is taken whole.
     */
    fun asWritten(written: String, built: JsonObject): JsonObject {
        val outbound = JsonParser.parseString(written).asJsonObject
        // The outbound as the app read it, before it changed anything, see toOutbound.
        val read = JsonUtil.toJsonPretty(JsonUtil.fromJson(written, V2rayConfig.OutboundBean::class.java))
            ?.let { JsonParser.parseString(it).asJsonObject } ?: return built
        applyChanges(outbound, read, built)
        return outbound
    }

    /** Sets in [target] what [after] sets differently from [before], and removes from it what [after] removed. */
    private fun applyChanges(target: JsonObject, before: JsonObject, after: JsonObject) {
        for ((key, value) in after.entrySet()) {
            val old = before.get(key)
            if (value == old) continue
            val current = target.get(key)
            if (old is JsonObject && value is JsonObject && current is JsonObject) {
                applyChanges(current, old, value)
            } else {
                target.add(key, value.deepCopy())
            }
        }
        for (key in before.keySet()) {
            if (!after.has(key)) target.remove(key)
        }
    }
}
