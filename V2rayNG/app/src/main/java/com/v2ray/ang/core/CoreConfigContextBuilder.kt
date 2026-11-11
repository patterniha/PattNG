package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileEntry
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils

/**
 * Build runtime context from the selected profile.
 *
 * All outbound type analysis is completed here for both the selected profile
 * and routing targets. Custom profiles are returned immediately without
 * entering the normal analysis flow.
 */
object CoreConfigContextBuilder {

    /**
     * Load one profile and produce a fully analyzed context.
     *
     * Null is returned only when the selected profile cannot be loaded. PattNG: without [routingTargets], as for a
     * latency test, which measures the profile alone, the profiles the routing rules send to are left out; the
     * fallback of a group that is the profile stays, which its balancer names.
     */
    fun build(context: Context, guid: String, routingTargets: Boolean = true): CoreConfigContext? {
        val config = MmkvManager.decodeServerConfig(guid) ?: return null

        // CUSTOM: return immediately — CoreConfigManager handles this path on its own.
        if (config.configType == EConfigType.CUSTOM) {
            return CoreConfigContext(context = context, guid = guid, isCustom = true)
        }

        // Step 1: Resolve the main outbound (always tag = TAG_PROXY).
        val primaryResolvedOutbound = resolveOutbound(AppConfig.TAG_PROXY, ProfileEntry(guid, config)) ?: run {
            LogUtil.e(AppConfig.TAG, "Failed to resolve main outbound for '${config.remarks}'")
            return null
        }

        // Step 2: Resolve all non-builtin routing outbound tags.
        val (routingResolvedOutbounds, unresolvedRoutingTarget) =
            if (routingTargets) resolveRoutingOutbounds() else emptyList<CoreConfigContext.ResolvedOutbound>() to null
        val resolvedOutbounds = listOf(primaryResolvedOutbound) + routingResolvedOutbounds
        val (fallbackResolvedOutbounds, unresolvedFallback) = resolveFallbackOutbounds(resolvedOutbounds)
        val routingDomainRules = collectRoutingDomainRulesForDns()

        return CoreConfigContext(
            context = context,
            guid = guid,
            resolvedOutbounds = resolvedOutbounds + fallbackResolvedOutbounds,
            routingDomainRules = routingDomainRules,
            unresolvedTarget = unresolvedRoutingTarget ?: unresolvedFallback,
        )
    }

    /**
     * Resolve one outbound target into a normalized outbound entry.
     *
     * Custom profiles are ignored at this stage and produce no entry.
     */
    private fun resolveOutbound(tag: String, entry: ProfileEntry): CoreConfigContext.ResolvedOutbound? {
        if (entry.profile.configType == EConfigType.CUSTOM) {
            return null
        }

        var unresolvedHop: CoreConfigContext.UnresolvedName? = null
        val (resolvedEntries, resolvedType) = when (entry.profile.configType) {
            EConfigType.POLICYGROUP -> Pair(
                resolvePolicyGroupEntries(entry),
                CoreResolvedType.POLICYGROUP,
            )

            EConfigType.PROXYCHAIN -> {
                val (chainEntries, unresolved) = resolveProxyChainEntries(entry)
                unresolvedHop = unresolved
                val type = if (chainEntries.size <= 1) CoreResolvedType.NORMAL else CoreResolvedType.PROXYCHAIN
                Pair(chainEntries, type)
            }

            else -> {
                val (chainEntries, unresolved) = resolveProxyChainEntriesFromGroup(entry)
                unresolvedHop = unresolved
                val type = if (chainEntries.size <= 1) CoreResolvedType.NORMAL else CoreResolvedType.PROXYCHAIN
                Pair(chainEntries, type)
            }
        }

        return CoreConfigContext.ResolvedOutbound(
            tag = tag,
            entry = entry,
            resolvedEntries = resolvedEntries,
            resolvedType = resolvedType,
            unresolvedHop = unresolvedHop,
        )
    }

