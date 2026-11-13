package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import com.v2ray.ang.AppConfig.DEFAULT_SUBSCRIPTION_ID
import com.v2ray.ang.AppConfig.PREF_IS_BOOTED
import com.v2ray.ang.AppConfig.PREF_ROUTING_RULESET
import com.v2ray.ang.AppConfig.TAG
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.entities.AssetUrlCache
import com.v2ray.ang.dto.entities.AssetUrlItem
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.SubscriptionUsageInfo
import com.v2ray.ang.dto.entities.WebDavConfig
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

internal class ProfileStorageException(message: String) : IllegalStateException(message)

object MmkvManager {

    //region private

    private const val ID_MAIN = "MAIN"
    private const val ID_PROFILE_FULL_CONFIG = "PROFILE_FULL_CONFIG"
    private const val ID_SERVER_RAW = "SERVER_RAW"
    private const val ID_SERVER_AFF = "SERVER_AFF"
    private const val ID_SUB = "SUB"
    private const val ID_ASSET = "ASSET"
    private const val ID_SETTING = "SETTING"
    private const val KEY_SELECTED_SERVER = "SELECTED_SERVER"
    private const val KEY_ANG_CONFIGS = "ANG_CONFIGS"
    private const val KEY_SUB_SERVER_PREFIX = "SUB_SERVERS_"
    private const val KEY_SUB_IDS = "SUB_IDS"
    private const val KEY_WEBDAV_CONFIG = "WEBDAV_CONFIG"

    private val recoveryHandler = object : MMKVHandler {
        override fun onMMKVCRCCheckFail(mmapID: String) =
            recoverFromStorageError(mmapID, "CRC check")

        override fun onMMKVFileLengthError(mmapID: String) =
            recoverFromStorageError(mmapID, "file length check")

        override fun wantLogRedirecting(): Boolean = false

        override fun mmkvLog(
            level: MMKVLogLevel,
            file: String,
            line: Int,
            function: String,
            message: String
        ) = Unit
    }

    private val mainStorage by lazy { MMKV.mmkvWithID(ID_MAIN, MMKV.MULTI_PROCESS_MODE) }
    private val profileFullStorage by lazy { MMKV.mmkvWithID(ID_PROFILE_FULL_CONFIG, MMKV.MULTI_PROCESS_MODE) }
    private val serverRawStorage by lazy { MMKV.mmkvWithID(ID_SERVER_RAW, MMKV.MULTI_PROCESS_MODE) }
    private val serverAffStorage by lazy { MMKV.mmkvWithID(ID_SERVER_AFF, MMKV.MULTI_PROCESS_MODE) }
    private val subStorage by lazy { MMKV.mmkvWithID(ID_SUB, MMKV.MULTI_PROCESS_MODE) }
    private val assetStorage by lazy { MMKV.mmkvWithID(ID_ASSET, MMKV.MULTI_PROCESS_MODE) }
    private val settingsStorage by lazy { MMKV.mmkvWithID(ID_SETTING, MMKV.MULTI_PROCESS_MODE) }

    private inline fun <T> withProfileIndexLock(block: () -> T): T {
        return synchronized(mainStorage) {
            mainStorage.lock()
            try {
                block()
            } finally {
                mainStorage.unlock()
            }
        }
    }

    /**
     * PattNG: runs [block] holding the lock of the test results, across the app's processes, so that a result written
     * or cleared and a removal of the profiles whose test failed, see [tryRemoveFailedServer] and
     * [tryRemoveFailedServers], which look at their results under it, come one after the other. Taken after the profile
     * index lock when both are held, never before it.
     */
    private inline fun <T> withTestResultLock(block: () -> T): T {
        return synchronized(serverAffStorage) {
            serverAffStorage.lock()
            try {
                block()
            } finally {
                serverAffStorage.unlock()
            }
        }
    }

    private fun removeProfilePayloads(guids: Collection<String>) {
        if (guids.isEmpty()) return
        val keys = guids.toTypedArray()
        profileFullStorage.removeValuesForKeys(keys)
        serverAffStorage.removeValuesForKeys(keys)
        serverRawStorage.removeValuesForKeys(keys)
    }

    private fun requireStorageWrite(success: Boolean, message: String) {
        if (!success) throw ProfileStorageException(message)
    }

    private fun persistServerList(serverList: List<String>, subscriptionId: String): Boolean {
        return mainStorage.encode(serverListKey(subscriptionId), JsonUtil.toJson(serverList))
    }

    private fun serverListKey(subscriptionId: String): String {
        return "$KEY_SUB_SERVER_PREFIX${getSubscriptionId(subscriptionId)}"
    }

    /**
     * Returns every server referenced outside the target group, or null if the raw indexes
     * cannot provide a complete view.
     */
    private fun decodeServersReferencedByOtherGroups(subscriptionId: String): Set<String>? {
        val targetKey = serverListKey(subscriptionId)
        val keys = mainStorage.allKeys() ?: return null
        if (targetKey !in keys) return null

        val referencedServers = mutableSetOf<String>()
        for (key in keys) {
            if (!key.startsWith(KEY_SUB_SERVER_PREFIX) || key == targetKey) continue

            val json = mainStorage.decodeString(key)
            if (json.isNullOrBlank()) return null
            val serverIds = JsonUtil.fromJsonSafe(json, Array<String>::class.java) ?: return null
            referencedServers.addAll(serverIds)
        }
        return referencedServers
    }

