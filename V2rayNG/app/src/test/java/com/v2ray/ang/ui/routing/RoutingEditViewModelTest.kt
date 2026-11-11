package com.v2ray.ang.ui.routing

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ByName
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RulesetItem
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

class RoutingEditViewModelTest {

    private class FakeSource : RoutingEditSource {
        val names = FakeProfileNames()

        /** The position each save was asked to store at, with a copy of the rule as it was then. */
        val saves = mutableListOf<Pair<Int, RulesetItem>>()
        /** The position and the id each delete was asked for. */
        val deletes = mutableListOf<Pair<Int, String>>()

        /** When set, the storage refuses every write: nothing is written. */
        var refuseWrites = false

        /** The rule the editor opens on, and the position and the kept id each read of it was asked for. */
        var rule: RulesetItem? = null
        val ruleReads = mutableListOf<Pair<Int, String?>>()

        /** Whether a rule can match the app a connection comes from. */
        var canUseProcess = false

        /** When set, the reads the screen opens on wait for it, as reads off the main thread take their time. */
        var openGate: CompletableDeferred<Unit>? = null

        override suspend fun <T> withProfileNames(takes: (ProfileItem) -> Boolean, check: (find: (String) -> ByName<ProfileItem>) -> T): T =
            names.withProfileNames(takes, check)

        // A fresh copy, as a real read gives.
        override suspend fun loadRule(position: Int, reopenedId: String?): RulesetItem? {
            ruleReads += position to reopenedId
            openGate?.await()
            return rule?.copy()
        }

        // As read, custom profiles left out by the source.
        override suspend fun profileNames(): List<String> {
            openGate?.await()
            return names.profiles.map { it.remarks }
        }

        override suspend fun canUseProcessRouting(): Boolean {
            openGate?.await()
            return canUseProcess
        }

        override suspend fun saveRule(position: Int, rule: RulesetItem): Boolean {
            if (refuseWrites) return false
            saves += position to rule.copy()
            return true
        }

        override suspend fun deleteRule(position: Int, id: String): Boolean {
            if (refuseWrites) return false
            deletes += position to id
            return true
        }
    }

    private val source = FakeSource()

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

    /** The editor opened at [position] on [initial], as the source reads it, by [reopenedId] when it kept one. */
    private fun viewModel(position: Int = -1, initial: RulesetItem? = null, reopenedId: String? = null): RoutingEditViewModel {
        source.rule = initial
        return RoutingEditViewModel(mock<Application>(), source, position, reopenedId)
    }

    private fun rule(tag: String = "exit", enabled: Boolean = true, id: String = "") =
        RulesetItem(id = id, remarks = "rule", outboundTag = tag, enabled = enabled)

    @Test
    fun aRuleWithoutRemarksSavesNothingAndTellsNothing() {
        val viewModel = viewModel()

        viewModel.save(rule().apply { remarks = "" })

        assertNull(viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun anEnabledRuleSendingToAProfileNoneOrSeveralHaveIsToldByItsName() {
        source.names.add("twice")
        source.names.add("twice")
        val viewModel = viewModel()

        viewModel.save(rule(tag = " gone "))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf("gone")), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.save(rule(tag = "twice"))
        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_duplicate, listOf("twice")), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
    }

    @Test
    fun theProfileIsFoundAmongThoseARuleCanSendTo() {
        // A custom profile is not a routing target, so a rule does not find it by its name.
        source.names.add("custom", EConfigType.CUSTOM)
        val viewModel = viewModel()

        viewModel.save(rule(tag = "custom"))

        assertEquals(EditorOutcome.Refused(R.string.toast_profile_name_not_found, listOf("custom")), viewModel.outcome.value)
    }

