package com.v2ray.ang.ui.subscription

import android.text.TextUtils
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.extension.toLongEx
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.base.EditorLoading
import com.v2ray.ang.ui.base.EditorOutcomeEffect
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.ServerSelectListDialog
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.Utils

class SubEditActivity : BaseComponentActivity() {
    private val editSubId by lazy { intent.getStringExtra("subId").orEmpty() }

    /** PattNG: the save and the delete, which outlive this activity when it is recreated, see [SubEditViewModel]. */
    private val viewModel: SubEditViewModel by viewModels {
        viewModelFactory {
            initializer { SubEditViewModel(application, SubEditRepository(), editSubId) }
        }
    }

    @Composable
    override fun ScreenContent() {
        val opened by viewModel.opened.collectAsStateWithLifecycle()
        EditorOutcomeEffect(
            viewModel = viewModel,
            onSaved = { finish() },
            onDeleted = { finish() }
        )
        // PattNG: the subscription, the names of the profiles and whether a delete is confirmed first are read off the
        // main thread; until they are, the screen waits.
        val subscription = opened
        if (subscription == null) {
            EditorLoading(stringResource(R.string.title_sub_setting)) { finish() }
            return
        }
        SubEditScreen(
            editSubId = editSubId,
            initial = subscription.subscription,
            profileSuggestions = subscription.profileNames,
            confirmRemove = subscription.confirmRemove,
            onBackClick = { finish() },
            onSave = { saveServer(it) },
            onDelete = { viewModel.delete() }
        )
    }

    /**
     * PattNG: the screen closes only once the save or the delete that runs has written, telling the screen it returns
     * to what it did, see [com.v2ray.ang.ui.base.EditorViewModel.leaveScreen].
     */
    override fun finish() {
        if (viewModel.leaveScreen()) super.finish()
    }

    /**
     * Saves the subscription with [applyEdits], the edits of the screen read at the tap, made on the subscription as
     * stored when it is written: what a background update wrote meanwhile, as its update time, stays. PattNG: the
     * view model looks up the profiles it names and writes it, see [SubEditViewModel.save].
     */
    private fun saveServer(applyEdits: (SubscriptionItem) -> Unit) {
        val edited = SubscriptionItem().also(applyEdits)
        if (TextUtils.isEmpty(edited.remarks)) {
            return
        }
        if (edited.url.isNotEmpty()) {
            if (!Utils.isValidUrl(edited.url)) {
                return
            }
            if (!Utils.isValidSubUrl(edited.url) && !edited.allowInsecureUrl) {
                return
            }
        }

        if (edited.autoUpdate && edited.updateInterval < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES) {
            return
        }

        viewModel.save(applyEdits)
    }
}

