package com.v2ray.ang.ui.subscription

import android.app.Application
import com.v2ray.ang.R
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.base.EditorOutcome
import com.v2ray.ang.ui.server.FakeProfileNames
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

class SubEditViewModelTest {

    private class FakeSource : SubEditSource {
        val names = FakeProfileNames()
        val stored = linkedMapOf<String, SubscriptionItem>()

        /** The key each save was asked to store as, blank for a new subscription. */
        val saves = mutableListOf<String>()
        val deletes = mutableListOf<String>()
        var deleteGate: CompletableDeferred<Unit>? = null

        /** When set, the storage refuses every write and every delete: nothing is written. */
        var refuseWrites = false

        /** When set, the reads the screen opens on wait for it, as reads off the main thread take their time. */
        var openGate: CompletableDeferred<Unit>? = null

        /** Whether a delete is confirmed first, as the settings have it. */
        var confirmRemove = false

        override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
            names.withProfileNames(takes, check)

        // A fresh copy, as a real read gives.
        override suspend fun loadSubscription(subId: String): SubscriptionItem? {
            openGate?.await()
            return stored[subId]?.copy()
        }

        override suspend fun profileNames(excluded: Set<EConfigType>): List<String> {
            openGate?.await()
            return names.profiles.filter { it.configType !in excluded }.map { it.remarks }
        }

        override suspend fun confirmsRemove(): Boolean {
            openGate?.await()
            return confirmRemove
        }

        override suspend fun saveSubscription(subId: String, edit: (SubscriptionItem) -> Unit): String? {
            saves += subId
            if (refuseWrites) return null
            val key = subId.ifBlank { "sub-${stored.size + 1}" }
            stored[key] = (stored[key] ?: SubscriptionItem()).also(edit)
            return key
        }

        override suspend fun deleteSubscription(subId: String): Boolean {
            deleteGate?.await()
            if (refuseWrites) return false
            deletes += subId
            stored.remove(subId)
            return true
        }
    }

    private val source = FakeSource()

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.names.add("entry")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(subId: String = "") = SubEditViewModel(mock<Application>(), source, subId)

    private fun edits(remarks: String = "sub", prev: String? = null, next: String? = null): (SubscriptionItem) -> Unit = {
        it.remarks = remarks
        it.prevProfile = prev
        it.nextProfile = next
    }