    @Test
    fun aDisabledRuleAndARuleToABuiltInOutboundAreSavedWithoutALookup() {
        val viewModel = viewModel(position = 2)

        viewModel.save(rule(tag = "gone", enabled = false))
        viewModel.onOutcomeHandled()
        viewModel.save(rule(tag = AppConfig.TAG_DIRECT))

        assertEquals(0, source.names.lookups)
        assertEquals(listOf(2, 2), source.saves.map { it.first })
    }

    @Test
    fun aNewRuleGetsOneIdThatEverySaveOfItKeeps() {
        val viewModel = viewModel()

        viewModel.save(rule())
        val (position, stored) = source.saves.single()
        assertEquals(-1, position)
        assertTrue(stored.id.isNotEmpty())
        assertEquals(EditorOutcome.Saved(stored.id), viewModel.outcome.value)

        // As when the screen, recreated before it closed, is saved again: the rule keeps its id, by which the rule it stored
        // first is found again and written over.
        viewModel.onOutcomeHandled()
        viewModel.save(rule())

        assertEquals(-1 to stored.id, source.saves[1].first to source.saves[1].second.id)
        assertEquals(EditorOutcome.Saved(stored.id), viewModel.outcome.value)
    }

    @Test
    fun aStoredRuleIsSavedWithTheIdItIsStoredWith() {
        val viewModel = viewModel(position = 3, initial = RulesetItem(id = "rule-id"))
        assertEquals("rule-id", viewModel.ruleId)

        viewModel.save(rule(id = "rule-id"))
        viewModel.onOutcomeHandled()
        viewModel.save(rule())
        // A screen recreated on what another rule left at the position builds on that one: the view model's id still wins.
        viewModel.onOutcomeHandled()
        viewModel.save(rule(id = "another"))

        assertEquals(listOf(3 to "rule-id", 3 to "rule-id", 3 to "rule-id"), source.saves.map { it.first to it.second.id })

        // One from before rules had ids keeps none, and goes by its position.
        val legacy = viewModel(position = 4, initial = RulesetItem())
        assertEquals("", legacy.ruleId)
        legacy.save(rule())
        assertEquals(4 to "", source.saves[3].first to source.saves[3].second.id)
    }

    @Test
    fun aRuleGoneByTheTimeTheEditorCameBackIsSavedAsANewOne() {
        val viewModel = viewModel(position = 3, initial = null)

        viewModel.save(rule())

        val (position, stored) = source.saves.single()
        assertEquals(3, position)
        // An id of its own, by which no stored rule is found: the rule is stored first, as a new one.
        assertTrue(stored.id.isNotEmpty())
        assertEquals(stored.id, viewModel.ruleId)
    }

    @Test
    fun theEditorOpensOnTheRuleAtItsPositionOrOnTheOneOfTheIdItKept() {
        val rules = listOf(RulesetItem(id = "a"), RulesetItem(id = "b"))

        assertEquals("b", openedRule(rules, 1, null)?.id)
        assertNull(openedRule(rules, 2, null))
        assertNull(openedRule(rules, -1, null))
        assertNull(openedRule(null, 0, null))
        // Back after its process was gone, the editor finds its rule by the id it kept, wherever the rule stands now.
        assertEquals("b", openedRule(rules.reversed(), 1, "b")?.id)
        assertNull(openedRule(rules, 1, "gone"))
    }

    @Test
    fun aRuleIsFoundAgainByItsIdWhereverTheListHasMovedIt() {
        val rules = listOf(RulesetItem(id = "a"), RulesetItem(id = "b"), RulesetItem(id = "c"))

        assertEquals(1, storedAt(rules, "b", 1))
        // Moved, or with a rule deleted before it, while the editor was open.
        assertEquals(0, storedAt(listOf(rules[1], rules[0], rules[2]), "b", 1))
        assertEquals(0, storedAt(rules.drop(1), "b", 1))
        // Gone, or new: no rule has the id.
        assertEquals(-1, storedAt(rules, "gone", 1))
        assertEquals(-1, storedAt(null, "b", 1))
        // A rule without an id goes by its position, while the list reaches that far.
        assertEquals(2, storedAt(rules, "", 2))
        assertEquals(-1, storedAt(rules, "", 3))
        assertEquals(-1, storedAt(rules, "", -1))
    }

