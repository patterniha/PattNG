package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.DeleteConfirmDialog

data class ServerDeleteTarget(
    val guid: String,
    val profileName: String,
) {
    companion object {
        val Saver = listSaver<ServerDeleteTarget?, String>(
            save = { target -> target?.let { listOf(it.guid, it.profileName) }.orEmpty() },
            restore = { ServerDeleteTarget(it[0], it[1]) },
        )
    }
}

@Composable
fun MainDialogs(
    showDelAllConfirm: Boolean,
    onDismissDelAll: () -> Unit,
    onConfirmDelAll: () -> Unit,
    showDelDuplicateConfirm: Boolean,
    onDismissDelDuplicate: () -> Unit,
    onConfirmDelDuplicate: () -> Unit,
    showDelInvalidConfirm: Boolean,
    onDismissDelInvalid: () -> Unit,
    onConfirmDelInvalid: () -> Unit,
    showRemoveConfirm: ServerDeleteTarget?,
    onDismissRemove: () -> Unit,
    onConfirmRemove: (String) -> Unit,
    showDonate: Boolean,
    onDismissDonate: () -> Unit,
    onCopyDonationAddress: (String) -> Unit,
    onOpenAuthorTelegram: () -> Unit,
) {
    if (showDelAllConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_visible_profiles),
            onConfirm = onConfirmDelAll,
            onDismiss = onDismissDelAll
        )
    }
    if (showDelDuplicateConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_duplicate_profiles),
            onConfirm = onConfirmDelDuplicate,
            onDismiss = onDismissDelDuplicate
        )
    }
    if (showDelInvalidConfirm) {
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_invalid_profiles),
            onConfirm = onConfirmDelInvalid,
            onDismiss = onDismissDelInvalid
        )
    }
    showRemoveConfirm?.let { target ->
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_profile),
            itemName = target.profileName,
            onConfirm = { onConfirmRemove(target.guid) },
            onDismiss = onDismissRemove
        )
    }
    if (showDonate) {
        DonateDialog(onCopy = onCopyDonationAddress, onOpenAuthor = onOpenAuthorTelegram, onDismiss = onDismissDonate)
    }
}

/**
 * PattNG: PattNG's donation wallets, each address selectable and copied whole by its button, and below them the author's
 * ID, a link to the author's Telegram; OK closes it.
 */
@Composable
private fun DonateDialog(onCopy: (String) -> Unit, onOpenAuthor: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_donate)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppConfig.DONATION_WALLETS.forEach { (network, address) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = network, style = MaterialTheme.typography.titleSmall)
                            SelectionContainer {
                                Text(
                                    text = address,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        IconButton(onClick = { onCopy(address) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = stringResource(R.string.acc_copy_address, network)
                            )
                        }
                    }
                }
                // The author's ID below the wallets, a link to the author's Telegram
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "ID", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = AppConfig.AUTHOR_ID,
                            modifier = Modifier.clickable(role = Role.Button, onClick = onOpenAuthor),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            textDecoration = TextDecoration.Underline
                        )
                    }
                    IconButton(onClick = onOpenAuthor) {
                        Icon(
                            painter = painterResource(R.drawable.ic_telegram_24dp),
                            contentDescription = stringResource(R.string.title_tg_channel)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