@Composable
fun SubEditScreen(
    editSubId: String,
    initial: SubscriptionItem,
    profileSuggestions: List<String>,
    confirmRemove: Boolean,
    onBackClick: () -> Unit,
    onSave: ((SubscriptionItem) -> Unit) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var remarks by rememberSaveable { mutableStateOf(initial.remarks.orEmpty()) }
    var isRemarksError by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf(initial.url.orEmpty()) }
    var isUrlError by rememberSaveable { mutableStateOf(false) }
    var userAgent by rememberSaveable { mutableStateOf(initial.userAgent.orEmpty()) }
    var requestHeaders by rememberSaveable { mutableStateOf(initial.requestHeaders.orEmpty()) }
    var filter by rememberSaveable { mutableStateOf(initial.filter ?: "") }
    var overrideAddress by rememberSaveable { mutableStateOf(initial.overrideAddress ?: "") }
    var overridePort by rememberSaveable { mutableStateOf(initial.overridePort?.toString() ?: "") }
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var autoUpdate by rememberSaveable { mutableStateOf(initial.autoUpdate) }
    var updateInterval by rememberSaveable { mutableStateOf(initial.updateInterval.toString()) }
    var isUpdateIntervalError by rememberSaveable { mutableStateOf(false) }
    var allowInsecureUrl by rememberSaveable { mutableStateOf(initial.allowInsecureUrl) }
    var prevProfile by rememberSaveable { mutableStateOf(initial.prevProfile ?: "") }
    var nextProfile by rememberSaveable { mutableStateOf(initial.nextProfile ?: "") }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showPrevProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showNextProfileDialog by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // What this screen edits, read at the tap, as a change to make on a subscription; null, with the reason told, when
    // a field cannot be saved. The save makes the change on the subscription as stored when it writes it, off the main
    // thread, so the values are taken here rather than read from the screen's state then.
    fun edits(): ((SubscriptionItem) -> Unit)? {
        val overridePortText = overridePort.trim()
        val overridePortValue = overridePortText.toIntOrNull()?.takeIf { it in 1..65535 }
        if (overridePortText.isNotEmpty() && overridePortValue == null) {
            context.toast(R.string.toast_invalid_override_port)
            return null
        }
        val newRemarks = remarks
        val newUrl = url
        val newUserAgent = userAgent
        val newRequestHeaders = requestHeaders
        val newFilter = filter
        val newEnabled = enabled
        val newAutoUpdate = autoUpdate
        val newUpdateInterval = updateInterval.toLongEx()
        val newPrevProfile = prevProfile
        val newNextProfile = nextProfile
        val newAllowInsecureUrl = allowInsecureUrl
        val newOverrideAddress = overrideAddress.trim().ifEmpty { null }
        return { subItem ->
            subItem.remarks = newRemarks
            subItem.url = newUrl
            subItem.userAgent = newUserAgent
            subItem.requestHeaders = newRequestHeaders
            subItem.filter = newFilter
            subItem.enabled = newEnabled
            subItem.autoUpdate = newAutoUpdate
            subItem.updateInterval = newUpdateInterval
            subItem.prevProfile = newPrevProfile
            subItem.nextProfile = newNextProfile
            subItem.allowInsecureUrl = newAllowInsecureUrl
            subItem.overrideAddress = newOverrideAddress
            subItem.overridePort = overridePortValue
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_sub_setting),
                onBackClick = onBackClick,
                actions = {
                    if (editSubId.isNotEmpty()) {
                        IconButton(onClick = {
                            if (confirmRemove) showDeleteConfirm = true else onDelete()
                        }) {
                            Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.acc_delete))
                        }
                    }
                    IconButton(onClick = {
                        val remarksErr = remarks.isBlank()
                        val urlErr = url.isNotEmpty() && (
                            !Utils.isValidUrl(url) || (!Utils.isValidSubUrl(url) && !allowInsecureUrl)
                        )
                        val intervalErr = autoUpdate && updateInterval.toLongEx() < AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES

                        isRemarksError = remarksErr
                        isUrlError = urlErr
                        isUpdateIntervalError = intervalErr

                        val hasError = remarksErr || urlErr || intervalErr
                        if (!hasError) {
                            edits()?.let(onSave)
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_fab_check), contentDescription = stringResource(R.string.acc_save))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(scrollState)
                .verticalScrollbar(scrollState)
                .padding(vertical = 8.dp)
                .padding(bottom = 36.dp)
        ) {
            FormTextField(
                label = stringResource(R.string.sub_setting_remarks),
                value = remarks,
                onValueChange = { remarks = it },
                isError = isRemarksError
            )
            FormTextField(
                label = stringResource(R.string.sub_setting_url),
                value = url,
                onValueChange = { url = it },
                isError = isUrlError,
                supportingText = if (isUrlError) stringResource(R.string.toast_invalid_url) else null
            )
            FormTextField(stringResource(R.string.sub_setting_user_agent), userAgent, { userAgent = it })
            FormTextField(stringResource(R.string.sub_setting_request_headers), requestHeaders, { requestHeaders = it })
            FormTextField(stringResource(R.string.sub_setting_filter), filter, { filter = it })
            FormTextField(
                stringResource(R.string.sub_setting_override_address),
                overrideAddress,
                { overrideAddress = it },
                placeholder = stringResource(R.string.sub_setting_override_tip)
            )
            FormTextField(
                stringResource(R.string.sub_setting_override_port),
                overridePort,
                { overridePort = it },
                keyboardType = KeyboardType.Number,
                placeholder = stringResource(R.string.sub_setting_override_tip)
            )
            SettingsSwitchItem(
                title = stringResource(R.string.sub_setting_enable),
                checked = enabled,
                onCheckedChange = { enabled = it }
            )

            SettingsSwitchItem(
                title = stringResource(R.string.sub_auto_update),
                checked = autoUpdate,
                onCheckedChange = { autoUpdate = it }
            )

            FormTextField(
                label = stringResource(R.string.title_pref_auto_update_interval),
                value = updateInterval,
                onValueChange = { updateInterval = it },
                keyboardType = KeyboardType.Number,
                isError = isUpdateIntervalError,
                supportingText = if (isUpdateIntervalError) stringResource(R.string.toast_invalid_update_interval) else null
            )

            SettingsSwitchItem(
                title = stringResource(R.string.sub_allow_insecure_url),
                checked = allowInsecureUrl,
                onCheckedChange = { allowInsecureUrl = it }
            )
            ProfileSelectDialogField(
                label = stringResource(R.string.sub_setting_pre_profile),
                placeholder = stringResource(R.string.sub_setting_pre_profile_tip),
                value = prevProfile,
                onClick = { showPrevProfileDialog = true },
                onClear = { prevProfile = "" },
                supportingText = stringResource(R.string.sub_setting_entry_proxy_tip)
            )
            ProfileSelectDialogField(
                label = stringResource(R.string.sub_setting_next_profile),
                placeholder = stringResource(R.string.sub_setting_pre_profile_tip),
                value = nextProfile,
                onClick = { showNextProfileDialog = true },
                onClear = { nextProfile = "" },
                supportingText = stringResource(R.string.sub_setting_exit_proxy_tip)
            )
            NavigationBarsSpacer()
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_subscription_group),
            itemName = initial.remarks,
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }
    if (showPrevProfileDialog) {
        ServerSelectListDialog(
            title = stringResource(R.string.sub_setting_pre_profile),
            options = profileSuggestions,
            selectedOption = prevProfile,
            onSelected = {
                prevProfile = it
                showPrevProfileDialog = false
            },
            onDismiss = { showPrevProfileDialog = false }
        )
    }
    if (showNextProfileDialog) {
        ServerSelectListDialog(
            title = stringResource(R.string.sub_setting_next_profile),
            options = profileSuggestions,
            selectedOption = nextProfile,
            onSelected = {
                nextProfile = it
                showNextProfileDialog = false
            },
            onDismiss = { showNextProfileDialog = false }
        )
    }
}

@Composable
private fun ProfileSelectDialogField(
    label: String,
    placeholder: String,
    value: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
    supportingText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = value.ifEmpty { placeholder },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onClear) {
            Icon(
                painterResource(R.drawable.ic_delete_24dp),
                contentDescription = "${stringResource(R.string.logcat_clear)}, $label"
            )
        }
    }
}