    @Test
    fun aDeleteDeletesTheRuleAtItsPositionAndANewRuleHasNoneToDelete() {
        val stored = viewModel(position = 3, initial = RulesetItem(id = "rule-id"))
        stored.delete()
        assertEquals(listOf(3 to "rule-id"), source.deletes)
        assertEquals(EditorOutcome.Deleted, stored.outcome.value)

        val new = viewModel()
        new.delete()
        assertEquals(listOf(3 to "rule-id"), source.deletes)
        assertNull(new.outcome.value)
    }

    @Test
    fun theScreenMayNotCloseWhileTheProfileIsLookedUp() {
        val gate = CompletableDeferred<Unit>()
        source.names.gate = gate
        val viewModel = viewModel()

        viewModel.save(rule())
        assertFalse(viewModel.leaveScreen())
        gate.complete(Unit)

        assertEquals(EditorOutcome.Saved(source.saves.single().second.id), viewModel.outcome.value)
        assertFalse(viewModel.leaveScreen())
        viewModel.onOutcomeHandled()
        assertTrue(viewModel.leaveScreen())
    }

    @Test
    fun aSaveOrADeleteTheStorageRefusesIsTold() {
        source.refuseWrites = true
        val viewModel = viewModel(position = 3, initial = rule(id = "rule-id"))

        viewModel.save(rule())
        assertEquals(EditorOutcome.Refused(R.string.toast_failure), viewModel.outcome.value)

        viewModel.onOutcomeHandled()
        viewModel.delete()
        assertEquals(EditorOutcome.Refused(R.string.toast_failure), viewModel.outcome.value)
        assertTrue(source.saves.isEmpty())
        assertTrue(source.deletes.isEmpty())
    }

    @Test
    fun aRuleGoneByTheTimeTheEditorOpenedHasNoneToDelete() {
        val viewModel = viewModel(position = 3, initial = null)

        viewModel.delete()

        assertTrue(source.deletes.isEmpty())
        assertNull(viewModel.outcome.value)
    }

    @Test
    fun theRuleAndWhatTheScreenOffersAreReadOffTheMainThreadBeforeTheScreenShowsThem() {
        source.names.add(AppConfig.TAG_DIRECT)
        source.canUseProcess = true
        val gate = CompletableDeferred<Unit>()
        source.openGate = gate

        val viewModel = viewModel(position = 3, initial = RulesetItem(id = "rule-id", remarks = "rule"), reopenedId = "rule-id")
        assertNull(viewModel.opened.value)
        assertNull(viewModel.ruleId)
        gate.complete(Unit)

        assertEquals(listOf(3 to "rule-id"), source.ruleReads)
        // The names of the profiles, but one named as a built-in outbound, which the field offers itself.
        assertEquals(
            RuleOpening(RulesetItem(id = "rule-id", remarks = "rule"), listOf("exit"), canUseProcess = true),
            viewModel.opened.value,
        )
        assertEquals("rule-id", viewModel.ruleId)
    }

    @Test
    fun nothingIsSavedOrDeletedBeforeTheRuleIsRead() {
        val gate = CompletableDeferred<Unit>()
        source.openGate = gate
        val viewModel = viewModel(position = 3, initial = RulesetItem(id = "rule-id"))

        viewModel.save(rule(tag = "gone"))
        viewModel.delete()

        // Not even a profile is looked up, whose name not found would be told.
        assertEquals(0, source.names.lookups)
        assertTrue(source.saves.isEmpty())
        assertTrue(source.deletes.isEmpty())
        assertNull(viewModel.outcome.value)
        gate.complete(Unit)
        assertEquals("rule-id", viewModel.ruleId)
    }
}