    /**
     * Collect and resolve non-builtin routing targets from enabled rules.
     *
     * Invalid or empty targets are skipped. PattNG: the session is then refused for them, see
     * CoreConfigManager.unbuiltRoutingTarget. A target is a profile's name; the first one that no profile has any more,
     * or several have, goes beside them, for the session to be refused for it rather than send the rule's traffic by
     * the proxy, or by a profile it may not mean.
     */
    private fun resolveRoutingOutbounds(): Pair<List<CoreConfigContext.ResolvedOutbound>, CoreConfigContext.UnresolvedName?> {
        val rulesetItems = MmkvManager.decodeRoutingRulesets() ?: return emptyList<CoreConfigContext.ResolvedOutbound>() to null
        val resolvedOutbounds = mutableListOf<CoreConfigContext.ResolvedOutbound>()
        val processedTags = mutableSetOf<String>()
        var unresolved: CoreConfigContext.UnresolvedName? = null

        try {
            rulesetItems
                .filter { it.enabled }
                .mapNotNull { it.outboundTag.takeIf { tag -> tag.isNotBlank() } }
                .filter { tag -> tag !in AppConfig.BUILTIN_OUTBOUND_TAGS }
                .distinct()
                .forEach { tag ->
                    if (tag in processedTags) {
                        return@forEach
                    }
                    processedTags.add(tag)

                    try {
                        val entry = when (val found = SettingsManager.findProfileEntryViaRemarks(tag, ::takesAsRoutingTarget)) {
                            is ByName.One -> found.value
                            ByName.None, ByName.Several -> {
                                val several = found == ByName.Several
                                LogUtil.w(AppConfig.TAG, "Routing tag '$tag' has ${if (several) "several matching profiles" else "no matching profile"}; the session is refused")
                                if (unresolved == null) unresolved = CoreConfigContext.UnresolvedName(tag.trim(), reasonOf(several))
                                return@forEach
                            }
                        }
                        val resolvedOutbound = resolveOutbound(tag, entry) ?: run {
                            LogUtil.w(AppConfig.TAG, "Cannot use CUSTOM profile as routing outbound for tag '$tag', skipping")
                            return@forEach
                        }
                        // PattNG: a chain that names a hop no profile has, or several have, stays, for the configuration to be refused for it.
                        if (resolvedOutbound.resolvedEntries.isEmpty() && resolvedOutbound.unresolvedHop == null) {
                            LogUtil.w(AppConfig.TAG, "Routing outbound '$tag' resolved to empty list, skipping")
                            return@forEach
                        }
                        resolvedOutbounds.add(resolvedOutbound)
                        LogUtil.d(AppConfig.TAG, "Resolved routing outbound: tag='$tag', type='${resolvedOutbound.resolvedType}', profiles=${resolvedOutbound.resolvedEntries.size}")
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to resolve routing outbound for tag '$tag', skipping", e)
                    }
                }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve routing outbounds from rulesets", e)
        }

        return resolvedOutbounds to unresolved
    }

    /** PattNG: whether [profile] can be what a routing rule sends to: any profile but a custom configuration. */
    internal fun takesAsRoutingTarget(profile: ProfileItem): Boolean = profile.configType != EConfigType.CUSTOM

