package com.v2ray.ang.ui.server

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
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

class ServerGroupViewModelTest {

    private val source = FakeProfileEditorSource()

    /** A type whose group tests its members, so that its fallback is used. */
    private val tested = BalancerStrategyType.RANDOM.policyGroupTypeValue.toInt()

    private val edit = PolicyGroupEdit(
        remarks = " group ",
        filter = " us ",
        type = tested,
        typeLabel = "Random",
        subscriptionId = "picked",
        subscriptionLabel = "Picked",
        testOutbounds = true,
        fallbackTag = " exit ",
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.names.add("exit")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(guid: String = "", subscriptionId: String? = null) =
        ServerGroupViewModel(mock<Application>(), source, guid, subscriptionId)

    @Test
    fun blankRemarksSaveNothingAndTellNothing() {
        val viewModel = viewModel()

        viewModel.save(edit.copy(remarks = " "))

        assertNull(viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun aFallbackNoProfileHasOrSeveralHaveIsToldByItsName() {
        source.names.add("twice")
        source.names.add("twice")
        val viewModel = viewModel()

        viewModel.save(edit.copy(fallbackTag = " gone "))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf("gone")), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "twice"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_duplicate, listOf("twice")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun theFallbackIsFoundAmongTheProfilesAGroupCanFallBackTo() {
        // A group cannot fall back to a group: a name only a group has is told as a group's.
        source.names.add("other group", EConfigType.POLICYGROUP)
        val viewModel = viewModel()

        viewModel.save(edit.copy(fallbackTag = "other group"))

        assertEquals(EditorOutcome.Refused(R.string.toast_profile_group_not_fallback, listOf("other group")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())

        // A custom configuration cannot be one either, and is told as such.
        source.names.add("custom", EConfigType.CUSTOM)
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "custom"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_custom_not_fallback, listOf("custom")), viewModel.outcome.value)

        // A profile that can be the fallback is found by the name, beside the group that has it too.
        source.names.add("other group")
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "other group"))
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertEquals("other group", source.stored.getValue("guid-1").policyGroupFallbackTag)
    }

