package com.v2ray.ang.ui.routing

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.v2ray.ang.AppConfig.BUILTIN_OUTBOUND_TAGS
import com.v2ray.ang.AppConfig.TAG_PROXY
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.ui.apppicker.AppPickerActivity
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
import com.v2ray.ang.ui.compose.verticalScrollbar

private val ROUTING_NETWORK_OPTIONS = listOf("tcp", "udp", "tcp,udp")

/** PattNG: where the editor keeps the id of its rule, to find the rule again should its process be gone before it is back. */
private const val KEY_RULE_ID = "routing_edit_rule_id"

/** PattNG: the id of the rule the routing list opened the editor on, by which the editor finds it. */
internal const val EXTRA_RULE_ID = "rule_id"

class RoutingEditActivity : BaseComponentActivity() {
    private val position by lazy { intent.getIntExtra("position", -1) }

    /**
     * PattNG: the id of the rule the editor is on, as its saved state kept it, see [onSaveInstanceState], or as the list
     * that opened it named it.
     */
    private var reopenedRuleId: String? = null

    /**
     * PattNG: the save and the delete, which outlive this activity when it is recreated, see [RoutingEditViewModel], and
     * the rule the editor opened on, read once, off the main thread, see [openedRule].
     */
    private val viewModel: RoutingEditViewModel by viewModels {
        viewModelFactory {
            initializer {
                RoutingEditViewModel(application, RoutingEditRepository(), position, reopenedRuleId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reopenedRuleId = savedInstanceState?.getString(KEY_RULE_ID) ?: intent.getStringExtra(EXTRA_RULE_ID)?.takeIf { it.isNotEmpty() }
    }

    @Composable
    override fun ScreenContent() {
        val opened by viewModel.opened.collectAsStateWithLifecycle()
        EditorOutcomeEffect(
            viewModel = viewModel,
            onSaved = { finish() },
            onDeleted = { finish() }
        )
        // PattNG: the rule, the names of the profiles and whether a rule can match the app a connection comes from are
        // read off the main thread; until they are, the screen waits.
        val rule = opened
        if (rule == null) {
            EditorLoading(stringResource(R.string.routing_settings_rule_title)) { finish() }
            return
        }
        RoutingEditScreen(
            initial = rule.rule,
            outboundSuggestions = rule.outboundSuggestions,
            canUseProcess = rule.canUseProcess,
            onBackClick = { finish() },
            onSave = { viewModel.save(it) },
            onDelete = { viewModel.delete() }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // PattNG: while the rule is read, the id it is read by.
        (viewModel.ruleId ?: reopenedRuleId)?.takeIf { it.isNotEmpty() }?.let { outState.putString(KEY_RULE_ID, it) }
    }

    /**
     * PattNG: the screen closes only once the save or the delete that runs has written, telling the screen it returns
     * to what it did, see [com.v2ray.ang.ui.base.EditorViewModel.leaveScreen].
     */
    override fun finish() {
        if (viewModel.leaveScreen()) super.finish()
    }
}

@Composable
fun RoutingEditScreen(
    initial: RulesetItem?,
    outboundSuggestions: List<String>,
    canUseProcess: Boolean,
    onBackClick: () -> Unit,
    onSave: (RulesetItem) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val processSelectTitle = stringResource(R.string.routing_settings_process_select)
    val scrollState = rememberScrollState()

    var remarks by rememberSaveable { mutableStateOf(initial?.remarks ?: "") }
    var isRemarksError by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(initial?.locked == true) }
    var domain by rememberSaveable { mutableStateOf(initial?.domain?.joinToString(",") ?: "") }
    var ip by rememberSaveable { mutableStateOf(initial?.ip?.joinToString(",") ?: "") }
    var processText by rememberSaveable { mutableStateOf(initial?.process?.joinToString(",") ?: "") }
    var protocol by rememberSaveable { mutableStateOf(initial?.protocol?.joinToString(",") ?: "") }
    var network by rememberSaveable { mutableStateOf(initial?.network ?: "") }
    var port by rememberSaveable { mutableStateOf(initial?.port ?: "") }
    var outboundTag by rememberSaveable {
        mutableStateOf(initial?.outboundTag ?: BUILTIN_OUTBOUND_TAGS.first())
    }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showOutboundDialog by rememberSaveable { mutableStateOf(false) }
    val selectedNetwork = network.ifBlank { "tcp,udp" }

    val processPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedPackages = AppPickerActivity.getSelectedPackages(result.data)
            processText = selectedPackages.joinToString(",")
        }
    }

    // The rule as the screen opened with it, read by the activity, with what this screen edits set on it.
    fun buildRuleset(): RulesetItem {
        val rulesetItem = initial?.copy() ?: RulesetItem()
        rulesetItem.apply {
            this.remarks = remarks
            this.locked = locked
            this.domain = domain.nullIfBlank()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            this.ip = ip.nullIfBlank()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            this.process = processText.nullIfBlank()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            this.protocol = protocol.nullIfBlank()
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            this.port = port.nullIfBlank()
            this.network = network.nullIfBlank()
            this.outboundTag = outboundTag.trim().ifEmpty { TAG_PROXY }
        }
        return rulesetItem
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.routing_settings_rule_title),
                onBackClick = onBackClick,
                actions = {
                    // PattNG: a rule the editor found, by its id, or at its position before rules had ids; one gone by
                    // then opens as a new one, with none to delete.
                    if (initial != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                painterResource(R.drawable.ic_delete_24dp),
                                contentDescription = stringResource(R.string.acc_delete)
                            )
                        }
                    }
                    IconButton(onClick = {
                        val remarksErr = remarks.isBlank()
                        isRemarksError = remarksErr

                        val hasError = remarksErr
                        if (!hasError) {
                            onSave(buildRuleset())
                        }
                    }) {
                        Icon(
                            painterResource(R.drawable.ic_fab_check),
                            contentDescription = stringResource(R.string.acc_save)
                        )
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
        ) {
            FormTextField(
                label = stringResource(R.string.sub_setting_remarks),
                value = remarks,
                onValueChange = { remarks = it },
                isError = isRemarksError
            )
            SettingsSwitchItem(
                title = stringResource(R.string.routing_settings_locked),
                checked = locked,
                onCheckedChange = { locked = it }
            )
            Text(
                text = stringResource(R.string.routing_settings_tips),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FormTextField(
                label = stringResource(R.string.routing_settings_domain),
                placeholder = stringResource(R.string.routing_settings_comma_tip),
                value = domain,
                onValueChange = { domain = it }
            )
            FormTextField(
                label = stringResource(R.string.routing_settings_ip),
                placeholder = stringResource(R.string.routing_settings_comma_tip),
                value = ip,
                onValueChange = { ip = it }
            )
            FormTextField(
                label = stringResource(R.string.routing_settings_process),
                placeholder = stringResource(R.string.routing_settings_comma_tip),
                value = processText,
                onValueChange = { processText = it },
                enabled = canUseProcess
            )
            if (canUseProcess) {
                TextButton(
                    onClick = {
                        val current = processText
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                        processPickerLauncher.launch(
                            AppPickerActivity.createIntent(
                                context = context,
                                selectedPackages = current,
                                title = processSelectTitle
                            )
                        )
                    },
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    Icon(
                        painterResource(R.drawable.ic_per_apps_24dp),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(processSelectTitle)
                }
            }
            FormTextField(
                label = stringResource(R.string.routing_settings_port),
                value = port,
                onValueChange = { port = it }
            )
            FormTextField(
                label = stringResource(R.string.routing_settings_protocol),
                placeholder = stringResource(R.string.routing_settings_protocol_tip),
                value = protocol,
                onValueChange = { protocol = it }
            )
            FormDropdownField(
                label = stringResource(R.string.routing_settings_network),
                value = selectedNetwork,
                options = ROUTING_NETWORK_OPTIONS,
                onValueChange = { network = it }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FormDropdownField(
                    label = stringResource(R.string.routing_settings_outbound_tag),
                    placeholder = stringResource(
                        R.string.routing_settings_outbound_tag_hint,
                        stringResource(R.string.server_lab_remarks)
                    ),
                    value = outboundTag,
                    options = BUILTIN_OUTBOUND_TAGS.toList(),
                    onValueChange = { outboundTag = it },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showOutboundDialog = true }) {
                    Icon(
                        painterResource(R.drawable.ic_search_24dp),
                        contentDescription = stringResource(R.string.title_file_chooser)
                    )
                }
            }
            Spacer(modifier = Modifier.height(36.dp))
            NavigationBarsSpacer()
        }

        if (showDeleteConfirm) {
            DeleteConfirmDialog(
                message = stringResource(R.string.confirm_delete_routing_rule),
                itemName = initial?.remarks.orEmpty(),
                onConfirm = onDelete,
                onDismiss = { showDeleteConfirm = false }
            )
        }
        if (showOutboundDialog) {
            ServerSelectListDialog(
                title = stringResource(R.string.routing_settings_outbound_tag),
                options = outboundSuggestions,
                selectedOption = outboundTag,
                onSelected = {
                    outboundTag = it
                    showOutboundDialog = false
                },
                onDismiss = { showOutboundDialog = false }
            )
        }
    }
}
