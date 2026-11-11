package com.v2ray.ang.ui.subscription

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.base.EditorViewModel
import com.v2ray.ang.ui.server.ProxyChainProblem
import com.v2ray.ang.ui.server.proxyChainProblem
import kotlinx.coroutines.flow.StateFlow

/**
 * PattNG: what the subscription editor opens on: the [subscription] as stored, a new one otherwise, the [profileNames]
 * it offers for the previous and the next profile, and whether a delete is confirmed first, [confirmRemove].
 */
data class SubEditOpening(val subscription: SubscriptionItem, val profileNames: List<String>, val confirmRemove: Boolean)

/**
 * PattNG: the save and the delete of the subscription editor, see [EditorViewModel]. The subscription is stored as
 * [subId]: the one the screen was opened on, or none for a new one until its first save stores it, which a later save
 * writes over.
 */
class SubEditViewModel(
    application: Application,
    private val source: SubEditSource,
    private var subId: String,
) : EditorViewModel(application) {

    /** What the screen opened on, read off the main thread, see [openedWith]: the subscription the screen was opened on. */
    val opened: StateFlow<SubEditOpening?> = subId.let { opening ->
        openedWith {
            SubEditOpening(
                subscription = source.loadSubscription(opening) ?: SubscriptionItem(),
                profileNames = source.profileNames(
                    setOf(EConfigType.CUSTOM, EConfigType.CUSTOM_OUTBOUND, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN)
                ),
                confirmRemove = source.confirmsRemove(),
            )
        }
    }

    /**
     * Saves the subscription with [edits], the edits of the screen read at the tap, made on the subscription as stored
     * when it is written. The previous and the next profile are found by their names, as the chain finds them when it
     * runs: a name no profile has, as after a rename or a delete, several have, one whose profile has no server address,
     * or only a group, a chain, a custom configuration or a custom outbound has, is told rather than saved, see
     * [proxyChainProblem]. A save the storage refused is told, see [WRITE_REFUSED].
     */
    fun save(edits: (SubscriptionItem) -> Unit) = launchSave {
        val edited = SubscriptionItem().also(edits)
        // The next profile first, then the previous one, as the chain finds them.
        val neighbors = listOfNotNull(edited.nextProfile, edited.prevProfile).map { it.trim() }.filter { it.isNotEmpty() }
        when (val problem = proxyChainProblem(neighbors) { name, takes -> source.withProfileNames(takes) { find -> find(name) } }) {
            is ProxyChainProblem.Unresolved -> return@launchSave EditorOutcome.Refused(problem.message, listOf(problem.name))
            // The previous and the next profile chain every profile of the subscription. Either can be Aether, but not
            // both: one core runs, so a chain can have one Aether profile.
            ProxyChainProblem.SecondAether -> return@launchSave EditorOutcome.Refused(R.string.aether_chain_one_profile)
            null -> Unit
        }

        subId = source.saveSubscription(subId, edits) ?: return@launchSave WRITE_REFUSED
        EditorOutcome.Saved(subId)
    }

    /**
     * Deletes the subscription, see [EditorViewModel.launchDelete]; a new one, never stored, has none to delete. A delete
     * the storage refused is told, see [WRITE_REFUSED].
     */
    fun delete() {
        val key = subId.takeIf { it.isNotEmpty() } ?: return
        launchDelete { WRITE_REFUSED.takeUnless { source.deleteSubscription(key) } }
    }

    private companion object {
        /** A write or a delete the storage refused, as when the device is full: told, and the screen stays open. */
        val WRITE_REFUSED = EditorOutcome.Refused(R.string.toast_failure)
    }
}