    @Test
    fun aFallbackIsLookedUpOnlyWhereItIsUsed() {
        val viewModel = viewModel()

        // Not when the group does not test its members, nor when its type cannot, nor for a built-in outbound.
        viewModel.save(edit.copy(fallbackTag = "gone", testOutbounds = false))
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "gone", type = BalancerStrategyType.LEAST_LOAD.policyGroupTypeValue.toInt()))
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(fallbackTag = "direct"))

        assertEquals(0, source.names.lookups)
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertEquals(3, source.saves.size)
    }

    @Test
    fun aNewGroupIsStoredOnceAndALaterSaveWritesOverIt() {
        val viewModel = viewModel(subscriptionId = "sub")

        viewModel.save(edit)
        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        val stored = source.stored.getValue("guid-1")
        assertEquals(EConfigType.POLICYGROUP, stored.configType)
        assertEquals("group", stored.remarks)
        assertEquals("us", stored.policyGroupFilter)
        assertEquals(tested.toString(), stored.policyGroupType)
        assertEquals("picked", stored.policyGroupSubscriptionId)
        assertEquals(true, stored.policyGroupTestOutbounds)
        assertEquals("exit", stored.policyGroupFallbackTag)
        assertEquals("sub", stored.subscriptionId)
        assertEquals("Random - Picked - us", stored.description)

        // As when the screen, recreated before it closed, is saved again: the group it stored is written over.
        viewModel.onOutcomeHandled()
        viewModel.save(edit.copy(remarks = "group 2", fallbackTag = " "))

        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertEquals(listOf("guid-1", "guid-1"), source.saves)
        assertEquals("group 2", source.stored.getValue("guid-1").remarks)
        assertNull(source.stored.getValue("guid-1").policyGroupFallbackTag)
    }

    @Test
    fun aStoredGroupIsWrittenOverAndKeepsItsSubscription() {
        source.stored["group-guid"] = ProfileItem.create(EConfigType.POLICYGROUP).apply { subscriptionId = "own" }
        val viewModel = viewModel(guid = "group-guid", subscriptionId = "sub")

        viewModel.save(edit)

        assertEquals(EditorOutcome.Saved("group-guid"), viewModel.outcome.value)
        assertEquals(listOf("group-guid"), source.saves)
        assertEquals("own", source.stored.getValue("group-guid").subscriptionId)
    }

    @Test
    fun theScreenMayNotCloseWhileTheFallbackIsLookedUp() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save(edit)
        assertFalse(viewModel.leaveScreen())
        gate.complete(Unit)

        assertEquals(EditorOutcome.Saved("guid-1"), viewModel.outcome.value)
        assertFalse(viewModel.leaveScreen())
        viewModel.onOutcomeHandled()
        assertTrue(viewModel.leaveScreen())
    }

    @Test
    fun theSubscriptionsAreOfferedAllFirstUnderLabelsNoTwoOfWhichAreAlike() {
        val offered = policyGroupSubscriptions(
            all = "All",
            subscriptions = listOf("a1" to "Iran", "b2" to "Iran", "c3" to "All", "d4" to "Work"),
            numbered = { name, number -> "$name ($number)" },
        )

        assertEquals(
            listOf(
                PolicyGroupSubscription("", "All"),
                PolicyGroupSubscription("a1", "Iran"),
                PolicyGroupSubscription("b2", "Iran (2)"),
                PolicyGroupSubscription("c3", "All (2)"),
                PolicyGroupSubscription("d4", "Work"),
            ),
            offered,
        )
        // So each label the list hands back leads to one subscription.
        assertEquals(offered.size, offered.map { it.label }.toSet().size)
        assertEquals(listOf(PolicyGroupSubscription("", "All")), policyGroupSubscriptions("All", emptyList()) { name, number -> "$name ($number)" })
    }

    @Test
    fun aSubscriptionIsPickedByItsKeyAndAGoneOneGivesAll() {
        val offered = policyGroupSubscriptions("All", listOf("a1" to "Iran", "b2" to "Iran")) { name, number -> "$name ($number)" }

        assertEquals(PolicyGroupSubscription("b2", "Iran (2)"), offered.pick("b2"))
        assertEquals(PolicyGroupSubscription("a1", "Iran"), offered.pick("a1"))
        assertEquals(PolicyGroupSubscription("", "All"), offered.pick(""))
        assertEquals(PolicyGroupSubscription("", "All"), offered.pick("gone"))
        assertEquals(PolicyGroupSubscription("", "All"), offered.pick(null))
    }

    @Test
    fun aDeleteDeletesTheGroupUnlessTheAppRunsOnIt() {
        source.stored["group-guid"] = ProfileItem.create(EConfigType.POLICYGROUP)
        source.selected = "group-guid"
        val viewModel = viewModel(guid = "group-guid")

        viewModel.delete()
        assertEquals(EditorOutcome.Refused(R.string.toast_action_not_allowed), viewModel.outcome.value)
        assertTrue(source.deletes.isEmpty())

        viewModel.onOutcomeHandled()
        source.selected = null
        viewModel.delete()
        assertEquals(EditorOutcome.Deleted, viewModel.outcome.value)
        assertEquals(listOf("group-guid"), source.deletes)
    }

    @Test
    fun aWriteTheStorageRefusesIsTold() {
        source.refuseWrites = true
        val viewModel = viewModel()

        viewModel.save(edit)

        assertEquals(EditorOutcome.Refused(R.string.toast_failure), viewModel.outcome.value)
        assertTrue(source.stored.isEmpty())
    }

    @Test
    fun theGroupTheSubscriptionsAndTheFallbacksAreReadOffTheMainThreadBeforeTheScreenShowsThem() {
        source.names.add("custom", EConfigType.CUSTOM)
        source.names.add("group", EConfigType.POLICYGROUP)
        source.names.add("chain", EConfigType.PROXYCHAIN)
        source.names.add("outbound", EConfigType.CUSTOM_OUTBOUND)
        source.names.add(AppConfig.TAG_DIRECT)
        source.subscriptions = listOf("sub-a" to "A", "sub-b" to "B")
        source.stored["group-id"] = ProfileItem.create(EConfigType.POLICYGROUP).apply {
            remarks = "group"
            policyGroupFilter = "us"
            policyGroupType = tested.toString()
            policyGroupSubscriptionId = "sub-b"
            policyGroupTestOutbounds = false
            policyGroupFallbackTag = "exit"
        }
        val gate = CompletableDeferred<Unit>()
        source.openGate = gate

        val viewModel = viewModel(guid = "group-id", subscriptionId = "sub-a")
        assertNull(viewModel.opened.value)
        gate.complete(Unit)

        assertEquals(
            PolicyGroupOpening(
                remarks = "group",
                filter = "us",
                type = tested,
                testOutbounds = false,
                fallbackTag = "exit",
                // The subscription the group was stored with, not the one the screen was opened in.
                pickedSubscription = "sub-b",
                subscriptions = listOf("sub-a" to "A", "sub-b" to "B"),
                // The built-in outbounds but the proxy, each once, then the profiles a group can fall back to, a chain and a
                // custom outbound as well.
                fallbackSuggestions = listOf(AppConfig.TAG_DIRECT, AppConfig.TAG_BLOCKED, "exit", "chain", "outbound"),
            ),
            viewModel.opened.value,
        )
    }

    @Test
    fun aNewGroupDrawsFromTheSubscriptionTheScreenWasOpenedInAndTestsItsMembers() {
        val opened = viewModel(subscriptionId = "sub-a").opened.value

        assertEquals("sub-a", opened?.pickedSubscription)
        assertEquals(true, opened?.testOutbounds)
        assertEquals(0, opened?.type)
        assertEquals("", opened?.remarks)
        assertEquals("", opened?.fallbackTag)
        // All, when the screen was opened in none.
        assertEquals("", viewModel().opened.value?.pickedSubscription)
    }

    @Test
    fun aGroupThatCannotTestItsMembersOpensAsTestingThem() {
        source.stored["group-id"] = ProfileItem.create(EConfigType.POLICYGROUP).apply {
            remarks = "group"
            policyGroupType = BalancerStrategyType.LEAST_LOAD.policyGroupTypeValue
            policyGroupTestOutbounds = false
        }

        assertEquals(true, viewModel(guid = "group-id").opened.value?.testOutbounds)
    }
}