    private fun resolvePolicyGroupEntries(entry: ProfileEntry): List<ProfileEntry> {
        try {
            val serverList = MmkvManager.decodeAllServerList()
            return serverList
                .asSequence()
                .mapNotNull { id -> MmkvManager.decodeServerConfig(id)?.let { ProfileEntry(id, it) } }
                .filter {
                    val subscriptionId = entry.profile.policyGroupSubscriptionId
                    if (subscriptionId.isNullOrBlank()) {
                        true
                    } else {
                        it.profile.subscriptionId == subscriptionId
                    }
                }
                .filter {
                    val filter = entry.profile.policyGroupFilter
                    if (filter.isNullOrBlank()) {
                        true
                    } else {
                        try {
                            Regex(filter).containsMatchIn(it.profile.remarks)
                        } catch (_: Exception) {
                            it.profile.remarks.contains(filter)
                        }
                    }
                }
                .filter { it.profile.hasDialableServer() }
                .filter { !it.profile.configType.isComplexType() }
                .toList()
                .let { members ->
                    val (kept, leftOut) = withOneAetherProfile(members, ProfileEntry::profile)
                    leftOut.forEach {
                        LogUtil.w(AppConfig.TAG, "Policy group '${entry.profile.remarks}' leaves out '${it.profile.remarks}': a second Aether profile with other settings, and one core serves one profile")
                    }
                    kept
                }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve policy group profiles for '${entry.profile.remarks}'", e)
            return listOf(entry)
        }
    }

    /**
     * PattNG: the hops of the proxy chain [entry], from the exit to the entry hop, found by their names, see
     * [proxyChainHops], and the first name that finds no profile, several, or one without a server address beside
     * them: the configuration is refused for it, rather than run without that hop, or through one it may not mean.
     */
    private fun resolveProxyChainEntries(entry: ProfileEntry): Pair<List<ProfileEntry>, CoreConfigContext.UnresolvedName?> {
        if (entry.profile.proxyChainProfiles.isNullOrBlank()) {
            return listOf(entry) to null
        }

        try {
            val (hops, unresolved) = proxyChainHops(
                ProfileItem.proxyChainMembersOf(entry.profile.proxyChainProfiles),
                SettingsManager::findProfileEntryViaRemarks,
                ProfileEntry::profile,
            )
            return hops.reversed() to unresolved
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve proxy chain profiles for '${entry.profile.remarks}'", e)
            return listOf(entry) to null
        }
    }

    /**
     * PattNG: whether [profile] can be a hop of a proxy chain: any profile but a chain, a group, a custom configuration or
     * a custom outbound.
     */
    internal fun takesAsHop(profile: ProfileItem): Boolean = !profile.configType.isComplexType()

    /**
     * PattNG: the profiles [names], the hops of a proxy chain in the order it lists them, find among those that can be a
     * hop, see [takesAsHop], and the first of the names that finds none, several, one a chain cannot go through, see
     * [hasServerAddress], or only a group, a chain, a custom configuration or a custom outbound, which is told as such
     * rather than as a name no profile has, see [HOP_REASONS]. [find] looks a name up among the profiles a filter takes,
     * see [ByName], and [profileOf] is the profile of what it finds; inline, so that an editor looks up through its own
     * source, which suspends. A blank name names no hop.
     */
    internal inline fun <T> proxyChainHops(
        names: List<String>,
        find: (String, (ProfileItem) -> Boolean) -> ByName<T>,
        profileOf: (T) -> ProfileItem,
    ): Pair<List<T>, CoreConfigContext.UnresolvedName?> {
        val hops = mutableListOf<T>()
        var unresolved: CoreConfigContext.UnresolvedName? = null
        for (name in names.map(String::trim).filter(String::isNotEmpty)) {
            val reason = when (val found = find(name, ::takesAsHop)) {
                is ByName.One -> if (hasServerAddress(profileOf(found.value))) {
                    hops += found.value
                    null
                } else {
                    CoreConfigContext.UnresolvedName.Reason.NO_SERVER
                }

                ByName.None -> reasonByType(name, HOP_REASONS, find, profileOf) ?: CoreConfigContext.UnresolvedName.Reason.NOT_FOUND
                ByName.Several -> CoreConfigContext.UnresolvedName.Reason.SEVERAL
            }
            if (reason != null && unresolved == null) unresolved = CoreConfigContext.UnresolvedName(name, reason)
        }
        return hops to unresolved
    }

    /** PattNG: [proxyChainHops] among the profiles themselves, as the editors look the hops up. */
    internal inline fun proxyChainHops(
        names: List<String>,
        find: (String, (ProfileItem) -> Boolean) -> ByName<ProfileItem>,
    ): Pair<List<ProfileItem>, CoreConfigContext.UnresolvedName?> = proxyChainHops(names, find) { it }

