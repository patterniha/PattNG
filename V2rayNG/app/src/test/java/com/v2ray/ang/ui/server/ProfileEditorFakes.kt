package com.v2ray.ang.ui.server

import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CompletableDeferred

/** The stored profiles an editor's test looks names up among, as [withStoredProfileNames] looks them up among the profiles of the device. */
internal class FakeProfileNames {
    val profiles = mutableListOf<ProfileItem>()

    /** The lookups made so far. */
    var lookups = 0

    /** When set, a lookup waits for it, as one off the main thread takes its time. */
    var gate: CompletableDeferred<Unit>? = null

    suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T {
        lookups++
        gate?.await()
        return check { name -> ByName.find(name, profiles.asSequence().filter(takes)) { it.remarks } }
    }

    /** Adds a profile of [type] named [name], with a server unless [server] is null. */
    fun add(name: String, type: EConfigType = EConfigType.VLESS, server: String? = "203.0.113.7") {
        profiles += ProfileItem.create(type).apply {
            remarks = name
            this.server = server
            serverPort = "443"
        }
    }
}

/** The profiles the profile editors store, in memory, with their names looked up in [names]. */
internal class FakeProfileEditorSource(val names: FakeProfileNames = FakeProfileNames()) : ProfileEditorSource {
    val stored = linkedMapOf<String, ProfileItem>()

    /** The configurations in full stored with their custom profiles, by guid. */
    val raws = mutableMapOf<String, String>()

    /** The guid each save was asked to store as, a new profile's got from [newGuid] before its first. */
    val saves = mutableListOf<String>()

    /** When set, a save waits for it before it writes, as one off the main thread takes its time. */
    var saveGate: CompletableDeferred<Unit>? = null

    /** When set, the storage refuses every write, as on a full device: nothing is written. */
    var refuseWrites = false

    /** What a custom configuration reads as. */
    var parsed: Result<ProfileItem> = Result.success(ProfileItem.create(EConfigType.CUSTOM))

    /** The guids of the profiles whose custom configuration was read, which a failure is logged for. */
    val parsedFor = mutableListOf<String>()

    /** The profile the app runs on, which is not deleted. */
    var selected: String? = null
    val deletes = mutableListOf<String>()

    /** When set, the storage refuses every delete: nothing is deleted. */
    var refuseDeletes = false

    /** The guids given to new profiles so far. */
    private var newGuids = 0

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        names.withProfileNames(takes, check)

    /** When set, the reads a screen opens on wait for it, as reads off the main thread take their time. */
    var openGate: CompletableDeferred<Unit>? = null

    /** The subscriptions, their keys with their names, as the policy group editor offers them. */
    var subscriptions: List<Pair<String, String>> = emptyList()

    // Fresh copies, as real reads give.
    override suspend fun loadProfile(guid: String): ProfileItem? {
        openGate?.await()
        return stored[guid]?.copy()
    }

    override suspend fun loadRaw(guid: String): String? {
        openGate?.await()
        return raws[guid]
    }

    // As SettingsManager.getProfileRemarks gives them.
    override suspend fun profileNames(excluded: Set<EConfigType>): List<String> {
        openGate?.await()
        return names.profiles.filter { it.configType !in excluded }.map { it.remarks.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    override suspend fun subscriptions(): List<Pair<String, String>> {
        openGate?.await()
        return subscriptions.toList()
    }

    override suspend fun saveProfile(guid: String, type: EConfigType, raw: String?, edit: (ProfileItem) -> Unit): String? {
        saves += guid
        saveGate?.await()
        if (refuseWrites) return null
        val key = guid.ifBlank { "guid-${stored.size + 1}" }
        val config = stored[key] ?: ProfileItem.create(type)
        edit(config)
        stored[key] = config
        if (raw != null) raws[key] = raw
        return key
    }

    override suspend fun storeProfile(guid: String, profile: ProfileItem): String? {
        saves += guid
        saveGate?.await()
        if (refuseWrites) return null
        val key = guid.ifBlank { "guid-${stored.size + 1}" }
        stored[key] = profile
        return key
    }

    override suspend fun parseCustomConfig(guid: String, content: String): Result<ProfileItem> {
        parsedFor += guid
        return parsed
    }

    /** What a custom outbound reads as: its profile, and the outbound as it is stored. */
    var parsedOutbound: Result<Pair<ProfileItem, String>> = Result.success(ProfileItem.create(EConfigType.CUSTOM_OUTBOUND) to "{}")

    override suspend fun parseCustomOutbound(guid: String, content: String): Result<Pair<ProfileItem, String>> {
        parsedFor += guid
        return parsedOutbound
    }

    override fun newGuid(): String = "guid-${++newGuids}"

    /** When set, a look at the profile selected waits for it, as one off the main thread takes its time. */
    var selectedGate: CompletableDeferred<Unit>? = null

    override suspend fun isSelected(guid: String): Boolean {
        selectedGate?.await()
        return guid == selected
    }

    override suspend fun deleteProfile(guid: String): Boolean {
        if (refuseDeletes) return false
        deletes += guid
        stored.remove(guid)
        return true
    }
}
