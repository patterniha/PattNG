package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.base.EditorOutcome
import kotlinx.coroutines.flow.StateFlow

/**
 * PattNG: what the custom configuration editor opens on: the profile's [remarks], its configuration in full, [content],
 * and its [type]: a custom configuration, as a new profile is, or a custom outbound, one outbound alone.
 */
data class CustomConfigOpening(val remarks: String, val content: String, val type: EConfigType = EConfigType.CUSTOM)

/**
 * PattNG: the save and the delete of the custom configuration editor, see [ProfileEditorViewModel]. The screen edits a
 * custom profile it was opened on, a custom configuration or a custom outbound, and leaves it in the subscription it is
 * in.
 */
class ServerCustomConfigViewModel(
    application: Application,
    source: ProfileEditorSource,
    guid: String,
    serviceRunning: Boolean = false,
) : ProfileEditorViewModel(application, source, guid, subscriptionId = null, serviceRunning = serviceRunning) {

    /** What the screen opened on, read off the main thread, see [openedWith]. */
    val opened: StateFlow<CustomConfigOpening?> = openedWith {
        val profile = source.loadProfile(guid)
        CustomConfigOpening(profile?.remarks.orEmpty(), source.loadRaw(guid).orEmpty(), profile?.configType ?: EConfigType.CUSTOM)
    }

    /**
     * Saves the configuration [content] with its profile, named [remarks], which gets the server and the port the
     * configuration describes, as stored then; read off the main thread. A custom outbound is stored as read, tagged as
     * the proxy, see [ProfileEditorSource.parseCustomOutbound]. One that cannot be read is told, see [malformedConfig],
     * rather than saved.
     */
    fun save(remarks: String, content: String) = launchSave {
        if (remarks.isBlank()) return@launchSave null
        // Nothing to save by before the profile is read; the screen shows no form until then.
        val type = opened.value?.type ?: return@launchSave null
        val (parsed, raw) = if (type == EConfigType.CUSTOM_OUTBOUND) {
            source.parseCustomOutbound(guid, content)
        } else {
            source.parseCustomConfig(guid, content).map { it to content }
        }.getOrElse { return@launchSave malformedConfig(it) }
        store(type, raw = raw) { config ->
            config.remarks = remarks
            config.server = parsed.server
            config.serverPort = parsed.serverPort
            config.description = AngConfigManager.generateDescription(config)
        }
    }
}

/** PattNG: the refusal of a custom configuration whose reading ended with [failure]: with what it says, when it says something. */
internal fun malformedConfig(failure: Throwable): EditorOutcome.Refused {
    val detail = failure.cause?.message?.takeIf { it.isNotBlank() }
        ?: failure.message?.takeIf { it.isNotBlank() }
    return if (detail == null) {
        EditorOutcome.Refused(R.string.toast_malformed_json)
    } else {
        EditorOutcome.Refused(R.string.toast_malformed_json_detail, listOf(detail))
    }
}