    /**
     * PattNG: the types of profile a chain cannot go through, see [takesAsHop], with why each is told, see
     * [reasonByType].
     */
    internal val HOP_REASONS = listOf(
        EConfigType.POLICYGROUP to CoreConfigContext.UnresolvedName.Reason.GROUP_AS_HOP,
        EConfigType.PROXYCHAIN to CoreConfigContext.UnresolvedName.Reason.CHAIN_AS_HOP,
        EConfigType.CUSTOM to CoreConfigContext.UnresolvedName.Reason.CUSTOM_AS_HOP,
        EConfigType.CUSTOM_OUTBOUND to CoreConfigContext.UnresolvedName.Reason.CUSTOM_AS_HOP,
    )

    /** PattNG: the types of profile a group cannot fall back to, see [takesAsFallback], with why each is told. */
    internal val FALLBACK_REASONS = listOf(
        EConfigType.POLICYGROUP to CoreConfigContext.UnresolvedName.Reason.GROUP_AS_FALLBACK,
        EConfigType.CUSTOM to CoreConfigContext.UnresolvedName.Reason.CUSTOM_AS_FALLBACK,
    )

    /**
     * PattNG: why [name], which finds no profile it can be used as, cannot be used: the reason of the first of
     * [reasons], in their order, whose type [find] finds a profile of the name among, or null when none does, as when no
     * profile has the name at all. A name that a profile of another type has is told by what it names, rather than as a
     * name no profile has. One lookup among the profiles of all those types tells it, as each lookup reads every
     * profile; the types are looked up one by one only when several of those profiles have the name. [profileOf] is
     * the profile of what [find] finds.
     */
    internal inline fun <T> reasonByType(
        name: String,
        reasons: List<Pair<EConfigType, CoreConfigContext.UnresolvedName.Reason>>,
        find: (String, (ProfileItem) -> Boolean) -> ByName<T>,
        profileOf: (T) -> ProfileItem,
    ): CoreConfigContext.UnresolvedName.Reason? =
        when (val found = find(name) { profile -> reasons.any { it.first == profile.configType } }) {
            ByName.None -> null
            is ByName.One -> reasons.first { it.first == profileOf(found.value).configType }.second
            ByName.Several -> reasons.firstOrNull { (type, _) -> find(name) { it.configType == type } != ByName.None }?.second
        }

    /**
     * PattNG: whether a proxy chain can go through [profile]: an Aether one, whose core it reaches on the loopback, or
     * one with a server address, whatever it is: Xray dials localhost or a name without a dot as well, and tells when it
     * cannot. A chain used to leave a hop out without a word when its address did not look like a web address.
     */
    internal fun hasServerAddress(profile: ProfileItem): Boolean =
        profile.configType == EConfigType.AETHER || !profile.server.isNullOrBlank()

    /** PattNG: why a name that finds no profile, or [several], cannot be used, see [ByName]. */
    private fun reasonOf(several: Boolean): CoreConfigContext.UnresolvedName.Reason =
        if (several) CoreConfigContext.UnresolvedName.Reason.SEVERAL else CoreConfigContext.UnresolvedName.Reason.NOT_FOUND

    /**
     * PattNG: true when [entry], selected, runs as a chain with the hops its subscription puts
     * around every one of its profiles, or would, but for a hop its subscription names that no
     * profile, or several, have, for which it is refused as a chain; see [resolveProxyChainEntriesFromGroup].
     */
    internal fun isChained(entry: ProfileEntry): Boolean =
        resolveProxyChainEntriesFromGroup(entry).let { (entries, unresolved) -> entries.size > 1 || unresolved != null }

