package com.v2ray.ang.ui.routing

import android.app.Application
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreConfigContextBuilder
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.base.EditorViewModel
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * PattNG: what the routing rule editor opens on: the [rule], null for a new one or one gone, the [outboundSuggestions]
 * it offers beside the built-in outbounds, the names of the profiles but one named as a built-in outbound, and whether
 * a rule can match the app a connection comes from, [canUseProcess].
 */
data class RuleOpening(val rule: RulesetItem?, val outboundSuggestions: List<String>, val canUseProcess: Boolean)

/**
 * PattNG: the save and the delete of the routing rule editor, see [EditorViewModel]. The screen was opened at
 * [position], a negative one for a new rule, on the rule of [reopenedId] when it kept one, see [openedRule]. The rule is
 * written where it is found again by its id, see [RoutingEditSource.saveRule]: the list may have changed while the
 * editor was open.
 */
class RoutingEditViewModel(
    application: Application,
    private val source: RoutingEditSource,
    private val position: Int,
    reopenedId: String?,
) : EditorViewModel(application) {

    /**
     * What the screen opened on, read off the main thread, see [openedWith], once when the editor opened, and kept while
     * the activity is recreated: a recreated screen reads no position again, where another rule may stand by then.
     */
    val opened: StateFlow<RuleOpening?> = openedWith {
        RuleOpening(
            rule = source.loadRule(position, reopenedId),
            // A profile named as a built-in outbound is no target: the rule sends to that outbound, see save.
            outboundSuggestions = source.profileNames().filter { it !in BUILTIN_OUTBOUND_TAGS },
            canUseProcess = source.canUseProcessRouting(),
        )
    }

    /** The id given to the rule should it be new, or gone. */
    private val newRuleId = UUID.randomUUID().toString()

    /**
     * The id the rule is saved and deleted by: the one it is stored with, or one given once to a new rule, or to one
     * gone, which every save keeps; null while the rule is read. Blank for a stored rule from before rules had ids, which
     * goes by its position.
     */
    val ruleId: String?
        get() = opened.value?.let { it.rule?.id ?: newRuleId }

    /**
     * Saves [rule]. A rule that sends to a profile names it, and the name has to find that one profile, as at the start:
     * a name no profile has, as after a rename or a delete, or several have, is told rather than saved. The start looks
     * at enabled rules alone, and so does this.
     */
    fun save(rule: RulesetItem) = launchSave {
        // Nothing to save by before the rule is read; the screen shows no form until then.
        val id = ruleId ?: return@launchSave null
        if (rule.remarks.isNullOrEmpty()) return@launchSave null
        val tag = rule.outboundTag
        if (rule.enabled && tag !in BUILTIN_OUTBOUND_TAGS) {
            when (source.withProfileNames(CoreConfigContextBuilder::takesAsRoutingTarget) { find -> find(tag) }) {
                ByName.None -> return@launchSave EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf(tag.trim()))
                ByName.Several -> return@launchSave EditorOutcome.Refused(R.string.toast_profile_name_duplicate, listOf(tag.trim()))
                is ByName.One -> Unit
            }
        }

        if (id.isNotEmpty()) {
            rule.id = id
        }
        if (!source.saveRule(position, rule)) return@launchSave WRITE_REFUSED
        EditorOutcome.Saved(rule.id)
    }

    /**
     * Deletes the rule, found again by its id, see [EditorViewModel.launchDelete]; a new rule, never stored, or one gone by
     * the time the editor opened, has none to delete. A delete the storage refused is told.
     */
    fun delete() {
        val rule = opened.value?.rule ?: return
        launchDelete { WRITE_REFUSED.takeUnless { source.deleteRule(position, rule.id) } }
    }

    private companion object {
        /** A write or a delete the storage refused, as when the device is full: told, and the screen stays open. */
        val WRITE_REFUSED = EditorOutcome.Refused(R.string.toast_failure)
    }
}
