package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.CustomFmt
import com.v2ray.ang.fmt.CustomOutboundFmt
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.base.EditorOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ServerCustomConfigViewModelTest {

    private val source = FakeProfileEditorSource()

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.stored[GUID] = ProfileItem.create(EConfigType.CUSTOM).apply {
            remarks = "old"
            server = "198.51.100.1"
            serverPort = "80"
            subscriptionId = "own"
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ServerCustomConfigViewModel(mock<Application>(), source, GUID)

    @Test
    fun startsWithNoOutcome() {
        assertNull(viewModel().outcome.value)
    }

    @Test
    fun blankRemarksSaveNothingAndTellNothing() {
        val viewModel = viewModel()

        viewModel.save("  ", CONTENT)

        assertNull(viewModel.outcome.value)
        assertTrue(source.parsedFor.isEmpty())
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun theConfigurationIsStoredWithItsProfileAsStoredThen() {
        source.parsed = Result.success(ProfileItem.create(EConfigType.CUSTOM).apply {
            remarks = "from the configuration"
            server = "203.0.113.9"
            serverPort = "8443"
        })
        val viewModel = viewModel()

        viewModel.save("custom", CONTENT)

        assertEquals(EditorOutcome.Saved(GUID), viewModel.outcome.value)
        assertEquals(listOf(GUID), source.parsedFor)
        val stored = source.stored.getValue(GUID)
        assertEquals("custom", stored.remarks)
        assertEquals("203.0.113.9", stored.server)
        assertEquals("8443", stored.serverPort)
        assertEquals(AngConfigManager.generateDescription(stored), stored.description)
        // A custom profile stays in the subscription it is in.
        assertEquals("own", stored.subscriptionId)
        assertEquals(CONTENT, source.raws[GUID])
    }

    @Test
    fun aCustomOutboundIsStoredAsReadTaggedAsTheProxyAndStaysOne() {
        source.stored[GUID] = ProfileItem.create(EConfigType.CUSTOM_OUTBOUND).apply { remarks = "old" }
        source.parsed = Result.failure(IllegalStateException("read as a whole configuration"))
        source.parsedOutbound = Result.success(ProfileItem.create(EConfigType.CUSTOM_OUTBOUND).apply {
            server = "203.0.113.9"
            serverPort = "443"
        } to OUTBOUND)
        val viewModel = viewModel()
        assertEquals(CustomConfigOpening("old", "", EConfigType.CUSTOM_OUTBOUND), viewModel.opened.value)

        viewModel.save("outbound", "{\"protocol\": \"vless\"}")

        assertEquals(EditorOutcome.Saved(GUID), viewModel.outcome.value)
        val stored = source.stored.getValue(GUID)
        assertEquals(EConfigType.CUSTOM_OUTBOUND, stored.configType)
        assertEquals("outbound", stored.remarks)
        assertEquals("203.0.113.9", stored.server)
        assertEquals("443", stored.serverPort)
        // The outbound as read, not as written.
        assertEquals(OUTBOUND, source.raws[GUID])
    }

    @Test
    fun aCustomOutboundThatCannotBeReadIsToldAndNothingIsStored() {
        source.stored[GUID] = ProfileItem.create(EConfigType.CUSTOM_OUTBOUND).apply { remarks = "old" }
        source.parsedOutbound = Result.failure(IllegalStateException("outer", IllegalArgumentException("line 1")))
        val viewModel = viewModel()

        viewModel.save("outbound", "{")

        assertEquals(EditorOutcome.Refused(R.string.toast_malformed_json_detail, listOf("line 1")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
        assertTrue(source.raws.isEmpty())
    }

    @Test
    fun theReaderOfACustomOutboundTagsItAsTheProxy() {
        // What the repository reads with, see ProfileEditorRepository.parseCustomOutbound.
        val (profile, raw) = CustomOutboundFmt.parse("""{"tag": "mine", "protocol": "vless", "settings": {"address": "203.0.113.9", "port": 443}}""")
        assertEquals(EConfigType.CUSTOM_OUTBOUND, profile.configType)
        assertEquals("203.0.113.9", profile.server)
        assertEquals("443", profile.serverPort)
        assertTrue(raw.contains("\"tag\": \"proxy\""), raw)
    }

    @Test
    fun aConfigurationThatCannotBeReadIsToldAndNothingIsStored() {
        source.parsed = Result.failure(IllegalStateException("outer", IllegalArgumentException("line 3")))
        val viewModel = viewModel()

        viewModel.save("custom", "{")

        assertEquals(EditorOutcome.Refused(R.string.toast_malformed_json_detail, listOf("line 3")), viewModel.outcome.value)
        assertEquals(listOf(GUID), source.parsedFor)
        assertTrue(source.saves.isEmpty())
        assertTrue(source.raws.isEmpty())
    }

    @Test
    fun aRefusalSaysWhatTheCauseSaysElseWhatTheFailureSaysElseNothing() {
        val detail = R.string.toast_malformed_json_detail
        assertEquals(EditorOutcome.Refused(detail, listOf("cause")), malformedConfig(Exception("failure", Exception("cause"))))
        assertEquals(EditorOutcome.Refused(detail, listOf("failure")), malformedConfig(Exception("failure", Exception(" "))))
        assertEquals(EditorOutcome.Refused(detail, listOf("failure")), malformedConfig(Exception("failure")))
        assertEquals(EditorOutcome.Refused(R.string.toast_malformed_json), malformedConfig(Exception(" ")))
        assertEquals(EditorOutcome.Refused(R.string.toast_malformed_json), malformedConfig(Exception()))
    }

    @Test
    fun aSecondTapWhileTheSaveRunsSavesNothingMore() {
        val gate = CompletableDeferred<Unit>()
        source.saveGate = gate
        val viewModel = viewModel()

        viewModel.save("custom", CONTENT)
        viewModel.save("custom", CONTENT)
        gate.complete(Unit)

        assertEquals(listOf(GUID), source.saves)
        assertEquals(EditorOutcome.Saved(GUID), viewModel.outcome.value)
    }

    @Test
    fun aDeleteDeletesTheProfileUnlessTheAppRunsOnIt() {
        source.selected = GUID
        val viewModel = viewModel()

        viewModel.delete()
        assertEquals(EditorOutcome.Refused(R.string.toast_action_not_allowed), viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())

        viewModel.onOutcomeHandled()
        source.selected = null
        viewModel.delete()
        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf(GUID), source.deletes)
    }

    @Test
    fun aWriteTheStorageRefusesIsToldAndNothingIsStored() {
        source.refuseWrites = true
        val viewModel = viewModel()

        viewModel.save("custom", CONTENT)

        assertEquals(EditorOutcome.Refused(R.string.toast_failure), viewModel.outcome.value)
        assertEquals("old", source.stored.getValue(GUID).remarks)
        assertTrue(source.raws.isEmpty())
    }

    @Test
    fun theReaderGivesTheServerOfAConfigurationAndSaysWhatItCannotRead() {
        // What the repository reads with, see ProfileEditorRepository.parseCustomConfig.
        val parsed = CustomFmt.parse("""{"remarks": "real", "outbounds": [{"protocol": "vless", "settings": {"address": "203.0.113.9", "port": 443}}]}""")
        assertEquals("real", parsed.remarks)
        assertEquals("203.0.113.9", parsed.server)
        assertEquals("443", parsed.serverPort)

        val refusal = malformedConfig(assertThrows(Exception::class.java) { CustomFmt.parse("""{"outbounds": [""") })
        assertEquals(R.string.toast_malformed_json_detail, refusal.message)
        assertTrue(refusal.args.single().isNotBlank())
    }

    private companion object {
        const val GUID = "custom-guid"
        const val CONTENT = "{\"outbounds\": []}"
        const val OUTBOUND = "{\"tag\": \"proxy\", \"protocol\": \"vless\"}"
    }

    @Test
    fun theProfileAndItsConfigurationAreReadOffTheMainThreadBeforeTheScreenShowsThem() {
        source.raws[GUID] = CONTENT
        val gate = CompletableDeferred<Unit>()
        source.openGate = gate

        val viewModel = viewModel()
        assertNull(viewModel.opened.value)
        gate.complete(Unit)

        assertEquals(CustomConfigOpening("old", CONTENT), viewModel.opened.value)

        // A profile stored without its configuration opens on none.
        source.openGate = null
        source.raws.clear()
        assertEquals(CustomConfigOpening("old", ""), viewModel().opened.value)
    }

    @Test
    fun aCustomProfileGoneOrNewOpensOnEmptyFields() {
        assertEquals(CustomConfigOpening("", ""), ServerCustomConfigViewModel(mock<Application>(), source, "gone").opened.value)
        assertEquals(CustomConfigOpening("", ""), ServerCustomConfigViewModel(mock<Application>(), source, "").opened.value)
    }
}
