package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ServerProxyChainViewModelTest {

    private val source = FakeProfileEditorSource()

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.names.add("entry")
        source.names.add("exit")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(guid: String = "", subscriptionId: String? = null) =
        ServerProxyChainViewModel(mock<Application>(), source, guid, subscriptionId)

    private fun refused(message: Int, vararg args: String) = EditorOutcome.Refused(message, args.toList())

    @Test
    fun blankRemarksSaveNothingAndTellNothing() {
        val viewModel = viewModel()

        viewModel.save("  ", listOf("entry", "exit"))

        assertNull(viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aRowLeftUnchosenAndAChainOfOneAreTold() {
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", " "))
        assertEquals(refused(R.string.server_proxy_chain_members_unselected), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("entry"))
        assertEquals(refused(R.string.server_proxy_chain_members_insufficient), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
        assertEquals(0, source.names.lookups)
    }

    @Test
    fun aMemberNoProfileHasSeveralHaveOrWithoutAServerIsToldByItsName() {
        source.names.add("twice")
        source.names.add("twice")
        source.names.add("bare", server = null)
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "gone"))
        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.NOT_FOUND.message, "gone"), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf(" twice ", "exit"))
        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.SEVERAL.message, "twice"), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("entry", "bare"))
        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.NO_SERVER.message, "bare"), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun membersAreFoundAmongTheProfilesAChainCanGoThrough() {
        // A policy group cannot be a hop: a name only a group has is told as a group's.
        source.names.add("group", EConfigType.POLICYGROUP)
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "group"))

        assertEquals(refused(R.string.toast_profile_group_not_hop, "group"), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())

        // Nor can another chain or a custom configuration, each told as what it is.
        source.names.add("other chain", EConfigType.PROXYCHAIN)
        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("entry", "other chain"))
        assertEquals(refused(R.string.toast_profile_chain_not_hop, "other chain"), viewModel.outcome.value)
        source.names.add("custom", EConfigType.CUSTOM)
        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("custom", "entry"))
        assertEquals(refused(R.string.toast_profile_custom_not_hop, "custom"), viewModel.outcome.value)
        // A name no profile has is told as before.
        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("entry", "gone"))
        assertEquals(refused(CoreConfigContext.UnresolvedName.Reason.NOT_FOUND.message, "gone"), viewModel.outcome.value)

        // A profile that can be a hop is found by the name, beside the group that has it too.
        source.names.add("group")
        viewModel.onOutcomeHandled()
        viewModel.save("chain", listOf("entry", "group"))
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
    }

    @Test
    fun aSecondAetherMemberIsTold() {
        source.names.add("warp", EConfigType.AETHER, server = null)
        source.names.add("warp 2", EConfigType.AETHER, server = null)
        val viewModel = viewModel()

        viewModel.save("chain", listOf("warp", "entry", "warp 2"))

        assertEquals(refused(R.string.aether_chain_one_profile), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aNewChainIsStoredOnceAndALaterSaveWritesOverIt() {
        val viewModel = viewModel(subscriptionId = "sub")

        viewModel.save(" chain ", listOf(" entry", "exit "))
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        val stored = source.stored.getValue("guid-1")
        assertEquals(EConfigType.PROXYCHAIN, stored.configType)
        assertEquals("chain", stored.remarks)
        assertEquals(listOf("entry", "exit"), ProfileItem.proxyChainMembersOf(stored.proxyChainProfiles))
        assertEquals("entry -> exit", stored.description)
        assertEquals("sub", stored.subscriptionId)

        // As when the screen, recreated before it closed, is saved again: the chain it stored is written over.
        viewModel.onOutcomeHandled()
        viewModel.save("chain 2", listOf("exit", "entry"))

        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertEquals(listOf("guid-1", "guid-1"), source.saves)
        assertEquals(setOf("guid-1"), source.stored.keys)
        assertEquals("chain 2", source.stored.getValue("guid-1").remarks)
    }

    @Test
    fun aStoredChainIsWrittenOverAndKeepsItsSubscription() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN).apply { subscriptionId = "own" }
        val viewModel = viewModel(guid = "chain-guid", subscriptionId = "sub")

        viewModel.save("chain", listOf("entry", "exit"))

        assertEquals(EditorOutcome.Saved("chain-guid"), viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.saves)
        assertEquals("own", source.stored.getValue("chain-guid").subscriptionId)
    }

    @Test
    fun aMemberWhoseNameHasACommaIsStoredSoThatItReadsBack() {
        source.names.add("a, b")
        val viewModel = viewModel()

        viewModel.save("chain", listOf("a, b", "exit"))

        assertEquals(listOf("a, b", "exit"), ProfileItem.proxyChainMembersOf(source.stored.getValue("guid-1").proxyChainProfiles))
    }

    @Test
    fun aSecondTapWhileTheSaveRunsSavesNothingMore() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "exit"))
        viewModel.save("chain", listOf("entry", "exit"))
        gate.complete(Unit)

        // One lookup for each member, of the one save.
        assertEquals(2, source.names.lookups)
        assertEquals(listOf("guid-1"), source.saves)
    }

    @Test
    fun theScreenMayNotCloseWhileTheMembersAreLookedUp() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "exit"))
        assertFalse(viewModel.leaveScreen())
        gate.complete(Unit)

        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertFalse(viewModel.leaveScreen())
        viewModel.onOutcomeHandled()
        assertTrue(viewModel.leaveScreen())
    }

    @Test
    fun aDeleteDeletesTheChainUnlessTheAppRunsOnIt() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN)
        source.selected = "chain-guid"
        val viewModel = viewModel(guid = "chain-guid")

        viewModel.delete()
        assertEquals(refused(R.string.toast_action_not_allowed), viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())

        // Told, the screen stays open, and deletes once the app runs on another profile.
        viewModel.onOutcomeHandled()
        source.selected = "other"
        viewModel.delete()
        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.deletes)
    }

    @Test
    fun aDeleteConfirmedWhileTheMembersAreLookedUpWaitsForTheSaveThenDeletes() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN)
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel(guid = "chain-guid")

        viewModel.save("chain", listOf("entry", "exit"))
        viewModel.delete()
        gate.complete(Unit)

        // The save ends first, so that it writes nothing back after the delete, which closes the screen.
        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.deletes)
        assertEquals(listOf("chain-guid"), source.saves)
        assertTrue(source.stored.isEmpty())
    }

    @Test
    fun aNewChainHasNoneToDelete() {
        val viewModel = viewModel()

        viewModel.delete()

        assertNull(viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())
    }

    @Test
    fun aDeleteRefusedWhileTheMembersAreLookedUpLetsTheSaveEndAndTheScreenCloseOnIt() {
        source.stored["chain-guid"] = ProfileItem.create(EConfigType.PROXYCHAIN)
        source.selected = "chain-guid"
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel(guid = "chain-guid")

        viewModel.save("chain", listOf("entry", "exit"))
        viewModel.delete()
        // The delete waits for the save, then is refused; told so, the screen closes on what the save saved.
        assertNull(viewModel.outcome.value)
        gate.complete(Unit)
        assertEquals(refused(R.string.toast_action_not_allowed), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        assertEquals(EditorOutcome.Saved("chain-guid"), viewModel.outcome.value)
        assertEquals(listOf("chain-guid"), source.saves)
        assertTrue(source.deletes.isEmpty())
    }

    @Test
    fun aWriteTheStorageRefusesIsTold() {
        source.refuseWrites = true
        val viewModel = viewModel()

        viewModel.save("chain", listOf("entry", "exit"))

        assertEquals(refused(R.string.toast_failure), viewModel.outcome.value)
        assertTrue(source.stored.isEmpty())
    }

    @Test
    fun theChainAndTheNamesOfTheProfilesAreReadOffTheMainThreadBeforeTheScreenShowsThem() {
        source.names.add("custom", EConfigType.CUSTOM)
        source.names.add("outbound", EConfigType.CUSTOM_OUTBOUND)
        source.names.add("group", EConfigType.POLICYGROUP)
        source.names.add("chain", EConfigType.PROXYCHAIN)
        source.stored["chain-id"] = ProfileItem.create(EConfigType.PROXYCHAIN).apply {
            remarks = "chain"
            proxyChainProfiles = ProfileItem.proxyChainProfilesOf(listOf(" entry ", "", "a\\b,c"))
        }
        val gate = CompletableDeferred<Unit>()
        source.openGate = gate

        val viewModel = viewModel(guid = "chain-id")
        assertNull(viewModel.opened.value)
        gate.complete(Unit)

        // Its members trimmed, a blank one left out; the profiles a chain can hold, neither custom configurations nor
        // custom outbounds nor groups nor chains.
        assertEquals(ProxyChainOpening("chain", listOf("entry", "a\\b,c"), listOf("entry", "exit")), viewModel.opened.value)
    }

    @Test
    fun aNewChainOpensOnTwoBlankMembers() {
        assertEquals(ProxyChainOpening("", listOf("", ""), listOf("entry", "exit")), viewModel().opened.value)
    }

    @Test
    fun aStoredChainWithoutMembersOpensOnTwoBlankOnes() {
        source.stored["chain-id"] = ProfileItem.create(EConfigType.PROXYCHAIN).apply { remarks = "chain" }

        assertEquals(ProxyChainOpening("chain", listOf("", ""), listOf("entry", "exit")), viewModel(guid = "chain-id").opened.value)
    }
}
