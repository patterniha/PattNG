package com.v2ray.ang.ui.server

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.CustomOutboundFmt
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ProfileStorageException
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PattNG: how an editor finds the profiles it names: by their names, as they are found when they are used, see [ByName]. */
interface ProfileNameSource {
    /**
     * What [check] gives, run off the main thread with a `find` that tells what a name finds among the profiles [takes]
     * lets through, see [SettingsManager.findServerViaRemarks].
     */
    suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T
}

/** PattNG: [ProfileNameSource.withProfileNames] over the profiles stored on this device. */
internal suspend fun <T> withStoredProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
    withContext(Dispatchers.IO) { check { SettingsManager.findServerViaRemarks(it, takes) } }

/**
 * PattNG: the names of the profiles stored on this device but those of [excluded] types, as
 * [SettingsManager.getProfileRemarks] gives them, read off the main thread, for an editor to offer.
 */
internal suspend fun storedProfileNames(excluded: Set<EConfigType>): List<String> =
    withContext(Dispatchers.IO) { SettingsManager.getProfileRemarks(excluded) }

/**
 * PattNG: where the profile editors read what they open on, find the profiles they name, read a custom configuration,
 * and store, or delete, their own.
 */
interface ProfileEditorSource : ProfileNameSource {
    /** The profile [guid] names, read off the main thread; null when [guid] is blank or names none. */
    suspend fun loadProfile(guid: String): ProfileItem?

    /** The configuration in full stored with the custom profile [guid] names, read off the main thread. */
    suspend fun loadRaw(guid: String): String?

    /** The names of the stored profiles but those of [excluded] types, read off the main thread, see [storedProfileNames]. */
    suspend fun profileNames(excluded: Set<EConfigType>): List<String>

    /** The subscriptions, their keys with their names, the key standing for a blank name, read off the main thread. */
    suspend fun subscriptions(): List<Pair<String, String>>

    /**
     * Stores the profile [guid] names with [edit] made on it as stored then, or, when [guid] is blank or names none any
     * more, a new profile of [type] with [edit] made on it, and with it [raw], when given, as the configuration in full
     * that a custom profile is; gives the guid it is stored as, or null, logged, when the storage refused the write.
     */
    suspend fun saveProfile(guid: String, type: EConfigType, raw: String? = null, edit: (ProfileItem) -> Unit): String?

    /**
     * Stores [profile], which its editor built whole, as [guid] names it, or as a new profile when [guid] is blank; gives
     * the guid it is stored as, or null, logged, when the storage refused the write.
     */
    suspend fun storeProfile(guid: String, profile: ProfileItem): String?

    /**
     * The profile the custom configuration [content] describes, read off the main thread, or the failure reading it
     * ended with, logged for the profile [guid].
     */
    suspend fun parseCustomConfig(guid: String, content: String): Result<ProfileItem>

    /**
     * The profile the custom outbound [content] describes, with the outbound as it is stored, tagged as the proxy, see
     * [CustomOutboundFmt], read off the main thread, or the failure reading it ended with, logged for the profile [guid].
     */
    suspend fun parseCustomOutbound(guid: String, content: String): Result<Pair<ProfileItem, String>>

    /** A guid of its own for a new profile, which no profile has. */
    fun newGuid(): String

    /** Whether [guid] names the profile selected, the one the app runs on. */
    suspend fun isSelected(guid: String): Boolean

    /** Deletes the profile [guid] names; false, logged, when the storage refused it, which leaves the profile as it was. */
    suspend fun deleteProfile(guid: String): Boolean
}

/**
 * PattNG: [ProfileEditorSource] over [MmkvManager], which owns the profiles: it moves their reads and writes, and the
 * reading of a custom configuration, off the main thread, logs what fails there, and lets the editors' view models be
 * tested without it.
 */
class ProfileEditorRepository : ProfileEditorSource {

    override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
        withStoredProfileNames(takes, check)

    override suspend fun loadProfile(guid: String): ProfileItem? =
        withContext(Dispatchers.IO) { MmkvManager.decodeServerConfig(guid) }

    override suspend fun loadRaw(guid: String): String? =
        withContext(Dispatchers.IO) { MmkvManager.decodeServerRaw(guid) }

    override suspend fun profileNames(excluded: Set<EConfigType>): List<String> = storedProfileNames(excluded)

    override suspend fun subscriptions(): List<Pair<String, String>> =
        withContext(Dispatchers.IO) { MmkvManager.decodeSubscriptions().map { sub -> sub.guid to sub.subscription.remarks.ifBlank { sub.guid } } }

    // The profile and its configuration are written in one go, which a cancelled save does not cut in two.
    override suspend fun saveProfile(guid: String, type: EConfigType, raw: String?, edit: (ProfileItem) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val config = MmkvManager.decodeServerConfig(guid) ?: ProfileItem.create(type)
            edit(config)
            storedAs(guid) {
                if (raw == null) MmkvManager.encodeServerConfig(guid, config) else MmkvManager.encodeServerConfigWithRaw(guid, config, raw)
            }
        }

    override suspend fun storeProfile(guid: String, profile: ProfileItem): String? =
        withContext(Dispatchers.IO) { storedAs(guid) { MmkvManager.encodeServerConfig(guid, profile) } }

    override suspend fun parseCustomConfig(guid: String, content: String): Result<ProfileItem> =
        withContext(Dispatchers.Default) {
            try {
                Result.success(CustomFmt.parse(content))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Custom configuration editor: failed to parse the configuration of profile $guid", e)
                Result.failure(e)
            }
        }

    override suspend fun parseCustomOutbound(guid: String, content: String): Result<Pair<ProfileItem, String>> =
        withContext(Dispatchers.Default) {
            try {
                Result.success(CustomOutboundFmt.parse(content))
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Custom configuration editor: failed to parse the outbound of profile $guid", e)
                Result.failure(e)
            }
        }

    /** The guid [write] stores the profile [guid] names as, or null, logged, when the storage refuses the write. */
    private inline fun storedAs(guid: String, write: () -> String): String? =
        try {
            write()
        } catch (e: ProfileStorageException) {
            LogUtil.e(AppConfig.TAG, "Profile editor: failed to store profile ${guid.ifBlank { "(new)" }}", e)
            null
        }

    override fun newGuid(): String = Utils.getUuid()

    override suspend fun isSelected(guid: String): Boolean =
        withContext(Dispatchers.IO) { MmkvManager.getSelectServer() == guid }

    // A refusal is logged by tryRemoveServer, which every delete goes through.
    override suspend fun deleteProfile(guid: String): Boolean =
        withContext(Dispatchers.IO) { MmkvManager.tryRemoveServer(guid) }
}