    //endregion

    /**
     * Initializes MMKV with best-effort recovery so a damaged store is not silently discarded.
     */
    fun initialize(context: Context) {
        val logLevel = if (BuildConfig.DEBUG) {
            MMKVLogLevel.LevelDebug
        } else {
            MMKVLogLevel.LevelInfo
        }
        MMKV.initialize(
            context,
            context.filesDir.resolve("mmkv").absolutePath,
            null,
            logLevel,
            recoveryHandler
        )
    }

    private fun recoverFromStorageError(mmapID: String, error: String): MMKVRecoverStrategic {
        Log.e(TAG, "MMKV $error failed for $mmapID; attempting data recovery")
        return MMKVRecoverStrategic.OnErrorRecover
    }

    //region Server

    /**
     * Reads the legacy server list from KEY_ANG_CONFIGS for migration.
     * This method is for migration purposes only.
     *
     * @return The JSON string of legacy server list, or null if not exists.
     */
    fun readLegacyServerList(): String? {
        return mainStorage.decodeString(KEY_ANG_CONFIGS)
    }


    /**
     * Gets the selected server GUID.
     *
     * @return The selected server GUID.
     */
    fun getSelectServer(): String? {
        return mainStorage.decodeString(KEY_SELECTED_SERVER)
    }

    /**
     * Sets the selected server GUID.
     *
     * @param guid The server GUID.
     */
    fun setSelectServer(guid: String) {
        withProfileIndexLock {
            mainStorage.encode(KEY_SELECTED_SERVER, guid)
        }
    }

    /**
     * Encodes the server list for a given subscription.
     * Saves to the subscription's serverList (including default subscription for ungrouped servers).
     *
     * @param serverList The list of server GUIDs.
     * @param subscriptionId The subscription ID.
     */
    fun encodeServerList(serverList: MutableList<String>, subscriptionId: String) {
        withProfileIndexLock {
            persistServerList(serverList, subscriptionId)
        }
    }