    @Test
    fun aNeighborNoProfileHasSeveralHaveOrWithoutAServerIsToldByItsName() {
        source.names.add("twice")
        source.names.add("twice")
        source.names.add("bare", server = null)
        val viewModel = viewModel()

        viewModel.save(edits(next = " gone "))
        assertEquals(EditorOutcome.Refused(CoreConfigContext.UnresolvedName.Reason.NOT_FOUND.message, listOf("gone")), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save(edits(prev = "twice"))
        assertEquals(EditorOutcome.Refused(CoreConfigContext.UnresolvedName.Reason.SEVERAL.message, listOf("twice")), viewModel.outcome.value)

        // The next profile is looked up first, as the chain finds it first.
        viewModel.onOutcomeHandled()
        viewModel.save(edits(prev = "twice", next = "bare"))
        assertEquals(EditorOutcome.Refused(CoreConfigContext.UnresolvedName.Reason.NO_SERVER.message, listOf("bare")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aNeighborOnlyAGroupAChainOrACustomConfigurationHasIsToldByWhatItIs() {
        // None of them can be a hop of the chain around the subscription's profiles.
        source.names.add("group", EConfigType.POLICYGROUP)
        source.names.add("chain", EConfigType.PROXYCHAIN)
        source.names.add("custom", EConfigType.CUSTOM)
        val viewModel = viewModel()

        viewModel.save(edits(prev = "group"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_group_not_hop, listOf("group")), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save(edits(next = "chain"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_chain_not_hop, listOf("chain")), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save(edits(prev = "custom"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_custom_not_hop, listOf("custom")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun twoAetherNeighborsAreTold() {
        source.names.add("warp", EConfigType.AETHER, server = null)
        source.names.add("warp 2", EConfigType.AETHER, server = null)
        val viewModel = viewModel()

        viewModel.save(edits(prev = "warp", next = "warp 2"))

        assertEquals(EditorOutcome.Refused(R.string.aether_chain_one_profile), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aNewSubscriptionIsStoredOnceAndALaterSaveWritesOverIt() {
        val viewModel = viewModel()

        viewModel.save(edits(prev = "entry"))
        assertEquals(EditorOutcome.Saved("sub-1"), viewModel.outcome.value)
        assertEquals("entry", source.stored.getValue("sub-1").prevProfile)

        // As when the screen, recreated before it closed, is saved again: the subscription it stored is written over.
        viewModel.onOutcomeHandled()
        viewModel.save(edits(remarks = "sub 2"))

        assertEquals(EditorOutcome.Saved("sub-1"), viewModel.outcome.value)
        assertEquals(listOf("", "sub-1"), source.saves)
        assertEquals(setOf("sub-1"), source.stored.keys)
        assertEquals("sub 2", source.stored.getValue("sub-1").remarks)
    }

    @Test
    fun theEditsAreMadeOnTheSubscriptionAsStored() {
        source.stored["sub-id"] = SubscriptionItem(remarks = "old", lastUpdated = 42L)
        val viewModel = viewModel("sub-id")

        viewModel.save(edits(remarks = "new"))

        assertEquals(EditorOutcome.Saved("sub-id"), viewModel.outcome.value)
        assertEquals("new", source.stored.getValue("sub-id").remarks)
        assertEquals(42L, source.stored.getValue("sub-id").lastUpdated)
    }

    @Test
    fun aDeleteDeletesTheSubscriptionAndNoSaveFollowsIt() {
        source.stored["sub-id"] = SubscriptionItem(remarks = "old")
        val gate = CompletableDeferred<Unit>()
        source.deleteGate = gate
        val viewModel = viewModel("sub-id")

        viewModel.delete()
        viewModel.save(edits())
        gate.complete(Unit)

        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf("sub-id"), source.deletes)
        assertTrue(source.saves.isEmpty())
        assertTrue(source.stored.isEmpty())
    }

    @Test
    fun aNewSubscriptionHasNoneToDelete() {
        val viewModel = viewModel()

        viewModel.delete()

        assertTrue(source.deletes.isEmpty())
        assertNull(viewModel.outcome.value)
    }

    @Test
    fun theScreenMayNotCloseWhileTheNeighborsAreLookedUp() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save(edits(prev = "entry"))
        assertFalse(viewModel.leaveScreen())
        gate.complete(Unit)

        assertEquals(EditorOutcome.Saved("sub-1"), viewModel.outcome.value)
        assertFalse(viewModel.leaveScreen())
        viewModel.onOutcomeHandled()
        assertTrue(viewModel.leaveScreen())
    }

    @Test
    fun theSubscriptionAndTheNamesOfTheProfilesAreReadOffTheMainThreadBeforeTheScreenShowsThem() {
        source.names.add("custom", EConfigType.CUSTOM)
        source.names.add("outbound", EConfigType.CUSTOM_OUTBOUND)
        source.names.add("group", EConfigType.POLICYGROUP)
        source.names.add("chain", EConfigType.PROXYCHAIN)
        val stored = SubscriptionItem(remarks = "old", prevProfile = "entry")
        source.stored["sub-id"] = stored
        source.confirmRemove = true
        val gate = CompletableDeferred<Unit>()
        source.openGate = gate

        val viewModel = viewModel("sub-id")
        assertNull(viewModel.opened.value)
        gate.complete(Unit)

        // The profiles a subscription can chain through, neither custom configurations nor custom outbounds nor groups nor
        // chains.
        assertEquals(SubEditOpening(stored, listOf("entry"), confirmRemove = true), viewModel.opened.value)
    }

    @Test
    fun aNewSubscriptionOpensAsANewOne() {
        val opened = viewModel().opened.value

        assertEquals("", opened?.subscription?.remarks)
        assertEquals("", opened?.subscription?.url)
        assertEquals(false, opened?.confirmRemove)
    }

    @Test
    fun aSaveTheStorageRefusesIsToldAndALaterSaveStoresTheSubscriptionAsNew() {
        source.refuseWrites = true
        val viewModel = viewModel()

        viewModel.save(edits())
        assertEquals(EditorOutcome.Refused(R.string.toast_failure), viewModel.outcome.value)
        assertTrue(source.stored.isEmpty())

        viewModel.onOutcomeHandled()
        source.refuseWrites = false
        viewModel.save(edits())

        assertEquals(EditorOutcome.Saved("sub-1"), viewModel.outcome.value)
        // Asked to store a new one both times: the refused save gave it no key.
        assertEquals(listOf("", ""), source.saves)
    }

    @Test
    fun aDeleteTheStorageRefusesIsToldAndTheScreenStays() {
        source.stored["sub-id"] = SubscriptionItem(remarks = "old")
        source.refuseWrites = true
        val viewModel = viewModel("sub-id")

        viewModel.delete()

        assertEquals(EditorOutcome.Refused(R.string.toast_failure), viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())
        assertEquals(setOf("sub-id"), source.stored.keys)
        viewModel.onOutcomeHandled()
        assertTrue(viewModel.leaveScreen())
    }
}