    /**
     * Resolve chain nodes from subscription neighbors in order: next, current, prev.
     *
     * When no chain is available, return a single-node result. PattNG: the neighbors are found by their names, as the
     * hops of a chain profile are, see [proxyChainHops]; the first name that finds no profile, or several, goes beside
     * them, and the configuration is refused for it.
     */
    private fun resolveProxyChainEntriesFromGroup(entry: ProfileEntry): Pair<List<ProfileEntry>, CoreConfigContext.UnresolvedName?> {
        if (entry.profile.subscriptionId.isEmpty()) {
            return listOf(entry) to null
        }

        try {
            val subItem = MmkvManager.decodeSubscription(entry.profile.subscriptionId) ?: return listOf(entry) to null
            val (next, nextUnresolved) = proxyChainHops(listOfNotNull(subItem.nextProfile), SettingsManager::findProfileEntryViaRemarks, ProfileEntry::profile)
            val (prev, prevUnresolved) = proxyChainHops(listOfNotNull(subItem.prevProfile), SettingsManager::findProfileEntryViaRemarks, ProfileEntry::profile)
            return next + entry + prev to (nextUnresolved ?: prevUnresolved)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to resolve proxy chain from group for '${entry.profile.remarks}'", e)
            return listOf(entry) to null
        }
    }

    /**
     * Collect enabled routing domain rules in original order for DNS segmentation.
     *
     * outbounds are normalized into three tags only: proxy / direct / block.
     */
    private fun collectRoutingDomainRulesForDns(): List<CoreConfigContext.RoutingDomainRule> {
        val rulesetItems = MmkvManager.decodeRoutingRulesets() ?: return emptyList()
        val result = mutableListOf<CoreConfigContext.RoutingDomainRule>()

        rulesetItems
            .asSequence()
            .filter { it.enabled }
            .filter { !it.domain.isNullOrEmpty() }
            .forEach { rule ->
                val normalizedOutboundTag = when (rule.outboundTag) {
                    AppConfig.TAG_DIRECT -> AppConfig.TAG_DIRECT
                    AppConfig.TAG_BLOCKED -> AppConfig.TAG_BLOCKED
                    else -> AppConfig.TAG_PROXY
                }
                result.add(
                    CoreConfigContext.RoutingDomainRule(
                        domain = rule.domain.orEmpty(),
                        outboundTag = normalizedOutboundTag
                    )
                )
            }

        return result
    }

    /**
     * Resolve and collect fallback outbounds from all POLICYGROUP nodes.
     *
     * Fallback targets must not overlap with already resolved tags or builtin tags. PattNG: a target is a profile's
     * name, read as [fallbackNameOf] reads it; the first one that no profile has any more, or several have, goes beside
     * them, for the session to be refused for it rather than fall back to an outbound that is not there, or to a profile
     * it may not mean. A routing target of that name stands in for the fallback only when it can be one, see
     * [takesAsFallback]: a group, which builds a balancer and no outbound of its name, is looked up and refused as when
     * no rule sends to it.
     */
    private fun resolveFallbackOutbounds(
        resolvedOutbounds: List<CoreConfigContext.ResolvedOutbound>,
    ): Pair<List<CoreConfigContext.ResolvedOutbound>, CoreConfigContext.UnresolvedName?> {
        var unresolved: CoreConfigContext.UnresolvedName? = null
        val fallbacks = resolvedOutbounds
            .asSequence()
            .filter { it.resolvedType == CoreResolvedType.POLICYGROUP }
            .filter { BalancerStrategyType.from(it.entry.profile.policyGroupType).supportsObservatory && it.entry.profile.policyGroupTestOutbounds != false }
            .mapNotNull { fallbackNameOf(it.entry.profile) }
            .filter { it !in AppConfig.BUILTIN_OUTBOUND_TAGS && resolvedOutbounds.none { outbound -> outbound.tag == it && takesAsFallback(outbound.entry.profile) } }
            .distinct()
            .mapNotNull { tag ->
                val (entry, reason) = fallbackOf(tag, SettingsManager::findProfileEntryViaRemarks, ProfileEntry::profile)
                if (reason != null) {
                    LogUtil.w(AppConfig.TAG, "Policy group fallback '$tag' cannot be used ($reason)")
                    if (unresolved == null) unresolved = CoreConfigContext.UnresolvedName(tag, reason)
                }
                entry?.let { resolveOutbound(tag, it) }
            }
            .toList()
        return fallbacks to unresolved
    }

