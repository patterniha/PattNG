package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import kotlinx.coroutines.flow.StateFlow

/**
 * PattNG: what the proxy chain editor opens on: the chain's [remarks] and [members], two blank ones for a new chain, and
 * the [profileNames] it offers for them.
 */
data class ProxyChainOpening(val remarks: String, val members: List<String>, val profileNames: List<String>)

/** PattNG: the save and the delete of the proxy chain editor, see [ProfileEditorViewModel]. */
class ServerProxyChainViewModel(
    application: Application,
    source: ProfileEditorSource,
    guid: String,
    subscriptionId: String?,
    serviceRunning: Boolean = false,
) : ProfileEditorViewModel(application, source, guid, subscriptionId, serviceRunning) {

    /** What the screen opened on, read off the main thread, see [openedWith]. */
    val opened: StateFlow<ProxyChainOpening?> = openedWith {
        val config = guid.takeIf { it.isNotEmpty() }?.let { source.loadProfile(it) }
        ProxyChainOpening(
            remarks = config?.remarks ?: "",
            members = config?.proxyChainProfiles?.let { text -> ProfileItem.proxyChainMembersOf(text).map { it.trim() }.filter { it.isNotEmpty() } }
                ?: listOf("", ""),
            profileNames = source.profileNames(
                setOf(EConfigType.CUSTOM, EConfigType.CUSTOM_OUTBOUND, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN)
            ),
        )
    }

    /**
     * Saves the chain as [remarks] with [members], the names of its profiles in its order. Every member has to be
     * chosen, and there have to be two. They are found by their names as the chain finds its hops when it runs: a name
     * no profile has, as after a rename or a delete, several have, one whose profile has no server address, or only a
     * group, a chain, a custom configuration or a custom outbound has, is told rather than saved, and so is a second
     * Aether member, see [proxyChainProblem].
     */
    fun save(remarks: String, members: List<String>) = launchSave {
        if (remarks.isBlank()) return@launchSave null
        val chainMembers = members.map { it.trim() }.filter { it.isNotEmpty() }
        if (chainMembers.size != members.size) return@launchSave EditorOutcome.Refused(R.string.server_proxy_chain_members_unselected)
        if (chainMembers.size < 2) return@launchSave EditorOutcome.Refused(R.string.server_proxy_chain_members_insufficient)

        when (val problem = proxyChainProblem(chainMembers) { name, takes -> source.withProfileNames(takes) { find -> find(name) } }) {
            is ProxyChainProblem.Unresolved -> return@launchSave EditorOutcome.Refused(problem.message, listOf(problem.name))
            // An Aether member can stand anywhere in the chain, but one core runs, so there can be one.
            ProxyChainProblem.SecondAether -> return@launchSave EditorOutcome.Refused(R.string.aether_chain_one_profile)
            null -> Unit
        }

        store(EConfigType.PROXYCHAIN) { config ->
            config.remarks = remarks.trim()
            config.proxyChainProfiles = ProfileItem.proxyChainProfilesOf(chainMembers)
            config.description = chainMembers.joinToString(" -> ")
        }
    }
}
