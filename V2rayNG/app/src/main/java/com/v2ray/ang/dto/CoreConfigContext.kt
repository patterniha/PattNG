package com.v2ray.ang.dto

import android.content.Context
import androidx.annotation.StringRes
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileEntry
import com.v2ray.ang.enums.CoreResolvedType

data class CoreConfigContext(
    val context: Context,
    val guid: String,
    val isCustom: Boolean = false,
    val resolvedOutbounds: List<ResolvedOutbound> = emptyList(),
    val routingDomainRules: List<RoutingDomainRule> = emptyList(),
    /**
     * PattNG: a profile a routing rule sends to, or a policy group falls back to, by a name no profile has, or several
     * have, or, as a fallback, only a group or a custom configuration has; the session is refused for it.
     */
    val unresolvedTarget: UnresolvedName? = null,
) {
    data class ResolvedOutbound(
        val tag: String,
        val entry: ProfileEntry,
        val resolvedEntries: List<ProfileEntry>,
        val resolvedType: CoreResolvedType,
        /**
         * PattNG: a hop the proxy chain, or the subscription around the profile, names that no profile has, several
         * have, or only a group, a chain or a custom configuration has; the configuration is refused for it.
         */
        val unresolvedHop: UnresolvedName? = null,
    )

    /** PattNG: a [name] by which a profile is named that cannot be used, for [reason], see [ByName]. */
    data class UnresolvedName(val name: String, val reason: Reason) {
        /** Why the name cannot be used, with the [message] that tells it, whose argument is the name. */
        enum class Reason(@StringRes val message: Int) {
            /** No profile has the name any more, as after a rename or a delete. */
            NOT_FOUND(R.string.toast_profile_name_not_found),

            /** Several have it, and the name cannot tell the one meant. */
            SEVERAL(R.string.toast_profile_name_duplicate),

            /** The one that has it has no server address, so a proxy chain cannot go through it. */
            NO_SERVER(R.string.toast_profile_no_server),

            /** As the fallback of a policy group, only a policy group has it, and a group cannot fall back to a group. */
            GROUP_AS_FALLBACK(R.string.toast_profile_group_not_fallback),

            /** As the fallback of a policy group, only a custom configuration has it, which a group cannot fall back to. */
            CUSTOM_AS_FALLBACK(R.string.toast_profile_custom_not_fallback),

            /** As a hop of a proxy chain, only a policy group has it, and a chain cannot go through a group. */
            GROUP_AS_HOP(R.string.toast_profile_group_not_hop),

            /** As a hop of a proxy chain, only a proxy chain has it, and a chain cannot go through another. */
            CHAIN_AS_HOP(R.string.toast_profile_chain_not_hop),

            /**
             * As a hop of a proxy chain, only a custom configuration has it, or a custom outbound, which a chain cannot go
             * through.
             */
            CUSTOM_AS_HOP(R.string.toast_profile_custom_not_hop),
        }
    }

    data class RoutingDomainRule(
        val domain: List<String>,
        val outboundTag: String,
    )
}
