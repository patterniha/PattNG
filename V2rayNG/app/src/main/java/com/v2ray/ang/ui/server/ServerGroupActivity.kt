package com.v2ray.ang.ui.server

import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.R
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.base.EditorLoading
import com.v2ray.ang.ui.base.EditorOutcomeEffect
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.ServerSelectListDialog
import com.v2ray.ang.ui.compose.SettingsSwitchItem

class ServerGroupActivity : BaseComponentActivity() {

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val subscriptionId by lazy { intent.getStringExtra("subscriptionId") }

    /** PattNG: the save, which outlives this activity when it is recreated, see [ServerGroupViewModel]. */
    private val viewModel: ServerGroupViewModel by viewModels {
        viewModelFactory {
            initializer {
                ServerGroupViewModel(application, ProfileEditorRepository(), editGuid, subscriptionId, intent.getBooleanExtra("isRunning", false))
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        // PattNG: read off the main thread, see ProfileEditorViewModel.isRunning; no delete is offered until it is known.
        val running by viewModel.isRunning.collectAsStateWithLifecycle()
        val opened by viewModel.opened.collectAsStateWithLifecycle()
        EditorOutcomeEffect(
            viewModel = viewModel,
            onSaved = { guid ->
                ProfileEditorResult.run {
                    finishSaved(
                        guid = guid,
                        restartService = viewModel.isRunning.value == true
                    )
                }
            },
            onDeleted = {
                ProfileEditorResult.run {
                    finishDeleted(editGuid)
                }
            }
        )
        // PattNG: the group, the subscriptions and the names of the profiles are read off the main thread; until they are,
        // the screen waits.
        val group = opened
        if (group == null) {
            EditorLoading(EConfigType.POLICYGROUP.toString()) { finish() }
            return
        }
        val all = stringResource(R.string.filter_config_all)
        val numbered = stringResource(R.string.label_numbered)
        val subscriptions = remember(group, all, numbered) {
            policyGroupSubscriptions(all = all, subscriptions = group.subscriptions, numbered = { name, number -> numbered.format(name, number) })
        }
        ServerGroupScreen(
            editGuid = editGuid,
            isRunning = running != false,
            subscriptions = subscriptions,
            initialRemarks = group.remarks,
            initialFilter = group.filter,
            initialType = group.type,
            // PattNG: the subscription is picked by its key: a group of a subscription gone, or a new one, starts on all.
            initialSubscriptionId = subscriptions.pick(group.pickedSubscription).id,
            initialTestOutbounds = group.testOutbounds,
            initialFallbackTag = group.fallbackTag,
            fallbackSuggestions = group.fallbackSuggestions,
            onBackClick = { finish() },
            onSave = { remarks, filter, typeIdx, subscription, testOutbounds, fallbackTag ->
                saveServer(subscriptions, remarks, filter, typeIdx, subscription, testOutbounds, fallbackTag)
            },
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

    private fun saveServer(
        subscriptions: List<PolicyGroupSubscription>,
        remarks: String,
        filter: String,
        typeIdx: Int,
        subscriptionId: String,
        testOutbounds: Boolean,
        fallbackTag: String,
    ) {
        val subscription = subscriptions.pick(subscriptionId)
        viewModel.save(
            PolicyGroupEdit(
                remarks = remarks,
                filter = filter,
                type = typeIdx,
                typeLabel = stringArrayPolicyGroupType().getOrNull(typeIdx).orEmpty(),
                subscriptionId = subscription.id,
                subscriptionLabel = subscription.label,
                testOutbounds = testOutbounds,
                fallbackTag = fallbackTag,
            )
        )
    }

    private fun stringArrayPolicyGroupType(): Array<String> =
        resources.getStringArray(R.array.policy_group_type)
}

@Composable
fun ServerGroupScreen(
    editGuid: String,
    isRunning: Boolean,
    subscriptions: List<PolicyGroupSubscription>,
    initialRemarks: String,
    initialFilter: String,
    initialType: Int,
    initialSubscriptionId: String,
    initialTestOutbounds: Boolean,
    initialFallbackTag: String,
    fallbackSuggestions: List<String>,
    onBackClick: () -> Unit,
    onSave: (String, String, Int, String, Boolean, String) -> Unit,
    onDelete: () -> Unit
) {
    val typeEntries = stringArrayResource(R.array.policy_group_type).toList()

    var remarks by rememberSaveable { mutableStateOf(initialRemarks) }
    var isRemarksError by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(initialFilter) }
    var typeValue by rememberSaveable { mutableStateOf(typeEntries.getOrNull(initialType).orEmpty()) }
    // PattNG: the subscription picked, by its key; the list shows it by its label.
    var subscriptionId by rememberSaveable { mutableStateOf(initialSubscriptionId) }
    val pickedSubscription = subscriptions.pick(subscriptionId)
    var testOutbounds by rememberSaveable { mutableStateOf(initialTestOutbounds) }
    var fallbackTag by rememberSaveable { mutableStateOf(initialFallbackTag) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showFallbackDialog by rememberSaveable { mutableStateOf(false) }
    val showDelete = editGuid.isNotEmpty() && !isRunning
    val selectedType = typeEntries.indexOf(typeValue).coerceAtLeast(0).toString()
    val supportsObservatory = BalancerStrategyType.from(selectedType).supportsObservatory

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = EConfigType.POLICYGROUP.toString(),
                onBackClick = onBackClick,
                actions = {
                    if (showDelete) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.acc_delete))
                        }
                    }
                    IconButton(onClick = {
                        val remarksErr = remarks.isBlank()
                        isRemarksError = remarksErr

                        val hasError = remarksErr
                        if (!hasError) {
                            val typeIdx = typeEntries.indexOf(typeValue).coerceAtLeast(0)
                            onSave(remarks, filter, typeIdx, pickedSubscription.id, testOutbounds, fallbackTag)
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
                .padding(vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            FormTextField(
                label = stringResource(R.string.server_lab_remarks),
                value = remarks,
                onValueChange = { remarks = it },
                isError = isRemarksError
            )
            FormDropdownField(
                label = stringResource(R.string.title_policy_group_type),
                value = typeValue,
                options = typeEntries,
                onValueChange = { typeValue = it }
            )
            FormDropdownField(
                label = stringResource(R.string.title_policy_group_subscription_id),
                value = pickedSubscription.label,
                options = subscriptions.map { it.label },
                onValueChange = { label -> subscriptions.firstOrNull { it.label == label }?.let { subscriptionId = it.id } }
            )
            FormTextField(stringResource(R.string.title_policy_group_subscription_filter), filter, { filter = it })
            if (supportsObservatory) {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_policy_group_test_outbounds),
                    checked = testOutbounds,
                    onCheckedChange = { testOutbounds = it }
                )
                if (testOutbounds) {
                    val fallbackLabel = stringResource(R.string.title_policy_group_fallback)
                    val fallbackDisplay = fallbackTag.ifBlank { stringResource(R.string.server_proxy_chain_member_unselected) }
                    Text(
                        text = fallbackLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = fallbackLabel
                                    stateDescription = fallbackDisplay
                                }
                                .clickable(role = Role.Button) { showFallbackDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = fallbackDisplay,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (fallbackTag.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (fallbackTag.isBlank()) FontWeight.Normal else FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clearAndSetSemantics {}
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                        IconButton(onClick = { fallbackTag = "" }) {
                            Icon(
                                painterResource(R.drawable.ic_delete_24dp),
                                contentDescription = "${stringResource(R.string.logcat_clear)}, $fallbackLabel"
                            )
                        }
                    }
                }
                NavigationBarsSpacer()
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_policy_group),
            itemName = initialRemarks,
            onConfirm = onDelete,
            onDismiss = { showDeleteConfirm = false }
        )
    }
    if (showFallbackDialog && supportsObservatory && testOutbounds) {
        ServerSelectListDialog(
            title = stringResource(R.string.title_policy_group_fallback),
            options = fallbackSuggestions,
            selectedOption = fallbackTag,
            onSelected = {
                fallbackTag = it
                showFallbackDialog = false
            },
            onDismiss = { showFallbackDialog = false }
        )
    }
}