    /** PattNG: whether [profile] can be the fallback of a policy group: any profile but a group or a custom configuration. */
    internal fun takesAsFallback(profile: ProfileItem): Boolean =
        profile.configType != EConfigType.CUSTOM && profile.configType != EConfigType.POLICYGROUP

    /**
     * PattNG: the one profile [name], the fallback a policy group names, finds among those that can be a fallback, see
     * [takesAsFallback], or why it finds none: no such profile has the name, several have it, or only a group or a
     * custom configuration has it, which is told as such rather than as a name no profile has, see [FALLBACK_REASONS].
     * [find] looks a name up among the profiles a filter takes, see [ByName], and [profileOf] is the profile of what it
     * finds; inline, so that the group editor looks up through its own source, which suspends.
     */
    internal inline fun <T> fallbackOf(
        name: String,
        find: (String, (ProfileItem) -> Boolean) -> ByName<T>,
        profileOf: (T) -> ProfileItem,
    ): Pair<T?, CoreConfigContext.UnresolvedName.Reason?> =
        when (val found = find(name, ::takesAsFallback)) {
            is ByName.One -> found.value to null
            ByName.Several -> null to CoreConfigContext.UnresolvedName.Reason.SEVERAL
            ByName.None -> null to (reasonByType(name, FALLBACK_REASONS, find, profileOf) ?: CoreConfigContext.UnresolvedName.Reason.NOT_FOUND)
        }

    /** PattNG: [fallbackOf] among the profiles themselves, as the group editor looks the fallback up. */
    internal inline fun fallbackOf(
        name: String,
        find: (String, (ProfileItem) -> Boolean) -> ByName<ProfileItem>,
    ): Pair<ProfileItem?, CoreConfigContext.UnresolvedName.Reason?> = fallbackOf(name, find) { it }

    /**
     * PattNG: the name of the profile the policy group [profile] falls back to, trimmed, or null when it names none. The
     * outbound built for that profile is tagged with it, and the group's balancer falls back to it, see
     * CoreConfigManager.resolvePolicyGroupFallbackTag: both read it here, so that the two tags match. A blank name
     * names none; the group then falls back to its first member, rather than to an outbound of a blank tag.
     */
    internal fun fallbackNameOf(profile: ProfileItem): String? =
        profile.policyGroupFallbackTag?.trim()?.takeIf(String::isNotEmpty)

    /**
     * A group is filled by a filter rather than by named members, so it can catch several Aether
     * profiles while one core serves one of them. The first one stays, together with any whose
     * settings are the same; the others are left out. A chain or a routing rule names its profiles,
     * so a conflict there is reported instead. [profileOf] is the profile of a member.
     */
    internal fun <T> withOneAetherProfile(members: List<T>, profileOf: (T) -> ProfileItem): Pair<List<T>, List<T>> {
        var kept: AetherCore? = null
        return members.partition { member ->
            val profile = profileOf(member)
            if (profile.configType != EConfigType.AETHER) return@partition true
            val core = AetherCore.of(profile)
            when (kept) {
                null -> {
                    kept = core
                    true
                }

                core -> true
                else -> false
            }
        }
    }

    /**
     * A member the core can dial. An Aether profile has no address of its own to check: its core
     * finds the endpoint, and the outbound built for it points at that core.
     */
    private fun ProfileItem.hasDialableServer(): Boolean =
        configType == EConfigType.AETHER ||
            (server.isNotNullEmpty() && (Utils.isPureIpAddress(server!!) || Utils.isValidUrl(server!!)))
}
