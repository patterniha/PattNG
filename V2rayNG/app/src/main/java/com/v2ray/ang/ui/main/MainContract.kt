package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus

    /** Running, but the profile is still bringing its tunnel up; the daemon supplies the text. */
    data class Connecting(val message: String) : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    /**
     * PattNG: a move of a profile the storage refused, still to be told, by a number of its own, so that a refusal set
     * again right after the last one was told is told too; null when none, see [MainAction.MoveRefusalShown].
     */
    val moveRefusal: Int? = null
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction

    /** The screen became visible: what it shows of the service may be out of date. */
    data object RefreshServiceState : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data class UpdateGroupSubscription(val groupId: String) : MainAction
    data object ExportAll : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction

    /** PattNG: a drag moved the profile [fromGuid] names to where the one [toGuid] names stands in [groupId]. */
    data class MoveServer(val groupId: String, val fromGuid: String, val toGuid: String) : MainAction

    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction
    data class OpenSubscriptionLink(val url: String) : MainAction

    /** PattNG: the Donate dialog's copy button of [address], one of AppConfig.DONATION_WALLETS. */
    data class CopyDonationAddress(val address: String) : MainAction
    /** PattNG: the Donate dialog's link of the author's ID, AppConfig.AUTHOR_TELEGRAM_URL. */
    data object OpenAuthorTelegram : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction

    /** PattNG: the refusal of a move numbered [refusal], see [MainUiState.moveRefusal], was told. */
    data class MoveRefusalShown(val refusal: Int) : MainAction
}
