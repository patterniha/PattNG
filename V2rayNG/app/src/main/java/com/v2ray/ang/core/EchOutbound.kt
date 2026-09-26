package com.v2ray.ang.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig.OutboundBean
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.util.JsonUtil

/**
 * PattNG: the ECH outbound of a profile is a whole Xray outbound, written as raw JSON, that the ECH
 * config query is sent through (tlsSettings.echSockopt.dialerProxy). It is appended to the generated
 * configuration after every other outbound, exactly as written.
 */
object EchOutbound {

    enum class Error { INVALID_JSON, NEEDS_ECH_CONFIG_LIST, INVALID_TAG }

    sealed interface AppendResult {
        data class Done(val content: String) : AppendResult
        data class TagConflict(val tag: String) : AppendResult
    }

    /**
     * Checks the ECH outbound of [profile]: a JSON object, next to an echConfigList, with a tag that is
     * not empty, not direct or block, and does not start with proxy (balancers pick their members by
     * that prefix, so such an outbound would carry the proxied traffic).
     *
     * @return null when the ECH outbound is empty or valid.
     */
    fun validate(profile: ProfileItem): Error? {
        if (profile.echOutbound.isNullOrBlank()) return null
        val outbound = parse(profile.echOutbound) ?: return Error.INVALID_JSON
        if (profile.echConfigList.isNullOrBlank()) return Error.NEEDS_ECH_CONFIG_LIST
        val tag = tagOf(outbound)
        if (tag.isNullOrBlank() || tag == AppConfig.TAG_DIRECT || tag == AppConfig.TAG_BLOCKED ||
            tag.startsWith(AppConfig.TAG_PROXY)
        ) {
            return Error.INVALID_TAG
        }
        return null
    }

    /** The ECH outbound of [profile] when it is set and valid. */
    fun outboundOf(profile: ProfileItem): JsonObject? {
        if (profile.echOutbound.isNullOrBlank() || validate(profile) != null) return null
        return parse(profile.echOutbound)
    }

    fun tagOf(outbound: JsonObject): String? {
        val tag = outbound.get("tag")
        return if (tag != null && tag.isJsonPrimitive && tag.asJsonPrimitive.isString) tag.asString else null
    }

    /**
     * Points the echSockopt of every outbound that carries an ECH outbound (attached by
     * CoreOutboundBuilder) at the tag that ECH outbound has in the configuration. Outbounds with the
     * same ECH outbound share it. A different ECH outbound under a tag that an earlier one has gets a
     * numbered tag of its own ("ech-2"): the tag only links a proxy outbound to its ECH outbound, and
     * a group, a chain or the routing outbounds put unrelated profiles in one configuration.
     *
     * @return the ECH outbounds to append, each with its tag.
     */
    fun link(outbounds: List<OutboundBean>): List<JsonObject> {
        val linked = mutableListOf<Pair<JsonObject, String>>()
        for (outbound in outbounds) {
            val tlsSettings = outbound.streamSettings?.tlsSettings ?: continue
            val echOutbound = tlsSettings.echOutbound ?: continue
            val tag = linked.firstOrNull { it.first == echOutbound }?.second
                ?: uniqueTag(tagOf(echOutbound).orEmpty(), linked.map { it.second }).also { linked.add(echOutbound to it) }
            tlsSettings.echSockopt = OutboundBean.StreamSettingsBean.SockoptBean(dialerProxy = tag)
        }
        return linked.map { (echOutbound, tag) -> echOutbound.deepCopy().apply { addProperty("tag", tag) } }
    }

    /**
     * Appends [echOutbounds] to the configuration [content] after every other outbound. A tag that
     * another outbound of the configuration already has is a conflict rather than a redirect of the
     * ECH config query.
     */
    fun appendTo(content: String, echOutbounds: List<JsonObject>): AppendResult {
        if (echOutbounds.isEmpty()) return AppendResult.Done(content)
        val config = parse(content) ?: return AppendResult.Done(content)
        val outbounds = config.get("outbounds") as? JsonArray ?: JsonArray().also { config.add("outbounds", it) }
        val usedTags = outbounds.mapNotNull { outbound -> (outbound as? JsonObject)?.let { tagOf(it) } }.toSet()
        val conflict = echOutbounds.mapNotNull { tagOf(it) }.firstOrNull { it in usedTags }
        if (conflict != null) return AppendResult.TagConflict(conflict)
        echOutbounds.forEach { outbounds.add(it) }
        return AppendResult.Done(JsonUtil.toJsonPretty(config) ?: content)
    }

    private fun uniqueTag(tag: String, taken: List<String>): String {
        var unique = tag
        var number = 2
        while (unique in taken) {
            unique = "$tag-${number++}"
        }
        return unique
    }

    /** Parses like JsonUtil.parseString, without logging: the text is the user's, not a failure to report. */
    private fun parse(json: String?): JsonObject? =
        json?.let { runCatching { JsonParser.parseString(it) as? JsonObject }.getOrNull() }
}
