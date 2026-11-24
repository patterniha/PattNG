package com.v2ray.ang.ui.routing

import android.app.Application
import android.webkit.URLUtil
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.moveItem
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoutingSettingsViewModel(
    application: Application,
    private val source: RoutingSettingsSource,
) : BaseViewModel(application) {
    private val rulesets: MutableList<RulesetItem> = mutableListOf()

    private val _rulesetsFlow = MutableStateFlow<List<RulesetItem>>(emptyList())
    val rulesetsFlow: StateFlow<List<RulesetItem>> = _rulesetsFlow.asStateFlow()

    /** PattNG: the reads and writes of the stored rules, off the main thread, one at a time, in the order asked for. */
    private val storage = Mutex()

    /** PattNG: the reading of the rules the screen asked for last, see [reload]. */
    private var reloadJob: Job? = null

    fun getAll(): List<RulesetItem> = rulesets.toList()

    /**
     * Reads the rules anew and shows them. PattNG: off the main thread, see [RoutingSettingsSource.loadRules]: one
     * without an id, or repeating another's, as the copy of a locked rule an import stored before it was left out, is
     * put right first, for the list, keyed by them, to show. A reload a later one replaces stops.
     */
    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            val loaded = storage.withLock { source.loadRules() }
            rulesets.clear()
            rulesets.addAll(loaded)
            _rulesetsFlow.value = rulesets.toList()
        }
    }

    /**
     * Shows [item] in place of the rule of its id and stores it so. PattNG: by the id, as a position the screen took from
     * the list it showed may have moved by now; not while the rules are read anew, which would show what was read in its
     * place; stored even when the screen closes right after.
     */
    fun update(item: RulesetItem) {
        val index = rulesets.indexOfFirst { it.id == item.id }
        if (isReloading || index < 0) return
        rulesets[index] = item
        _rulesetsFlow.value = rulesets.toList()
        store { source.updateRule(item) }
    }

    /**
     * Shows the rule of [fromId] where the rule of [toId] stands and stores it there, by their ids as well, so that a
     * change another made to the stored list meanwhile, as an import, is not written over. PattNG: not while the rules
     * are read anew; stored even when the screen closes right after.
     */
    fun move(fromId: String, toId: String) {
        if (isReloading || !rulesets.moveItem(rulesets.indexOfFirst { it.id == fromId }, rulesets.indexOfFirst { it.id == toId })) return
        _rulesetsFlow.value = rulesets.toList()
        store { source.moveRule(fromId, toId) }
    }

    /**
     * PattNG: the rules as stored once what the screen asked to store before is, read off the main thread, see
     * [RoutingSettingsSource.loadRules]: for an export, which the list shown may lag behind, while it is read anew.
     */
    suspend fun storedRules(): List<RulesetItem> = storage.withLock { source.loadRules() }

    private val isReloading: Boolean
        get() = reloadJob?.isActive == true

    /** Runs [write] in its turn, to its end even when the screen is gone by then, which a change shown is owed. */
    private fun store(write: suspend () -> Unit) {
        viewModelScope.launch { withContext(NonCancellable) { storage.withLock { write() } } }
    }

    fun importRulesets(content: String?) {
        launchLoading {
            if (content.isNullOrBlank()) {
                toastError(R.string.toast_failure)
                return@launchLoading
            }

            val result = withContext(Dispatchers.IO) {
                var realContent = content.trim()
                if (URLUtil.isNetworkUrl(realContent)) {
                    val fetched = HttpUtil.getUrlContent(UrlContentRequest(url = realContent, timeout = 10000))
                    if (fetched.isNullOrEmpty()) {
                        val proxyUsername = SettingsManager.getSocksUsername()
                        val proxyPassword = SettingsManager.getSocksPassword()
                        val httpPort = SettingsManager.getHttpPort()
                        realContent = HttpUtil.getUrlContent(
                            UrlContentRequest(
                                url = realContent,
                                timeout = 10000,
                                httpPort = httpPort,
                                proxyUsername = proxyUsername,
                                proxyPassword = proxyPassword
                            )
                        ).orEmpty()
                    } else {
                        realContent = fetched
                    }
                }

                SettingsManager.resetRoutingRulesets(realContent)
            }

            if (result) {
                reload()
                toastSuccess(R.string.toast_success)
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }
}