    /**
     * Decodes the server list for a given subscription.
     * If subscriptionId is empty, returns ungrouped servers.
     * Otherwise, returns servers from the specified subscription's serverList.
     *
     * @param subscriptionId The subscription ID.
     * @return The list of server GUIDs.
     */
    fun decodeServerList(subscriptionId: String): MutableList<String> {
        val json = mainStorage.decodeString(serverListKey(subscriptionId))
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toMutableList() ?: mutableListOf()
        }
    }

    /**
     * Decodes all server list (merged from all subscriptions including default subscription).
     * Use this when you need the complete server list.
     *
     * @return The list of all server GUIDs.
     */
    fun decodeAllServerList(): MutableList<String> {
        val allServers = mutableListOf<String>()
        val subsList = decodeSubsList()

        // If DEFAULT_SUBSCRIPTION_ID is not in the subscriptions list, add its servers
        if (!subsList.contains(DEFAULT_SUBSCRIPTION_ID)) {
            allServers.addAll(decodeServerList(DEFAULT_SUBSCRIPTION_ID))
        }

        // Add servers from all subscriptions
        subsList.forEach { guid ->
            allServers.addAll(decodeServerList(guid))
        }

        return allServers
    }


    /**
     * Decodes the server configuration.
     *
     * @param guid The server GUID.
     * @return The server configuration.
     */
    fun decodeServerConfig(guid: String): ProfileItem? {
        if (guid.isBlank()) {
            return null
        }
        val json = profileFullStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ProfileItem::class.java)
    }


    /**
     * Encodes the server configuration.
     *
     * @param guid The server GUID.
     * @param config The server configuration.
     * @return The server GUID.
     */
    fun encodeServerConfig(guid: String, config: ProfileItem): String {
        val key = guid.ifBlank { Utils.getUuid() }
        withProfileIndexLock {
            // PattNG: the payload as it was, put back when a write after it is refused.
            val previousPayload = profileFullStorage.decodeString(key)
            requireStorageWrite(
                profileFullStorage.encode(key, JsonUtil.toJson(config)),
                "Failed to save profile payload",
            )

            // Use default subscription for servers without subscription
            val subId = getSubscriptionId(config.subscriptionId)
            val serverList = decodeServerList(subId)

            if (!serverList.contains(key)) {
                serverList.add(0, key)
                var listed = false
                try {
                    requireStorageWrite(
                        persistServerList(serverList, subId),
                        "Failed to publish profile index",
                    )
                    listed = true
                    if (getSelectServer().isNullOrBlank()) {
                        requireStorageWrite(
                            mainStorage.encode(KEY_SELECTED_SERVER, key),
                            "Failed to update selected profile",
                        )
                    }
                } catch (e: ProfileStorageException) {
                    // PattNG: a new profile is stored whole or not at all, so that the next try adds it, and selects it,
                    // anew: out of its list again, and then its payload as it was. Should the list refuse that too, the
                    // payload stays, so that no list names a profile without one.
                    serverList.remove(key)
                    if (!listed || persistServerList(serverList, subId)) {
                        if (previousPayload == null) profileFullStorage.removeValueForKey(key) else profileFullStorage.encode(key, previousPayload)
                    } else {
                        LogUtil.e(TAG, "MmkvManager: the storage refused profile $key out of its list again; it stays listed")
                    }
                    throw e
                }
            }
        }

        return key
    }

    /**
     * Saves a profile batch before publishing its group index and removing replaced payloads.
     *
     * @param profiles Generated GUIDs and parsed profiles, in insertion order.
     * @param rawConfigs Optional raw configuration payloads keyed by profile GUID.
     * @param subscriptionId The destination subscription ID.
     * @param append Whether to append to the existing group index.
     */
    internal fun saveServerProfiles(
        profiles: Map<String, ProfileItem>,
        rawConfigs: Map<String, String>,
        subscriptionId: String,
        append: Boolean,
    ) {
        if (profiles.isEmpty()) return

        withProfileIndexLock {
            val replacedServers = if (append) {
                emptyList()
            } else {
                decodeServerList(subscriptionId).toList()
            }
            val previousSelection = getSelectServer()
            val selectedProfile = if (!append &&
                previousSelection != null &&
                previousSelection in replacedServers
            ) {
                decodeServerConfig(previousSelection)
            } else {
                null
            }
            val replacementSelection = ProfileReplacement.findSelectedReplacement(
                profiles = profiles,
                currentSelection = previousSelection,
                selectedProfile = selectedProfile,
            )

            profiles.forEach { (guid, profile) ->
                requireStorageWrite(
                    profileFullStorage.encode(guid, JsonUtil.toJson(profile)),
                    "Failed to save profile payload",
                )
                rawConfigs[guid]?.let { raw ->
                    requireStorageWrite(
                        serverRawStorage.encode(guid, raw),
                        "Failed to save raw profile payload",
                    )
                }
            }

            val serverList = if (append) {
                decodeServerList(subscriptionId)
            } else {
                ProfileReplacement.findKeptGroups(
                    replacedProfiles = replacedServers.associateWith(::decodeServerConfig),
                    profiles = profiles.values,
                ).toMutableList()
            }
            val indexedServers = serverList.toHashSet()
            profiles.keys.forEach { guid ->
                if (indexedServers.add(guid)) {
                    serverList.add(0, guid)
                }
            }
            requireStorageWrite(
                persistServerList(serverList, subscriptionId),
                "Failed to publish profile index",
            )
            replacementSelection?.let { guid ->
                requireStorageWrite(
                    mainStorage.encode(KEY_SELECTED_SERVER, guid),
                    "Failed to update selected profile",
                )
            }
            if (replacedServers.isEmpty()) return@withProfileIndexLock

            val protectedServer = replacementSelection ?: previousSelection
            val referencedByOtherGroups = decodeServersReferencedByOtherGroups(subscriptionId)
            val removablePayloads = ProfileReplacement.findRemovablePayloads(
                replacedServers = replacedServers,
                replacementServers = serverList.toSet(),
                protectedServer = protectedServer,
                serversReferencedByOtherGroups = referencedByOtherGroups,
            )
            removeProfilePayloads(removablePayloads)
        }
    }

    /**
     * Removes the server configuration.
     *
     * @param guid The server GUID.
     */
    fun removeServer(guid: String) {
        if (guid.isBlank()) {
            return
        }

        tryRemoveServer(guid)
    }

    /**
     * PattNG: removes the profile [guid] names, as [removeServer] does, under the profile index lock, so that a list
     * written meanwhile, as by a subscription update, is not written back without its change: out of its list first,
     * then its payloads, its raw configuration among them, which [removeServer] used to leave behind. False, with
     * nothing removed, when the storage refused the list without it, which is logged.
     */
    fun tryRemoveServer(guid: String): Boolean {
        if (guid.isBlank()) return true
        return withProfileIndexLock {
            // Get config to determine which subscription to update
            val config = decodeServerConfig(guid)
            val subId = getSubscriptionId(config?.subscriptionId)

            // Remove from appropriate server list
            val serverList = decodeServerList(subId)
            if (serverList.remove(guid) && !persistServerList(serverList, subId)) {
                LogUtil.e(TAG, "MmkvManager: the storage refused the list without profile $guid")
                return@withProfileIndexLock false
            }

            // Clean up storage
            if (getSelectServer() == guid) {
                mainStorage.remove(KEY_SELECTED_SERVER)
            }
            removeProfilePayloads(listOf(guid))
            true
        }
    }

    /**
     * PattNG: removes the profiles of [subscriptionId] whose test failed, among those listed when the profile index lock
     * is held, so that a profile listed or removed meanwhile, as by an update or a delete of the subscription, is not
     * undone, as their results say under the lock of the test results as well, so that a test that passes one meanwhile,
     * or a clearing of its result, keeps it: out of the list first, checked, then the selection when it is one of them,
     * and their payloads, their raw configurations among them. Whether the storage took it: false, with nothing
     * removed, when it refused the list, which is logged.
     */
    fun tryRemoveFailedServers(subscriptionId: String): Boolean = withProfileIndexLock {
        withTestResultLock {
            val subId = getSubscriptionId(subscriptionId)
            val serverList = decodeServerList(subId)
            val removed = serverList.filter { guid ->
                val aff = decodeServerAffiliationInfo(guid)
                aff != null && aff.testDelayMillis < 0L
            }
            if (removed.isEmpty()) return@withTestResultLock true
            if (!persistServerList(serverList - removed.toSet(), subId)) {
                LogUtil.e(TAG, "MmkvManager: the storage refused the list of group $subId without ${removed.size} of its profiles")
                return@withTestResultLock false
            }
            val selected = getSelectServer()
            if (selected != null && selected in removed) {
                mainStorage.remove(KEY_SELECTED_SERVER)
            }
            removeProfilePayloads(removed)
            true
        }
    }

    /**
     * PattNG: moves the profile [fromGuid] names to where the one [toGuid] names stands in the list of [subscriptionId],
     * as it is stored then, under the profile index lock, so that a profile an update stored, or a removal took away,
     * meanwhile is not undone. With either not listed there is nothing to move, which is no refusal. Whether the
     * storage took it, a refusal logged.
     */
    fun tryMoveServer(subscriptionId: String, fromGuid: String, toGuid: String): Boolean = withProfileIndexLock {
        val subId = getSubscriptionId(subscriptionId)
        val serverList = decodeServerList(subId)
        if (!serverList.moveItem(serverList.indexOf(fromGuid), serverList.indexOf(toGuid))) return@withProfileIndexLock true
        if (!persistServerList(serverList, subId)) {
            LogUtil.e(TAG, "MmkvManager: the storage refused the list of group $subId with $fromGuid moved")
            return@withProfileIndexLock false
        }
        true
    }

    /**
     * PattNG: orders the profiles of [subscriptionId] by [rank], the least first, those of an equal rank as they stood,
     * the list as stored when the profile index lock is held, so that a profile listed or removed meanwhile, as by an
     * update or a delete of the subscription, is not undone. [rank] is read once for each profile, so that the sort
     * holds the lock no longer than it must, and a rank that changes meanwhile, as a test result written, cannot make
     * the order contradict itself. Written only when the order changes. Whether the storage took it, a refusal logged.
     */
    fun trySortServerList(subscriptionId: String, rank: (guid: String) -> Long): Boolean = withProfileIndexLock {
        val subId = getSubscriptionId(subscriptionId)
        val serverList = decodeServerList(subId)
        val ranks = serverList.associateWith(rank)
        val sorted = serverList.sortedBy { ranks.getValue(it) }
        if (sorted == serverList) return@withProfileIndexLock true
        if (!persistServerList(sorted, subId)) {
            LogUtil.e(TAG, "MmkvManager: the storage refused the list of group $subId sorted")
            return@withProfileIndexLock false
        }
        true
    }

    /**
     * Decodes the server affiliation information.
     *
     * @param guid The server GUID.
     * @return The server affiliation information.
     */
    fun decodeServerAffiliationInfo(guid: String): ServerAffiliationInfo? {
        if (guid.isBlank()) {
            return null
        }
        val json = serverAffStorage.decodeString(guid)
        if (json.isNullOrBlank()) {
            return null
        }
        return JsonUtil.fromJsonSafe(json, ServerAffiliationInfo::class.java)
    }

    /**
     * Encodes the server test delay in milliseconds.
     *
     * @param guid The server GUID.
     * @param testResult The test delay in milliseconds.
     */
    fun encodeServerTestDelayMillis(guid: String, testResult: Long) {
        if (guid.isBlank()) {
            return
        }
        // PattNG: under the lock of the test results, see withTestResultLock; a write the storage refused is logged.
        withTestResultLock {
            val aff = decodeServerAffiliationInfo(guid) ?: ServerAffiliationInfo()
            aff.testDelayMillis = testResult
            if (!serverAffStorage.encode(guid, JsonUtil.toJson(aff))) {
                LogUtil.e(TAG, "MmkvManager: the storage refused the test result of profile $guid")
            }
        }
    }

    /**
     * Clears all test delay results.
     *
     * @param keys The list of server GUIDs.
     */
    fun clearAllTestDelayResults(keys: List<String>?) {
        // PattNG: under the lock of the test results, see withTestResultLock; a write the storage refused is logged.
        withTestResultLock {
            keys?.forEach { key ->
                decodeServerAffiliationInfo(key)?.let { aff ->
                    aff.testDelayMillis = 0
                    if (!serverAffStorage.encode(key, JsonUtil.toJson(aff))) {
                        LogUtil.e(TAG, "MmkvManager: the storage refused the cleared test result of profile $key")
                    }
                }
            }
        }
    }

    /**
     * Removes all server configurations.
     *
     * @return The number of server configurations removed.
     */
    fun removeAllServer(): Int {
        val count = profileFullStorage.allKeys()?.count() ?: 0
        profileFullStorage.clearAll()
        serverAffStorage.clearAll()
        serverRawStorage.clearAll()

        decodeSubscriptions().forEach { sub ->
            encodeServerList(mutableListOf(), sub.guid)
        }
        return count
    }

    /**
     * Removes invalid server configurations.
     *
     * @param guid The server GUID.
     * @return The number of server configurations removed.
     */
    fun removeInvalidServer(guid: String): Int {
        var count = 0
        if (guid.isNotEmpty()) {
            if (tryRemoveFailedServer(guid)) {
                count++
            }
        } else {
            serverAffStorage.allKeys()?.forEach { key ->
                if (tryRemoveFailedServer(key)) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * PattNG: removes the profile [guid] names, see [tryRemoveServer], when its test failed, as its result says under
     * the profile index lock and the lock of the test results, so that a test that passes it meanwhile, or a clearing
     * of its result, keeps it. Whether it was removed: false when its test did not fail, or when the storage refused its
     * list, which is logged.
     */
    private fun tryRemoveFailedServer(guid: String): Boolean = withProfileIndexLock {
        withTestResultLock {
            val aff = decodeServerAffiliationInfo(guid)
            aff != null && aff.testDelayMillis < 0L && tryRemoveServer(guid)
        }
    }

    /**
     * Encodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @param config The raw server configuration.
     */
    fun encodeServerRaw(guid: String, config: String) {
        serverRawStorage.encode(guid, config)
    }

    /**
     * PattNG: saves [config] as [encodeServerConfig] does, with [raw], the configuration in full a custom profile is,
     * under the guid it gives: the raw configuration first, then the profile, which encodeServerConfig stores whole or
     * not at all. When the profile's write is refused, the raw configuration is put back as it was, or removed, and the
     * failure is thrown. Both go under the profile index lock, which encodeServerConfig takes again, MMKV counting the
     * holds of one process: a subscription update, which removes the payloads of the profiles it replaces, cannot come in
     * between.
     */
    fun encodeServerConfigWithRaw(guid: String, config: ProfileItem, raw: String): String {
        val key = guid.ifBlank { Utils.getUuid() }
        return withProfileIndexLock {
            val previous = serverRawStorage.decodeString(key)
            requireStorageWrite(serverRawStorage.encode(key, raw), "Failed to save raw profile payload")
            try {
                encodeServerConfig(key, config)
            } catch (e: ProfileStorageException) {
                if (previous == null) serverRawStorage.removeValueForKey(key) else serverRawStorage.encode(key, previous)
                throw e
            }
        }
    }

    /**
     * Decodes the raw server configuration.
     *
     * @param guid The server GUID.
     * @return The raw server configuration.
     */
    fun decodeServerRaw(guid: String): String? {
        return serverRawStorage.decodeString(guid)
    }

    /**
     * Removes profile payloads that are provably absent from their raw SUB_SERVERS_* index.
     *
     * SUB_IDS and SUB are intentionally ignored: either store can be missing after MMKV
     * recovery while the group indexes still identify live profiles. If any group index or
     * profile payload needed for a decision is unreadable, that data is preserved.
     *
     * @return The number of profile payloads removed, or null if cleanup could not run safely.
     */
    internal fun removeOrphanedServerProfiles(): Int? = synchronized(mainStorage) {
        mainStorage.lock()
        try {
            val indexedServersBySubscription = mainStorage.allKeys().orEmpty()
                .asSequence()
                .filter { key -> key.startsWith(KEY_SUB_SERVER_PREFIX) }
                .associate { key ->
                    val subscriptionId = key.removePrefix(KEY_SUB_SERVER_PREFIX)
                    val json = mainStorage.decodeString(key)
                    val serverIds = if (json.isNullOrBlank()) {
                        null
                    } else {
                        JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.toSet()
                    }
                    subscriptionId to serverIds
                }

            val profiles = profileFullStorage.allKeys().orEmpty().map { guid ->
                StoredProfileReference(
                    guid = guid,
                    subscriptionId = decodeServerConfig(guid)?.subscriptionId,
                )
            }
            val orphans = OrphanProfileCleaner.findOrphans(
                profiles = profiles,
                indexedServersBySubscription = indexedServersBySubscription,
                selectedServer = getSelectServer(),
            ) ?: return@synchronized null

            if (orphans.isNotEmpty()) {
                val keys = orphans.toTypedArray()
                profileFullStorage.removeValuesForKeys(keys)
                serverAffStorage.removeValuesForKeys(keys)
                serverRawStorage.removeValuesForKeys(keys)
            }
            orphans.size
        } finally {
            mainStorage.unlock()
        }
    }

    //endregion

    //region Subscriptions

    private fun getSubscriptionId(subscriptionId: String?): String {
        return subscriptionId?.ifEmpty { DEFAULT_SUBSCRIPTION_ID } ?: DEFAULT_SUBSCRIPTION_ID
    }

    /**
     * Initializes the subscription list.
     */
    private fun initSubsList() {
        if (decodeSubsList().isNotEmpty()) {
            return
        }
        // PattNG: looked at again under the profile index lock, which a removal of a subscription holds until its payload
        // is gone too, see tryRemoveSubscription, so that the last subscription removed is not listed again.
        withProfileIndexLock {
            val subsList = decodeSubsList()
            if (subsList.isNotEmpty()) {
                return@withProfileIndexLock
            }
            subStorage.allKeys()?.forEach { key ->
                subsList.add(key)
            }
            encodeSubsList(subsList)
        }
    }

    /**
     * Decodes the subscriptions.
     *
     * @return The list of subscriptions.
     */
    fun decodeSubscriptions(): List<SubscriptionCache> {
        initSubsList()

        val subscriptions = mutableListOf<SubscriptionCache>()
        decodeSubsList().forEach { key ->
            val json = subStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java) ?: SubscriptionItem()
                subscriptions.add(SubscriptionCache(key, item))
            }
        }
        return subscriptions
    }

    /**
     * PattNG: stores [subItem] as the subscription [guid] names, a new one under a new key when it is blank, under the
     * profile index lock: a subscription not listed yet is listed last, or first when [listFirst]; an empty list stands
     * for every subscription stored, as [initSubsList] reads it, and those stay listed. Tells whether the storage took
     * it: the key it is stored as, or null when the storage refused the subscription, or the list naming it, which is
     * logged. Refused, the subscription is as it was: a new one leaves nothing behind, and one stored before is put
     * back as it was stored, which, refused too, is logged.
     */
    fun tryEncodeSubscription(guid: String, subItem: SubscriptionItem, listFirst: Boolean = false): String? {
        val key = guid.ifBlank { Utils.getUuid() }
        return withProfileIndexLock {
            val previous = subStorage.decodeString(key)
            if (!subStorage.encode(key, JsonUtil.toJson(subItem))) {
                LogUtil.e(TAG, "MmkvManager: the storage refused subscription $key")
                return@withProfileIndexLock null
            }
            val subsList = decodeSubsList().ifEmpty { subStorage.allKeys()?.filter { it != key }?.toMutableList() ?: mutableListOf() }
            if (key !in subsList && !mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(if (listFirst) listOf(key) + subsList else subsList + key))) {
                LogUtil.e(TAG, "MmkvManager: the storage refused the subscription list with $key")
                if (previous == null) {
                    subStorage.removeValueForKey(key)
                } else if (!subStorage.encode(key, previous)) {
                    LogUtil.e(TAG, "MmkvManager: the storage refused subscription $key back as it was stored")
                }
                return@withProfileIndexLock null
            }
            key
        }
    }

    /**
     * PattNG: removes the subscription [subid] names with its profiles, under the profile index lock, and tells whether
     * the storage took it. First the lists, each write checked: the list of the subscriptions without it, then the list
     * of its profiles emptied; then the payloads, the raw configurations of its profiles among them, and its own. False,
     * with nothing removed, when the storage refused a list, which is logged: a refused list of profiles puts the list
     * of the subscriptions back as it was stored, and should the storage refuse that too, the subscription stays out of
     * it, its profiles and payloads kept, which is logged as well.
     */
    fun tryRemoveSubscription(subid: String): Boolean = withProfileIndexLock {
        val storedSubs = mainStorage.decodeString(KEY_SUB_IDS)
        val subsList = decodeSubsList()
        val unlisted = subsList.remove(subid)
        if (unlisted && !mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))) {
            LogUtil.e(TAG, "MmkvManager: the storage refused the subscription list without $subid")
            return@withProfileIndexLock false
        }
        val subId = getSubscriptionId(subid)
        val serverList = decodeServerList(subId)
        if (!persistServerList(emptyList(), subId)) {
            LogUtil.e(TAG, "MmkvManager: the storage refused the profiles of subscription $subid out of their list")
            if (unlisted && storedSubs != null && !mainStorage.encode(KEY_SUB_IDS, storedSubs)) {
                LogUtil.e(TAG, "MmkvManager: the storage refused subscription $subid back in the subscription list; it stays out of it, with its profiles")
            }
            return@withProfileIndexLock false
        }
        val selected = getSelectServer()
        if (selected != null && selected in serverList) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }
        removeProfilePayloads(serverList)
        subStorage.remove(subid)
        true
    }

    /**
     * PattNG: changes the subscription [subId] names as it is stored then, with [change], which tells whether it changed
     * anything. A payload that cannot be read is read as a new subscription, as [decodeSubscriptions] reads it for the
     * list to show, when [newIfUnreadable]; else it is left as it is. The list of the subscriptions is not written, so
     * that one removed meanwhile is not listed again. Null when it is gone; else whether the storage took the change,
     * none to make included, a refusal logged as of [what]. Called under the profile index lock.
     */
    private fun changeStoredSubscription(
        subId: String,
        what: String,
        newIfUnreadable: Boolean,
        change: (SubscriptionItem) -> Boolean,
    ): Boolean? {
        val json = subStorage.decodeString(subId)
        if (json.isNullOrBlank()) return null
        val item = JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java)
            ?: if (newIfUnreadable) SubscriptionItem() else return true
        if (!change(item)) return true
        if (!subStorage.encode(subId, JsonUtil.toJson(item))) {
            LogUtil.e(TAG, "MmkvManager: the storage refused subscription $subId $what")
            return false
        }
        return true
    }

    /**
     * PattNG: turns the subscription [subId] names on or off, as it is stored then, under the profile index lock, see
     * [changeStoredSubscription], and tells whether the storage took it. One gone, as removed meanwhile, or already so,
     * is left as it is, which is no refusal.
     */
    fun trySetSubscriptionEnabled(subId: String, enabled: Boolean): Boolean = withProfileIndexLock {
        changeStoredSubscription(subId, "turned ${if (enabled) "on" else "off"}", newIfUnreadable = true) { item ->
            (item.enabled != enabled).also { changed -> if (changed) item.enabled = enabled }
        } ?: true
    }

    /**
     * PattNG: sets [time] as when the subscription [subId] names was updated, on it as stored then, under the profile
     * index lock, see [changeStoredSubscription]: one removed meanwhile is not listed again, and a payload that cannot
     * be read is left as it is. Whether it is still stored; a refused write is logged.
     */
    fun trySetSubscriptionUpdated(subId: String, time: Long): Boolean = withProfileIndexLock {
        changeStoredSubscription(subId, "with its update time", newIfUnreadable = false) { item ->
            item.lastUpdated = time
            true
        } != null
    }

    /**
     * PattNG: ends an update of the subscription [subId] names, once its profiles are stored: [time] is set as when it
     * was updated, and [usageInfo] as the usage its panel sent, replacing the last one, null when it sent none, on it as
     * stored then, under the profile index lock, see [changeStoredSubscription], which keeps what an edit wrote
     * meanwhile; a payload that cannot be read is left as it is. Removed meanwhile, it is not listed again, and the
     * profiles the update stored for it go too: none stays selected, their list is emptied, then their payloads go.
     * Whether it is still stored; a refused write is logged.
     */
    fun finishSubscriptionUpdate(subId: String, time: Long, usageInfo: SubscriptionUsageInfo?): Boolean = withProfileIndexLock {
        val stored = changeStoredSubscription(subId, "with its update time", newIfUnreadable = false) { item ->
            item.lastUpdated = time
            item.usageInfo = usageInfo
            true
        }
        if (stored != null) return@withProfileIndexLock true
        val serverList = decodeServerList(subId)
        if (serverList.isEmpty()) return@withProfileIndexLock false
        // Before the list: no profile of a subscription gone stays selected, even when the storage keeps them listed.
        val selected = getSelectServer()
        if (selected != null && selected in serverList) {
            mainStorage.remove(KEY_SELECTED_SERVER)
        }
        if (!persistServerList(emptyList(), subId)) {
            LogUtil.e(TAG, "MmkvManager: the storage refused the profiles of removed subscription $subId out of their list")
            return@withProfileIndexLock false
        }
        removeProfilePayloads(serverList)
        false
    }

    /**
     * PattNG: moves the subscription [fromId] names to where the one [toId] names stands in the list of the subscriptions,
     * as it is stored then, under the profile index lock, so that a subscription listed before the move, if after the
     * list was shown, is not written out of it, and tells whether the storage took it, a refusal logged. With either not
     * listed there is nothing to move, which is no refusal.
     */
    fun tryMoveSubscription(fromId: String, toId: String): Boolean = withProfileIndexLock {
        val subsList = decodeSubsList()
        if (!subsList.moveItem(subsList.indexOf(fromId), subsList.indexOf(toId))) return@withProfileIndexLock true
        if (!mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))) {
            LogUtil.e(TAG, "MmkvManager: the storage refused the subscription list with $fromId moved")
            return@withProfileIndexLock false
        }
        true
    }

    /**
     * Decodes the subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return The subscription item.
     */
    fun decodeSubscription(subscriptionId: String): SubscriptionItem? {
        val json = subStorage.decodeString(subscriptionId) ?: return null
        return JsonUtil.fromJsonSafe(json, SubscriptionItem::class.java)
    }

    /**
     * Encodes the subscription list.
     *
     * @param subsList The list of subscription IDs.
     */
    fun encodeSubsList(subsList: MutableList<String>) {
        mainStorage.encode(KEY_SUB_IDS, JsonUtil.toJson(subsList))
    }

    /**
     * Decodes the subscription list.
     *
     * @return The list of subscription IDs.
     */
    fun decodeSubsList(): MutableList<String> {
        val json = mainStorage.decodeString(KEY_SUB_IDS)
        return if (json.isNullOrBlank()) {
            mutableListOf()
        } else {
            // Keep the first occurrence so a damaged index cannot produce duplicate Compose keys.
            JsonUtil.fromJsonSafe(json, Array<String>::class.java)?.distinct()?.toMutableList() ?: mutableListOf()
        }
    }

    //endregion

    //region Asset

    /**
     * Decodes the asset URLs.
     *
     * @return The list of asset URLs.
     */
    fun decodeAssetUrls(): List<AssetUrlCache> {
        val assetUrlItems = mutableListOf<AssetUrlCache>()
        assetStorage.allKeys()?.forEach { key ->
            val json = assetStorage.decodeString(key)
            if (!json.isNullOrBlank()) {
                val item = JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java) ?: AssetUrlItem()
                assetUrlItems.add(AssetUrlCache(key, item))
            }
        }
        return assetUrlItems.sortedBy { it.assetUrl.addedTime }
    }

    /**
     * Removes the asset URL.
     *
     * @param assetid The asset ID.
     */
    fun removeAssetUrl(assetid: String) {
        assetStorage.remove(assetid)
    }

    /**
     * Encodes the asset.
     *
     * @param assetid The asset ID.
     * @param assetItem The asset item.
     */
    fun encodeAsset(assetid: String, assetItem: AssetUrlItem) {
        val key = assetid.ifBlank { Utils.getUuid() }
        assetStorage.encode(key, JsonUtil.toJson(assetItem))
    }

    /**
     * Decodes the asset.
     *
     * @param assetid The asset ID.
     * @return The asset item.
     */
    fun decodeAsset(assetid: String): AssetUrlItem? {
        val json = assetStorage.decodeString(assetid) ?: return null
        return JsonUtil.fromJsonSafe(json, AssetUrlItem::class.java)
    }

    //endregion

    //region Routing

    /**
     * Decodes the routing rulesets.
     *
     * @return The list of routing rulesets.
     */
    fun decodeRoutingRulesets(): MutableList<RulesetItem>? {
        val ruleset = settingsStorage.decodeString(PREF_ROUTING_RULESET)
        if (ruleset.isNullOrEmpty()) return null
        return JsonUtil.fromJsonSafe(ruleset, Array<RulesetItem>::class.java)?.toMutableList() ?: mutableListOf()
    }

    /**
     * Encodes the routing rulesets.
     *
     * @param rulesetList The list of routing rulesets.
     */
    fun encodeRoutingRulesets(rulesetList: MutableList<RulesetItem>?): Boolean {
        return if (rulesetList.isNullOrEmpty())
            encodeSettings(PREF_ROUTING_RULESET, "")
        else
            encodeSettings(PREF_ROUTING_RULESET, JsonUtil.toJson(rulesetList))
    }

    //endregion

    //region settings
    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: String?): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Int): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Long): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Float): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: Boolean): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Encodes the settings.
     *
     * @param key The settings key.
     * @param value The settings value.
     * @return Whether the encoding was successful.
     */
    fun encodeSettings(key: String, value: MutableSet<String>): Boolean {
        return settingsStorage.encode(key, value)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String): String? {
        return settingsStorage.decodeString(key)
    }

    /**
     * Decodes the settings string.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsString(key: String, defaultValue: String?): String? {
        return settingsStorage.decodeString(key, defaultValue)
    }

    /**
     * Decodes the settings integer.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsInt(key: String, defaultValue: Int): Int {
        return settingsStorage.decodeInt(key, defaultValue)
    }

    /**
     * Decodes the settings long.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsLong(key: String, defaultValue: Long): Long {
        return settingsStorage.decodeLong(key, defaultValue)
    }

    /**
     * Decodes the settings float.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsFloat(key: String, defaultValue: Float): Float {
        return settingsStorage.decodeFloat(key, defaultValue)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String): Boolean {
        return settingsStorage.decodeBool(key, false)
    }

    /**
     * Decodes the settings boolean.
     *
     * @param key The settings key.
     * @param defaultValue The default value.
     * @return The settings value.
     */
    fun decodeSettingsBool(key: String, defaultValue: Boolean): Boolean {
        return settingsStorage.decodeBool(key, defaultValue)
    }

    /**
     * Decodes the settings string set.
     *
     * @param key The settings key.
     * @return The settings value.
     */
    fun decodeSettingsStringSet(key: String): MutableSet<String>? {
        return settingsStorage.decodeStringSet(key)
    }


    /**
     * Encodes the start on boot setting.
     *
     * @param startOnBoot Whether to start on boot.
     */
    fun encodeStartOnBoot(startOnBoot: Boolean) {
        encodeSettings(PREF_IS_BOOTED, startOnBoot)
    }

    /**
     * Decodes the start on boot setting.
     *
     * @return Whether to start on boot.
     */
    fun decodeStartOnBoot(): Boolean {
        return decodeSettingsBool(PREF_IS_BOOTED, false)
    }

    //endregion

    //region WebDAV

    /**
     * Encodes the WebDAV config as JSON into storage.
     */
    fun encodeWebDavConfig(config: WebDavConfig): Boolean {
        return mainStorage.encode(KEY_WEBDAV_CONFIG, JsonUtil.toJson(config))
    }

    /**
     * Decodes the WebDAV config from storage.
     */
    fun decodeWebDavConfig(): WebDavConfig? {
        val json = mainStorage.decodeString(KEY_WEBDAV_CONFIG) ?: return null
        return JsonUtil.fromJsonSafe(json, WebDavConfig::class.java)
    }

    //endregion

    //region Compose helpers for Settings

    /**
     * MMKV-backed String state, auto-persists and notifies on change.
     */
    @Composable
    fun rememberMmkvString(
        key: String,
        default: String = ""
    ): MutableState<String> {
        val state = remember(key) {
            mutableStateOf(decodeSettingsString(key, default) ?: default)
        }

        LaunchedEffect(key) {
            snapshotFlow { state.value }
                .drop(1)
                .distinctUntilChanged()
                .collectLatest { value ->
                    encodeSettings(key, value)
                    SettingsChangeManager.notifySettingChanged(key)
                }
        }
        return state
    }

    /**
     * MMKV-backed Boolean state, auto-persists and notifies on change.
     */
    @Composable
    fun rememberMmkvBool(
        key: String,
        default: Boolean = false
    ): MutableState<Boolean> {
        val state = remember(key) {
            mutableStateOf(decodeSettingsBool(key, default))
        }

        LaunchedEffect(key) {
            snapshotFlow { state.value }
                .drop(1)
                .distinctUntilChanged()
                .collectLatest { value ->
                    encodeSettings(key, value)
                    SettingsChangeManager.notifySettingChanged(key)
                }
        }
        return state
    }

    //endregion
}
