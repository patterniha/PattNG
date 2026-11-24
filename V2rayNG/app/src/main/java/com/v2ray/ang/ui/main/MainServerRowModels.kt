package com.v2ray.ang.ui.main

import com.v2ray.ang.core.AetherCore
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager

internal data class ServerRowUiModel(
    val guid: String,
    val profile: ProfileItem,
    val remarks: String,
    val statistics: String,
    val typeDescription: String,
    val testDelayMillis: Long,
    val subscriptionBadge: String,
)

internal data class ServerGroupUiState(
    val servers: List<ServersCache> = emptyList(),
    val rows: List<ServerRowUiModel> = emptyList(),
    val isFiltered: Boolean = false,
) {
    fun canReorder(groupId: String, searchQuery: String): Boolean =
        groupId.isNotEmpty() && searchQuery.isEmpty() && !isFiltered

    fun moveServer(fromGuid: String, toGuid: String): ServerGroupUiState? {
        if (isFiltered) return null
        val from = servers.indexOfFirst { it.guid == fromGuid }
        val to = servers.indexOfFirst { it.guid == toGuid }
        if (from == to || from < 0 || to < 0) return null
        return ServerGroupUiState(
            servers = servers.toMutableList().apply { moveItem(from, to) },
            rows = rows.toMutableList().apply { moveItem(from, to) }
        )
    }
}

internal fun buildServerRowUiModel(
    server: ServersCache,
    subscriptionRemarks: String,
    aetherProtocolLabel: (AetherProtocol) -> String = AetherProtocol::name,
): ServerRowUiModel {
    val profile = server.profile
    return ServerRowUiModel(
        guid = server.guid,
        profile = profile,
        remarks = profile.remarks,
        statistics = profile.description.nullIfBlank()
            ?: AngConfigManager.generateDescription(profile),
        typeDescription = serverProtocolDescription(profile, aetherProtocolLabel),
        testDelayMillis = server.testDelayMillis,
        subscriptionBadge = subscriptionRemarks.firstOrNull()?.toString().orEmpty(),
    )
}

/**
 * The type of [profile] as its row names it; an Aether profile's WARP protocol is named by [aetherProtocolLabel], as
 * the editor names it, see [aetherProtocolLabels].
 */
internal fun serverProtocolDescription(
    profile: ProfileItem,
    aetherProtocolLabel: (AetherProtocol) -> String = AetherProtocol::name,
): String {
    if (profile.configType.isComplexType()) return profile.configType.name
    val parts = mutableListOf(profile.configType.name)
    if (profile.configType == EConfigType.AETHER) {
        // The tunnel from the outside in, so a profile that runs Psiphon or Tor alone is not named after a WARP protocol it never opens.
        parts.add(AetherCore.of(profile).path(aetherProtocolLabel).joinToString(" → "))
    }
    profile.network?.let { network ->
        if (network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) {
            parts.add(network)
        }
    }
    profile.security?.let { security ->
        if (security.isNotBlank()) {
            parts.add(
                if (profile.insecure == true && security.equals("tls", ignoreCase = true)) {
                    "$security insecure"
                } else {
                    security
                }
            )
        }
    }
    return parts.joinToString(" / ")
}

/**
 * The names the editor's protocol list gives the WARP protocols, [labels] beside the [values] a profile stores, as a
 * lookup for [serverProtocolDescription]; a protocol the list lacks keeps its own name.
 */
internal fun aetherProtocolLabels(labels: List<String>, values: List<String>): (AetherProtocol) -> String =
    { protocol -> labels.getOrNull(values.indexOf(protocol.type)) ?: protocol.name }
