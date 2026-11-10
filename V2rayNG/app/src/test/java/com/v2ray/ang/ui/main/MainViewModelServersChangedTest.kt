package com.v2ray.ang.ui.main

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServerAffiliationInfo
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.moveItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainViewModelServersChangedTest {

    /**
     * One subscription, whose server list [serverGuids] a change away from the screen replaces, and in which a move is
     * stored, recorded in [moves], at once or, held by [hold], once [release] lets it, or refused, see [refusals].
     */
    private class FakeSource : MainDataSource {
        val events = Channel<MainServiceEvent>(Channel.UNLIMITED)

        @Volatile
        var serverGuids = listOf("a", "b")

        /** Whether the list of every group, its id empty, is shown too, as the setting has it. */
        @Volatile
        var showAllGroup = false
        private var selectedSubscriptionId = ""

        /** The moves stored, as the group and the two guids. */
        val moves: MutableList<Triple<String, String, String>> = Collections.synchronizedList(mutableListOf())

        /** How many of the next moves the storage refuses. */
        val refusals = AtomicInteger(0)

        /** The request ids of the current-server tests asked for, in order. */
        val currentTests: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private val asked = AtomicInteger(0)
        private val held = ConcurrentHashMap<Int, CountDownLatch>()
        private val entered = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

        /** The move numbered [move], from 0, waits, as on a slow storage, until [release]. */
        fun hold(move: Int) {
            held[move] = CountDownLatch(1)
        }

        fun release(move: Int) = held.getValue(move).countDown()

        /** Told when the storage is asked for the move numbered [move]. */
        fun entered(move: Int): CompletableDeferred<Unit> = entered.computeIfAbsent(move) { CompletableDeferred() }

        override val mainServiceEvent: Flow<MainServiceEvent> = events.receiveAsFlow()
        override fun getSelectedSubscriptionId() = selectedSubscriptionId
        override fun setSelectedSubscriptionId(id: String) {
            selectedSubscriptionId = id
        }

        override fun getSelectServer(): String? = null
        override fun setSelectServer(guid: String) = Unit
        override fun getConfirmRemove() = false
        override fun getDoubleColumnDisplay() = false
        override fun isGroupAllDisplayEnabled() = showAllGroup
        override fun getString(resId: Int) = ""
        override fun getString(resId: Int, vararg formatArgs: Any) = ""
        override fun getStringArray(resId: Int) = emptyList<String>()
        override fun getSubscriptions() = buildList {
            if (showAllGroup) add(SubscriptionCache("", SubscriptionItem(remarks = "All")))
            add(SubscriptionCache(SUB, SubscriptionItem(remarks = "Sub")))
        }
        /** Shut by a test, the next reading of the list of every group waits on it after it read the group, see [readingHeld]. */
        @Volatile
        var holdNextAllRowsRead: CountDownLatch? = null
        val readingHeld = CompletableDeferred<Unit>()

        // The rows of the list of every group name the subscriptions, read here: after the group was read, before shown.
        override fun getSubscriptionItem(id: String): SubscriptionItem? {
            holdNextAllRowsRead?.let { gate ->
                holdNextAllRowsRead = null
                readingHeld.complete(Unit)
                gate.await(5, TimeUnit.SECONDS)
            }
            return null
        }
        /** Shut by a test, the next reading of a list waits on it, holding the group's load lock, see [listReadHeld]. */
        @Volatile
        var holdNextListRead: CountDownLatch? = null
        val listReadHeld = CompletableDeferred<Unit>()

        override fun getServerGuidList(groupId: String): List<String> {
            holdNextListRead?.let { gate ->
                holdNextListRead = null
                listReadHeld.complete(Unit)
                gate.await(5, TimeUnit.SECONDS)
            }
            return serverGuids
        }
        override fun decodeServerConfig(guid: String): ProfileItem? =
            ProfileItem.create(EConfigType.VLESS).apply {
                subscriptionId = SUB
                remarks = guid
                description = guid
            }

        override fun decodeAffiliationInfo(guid: String): ServerAffiliationInfo? = null

        override fun moveServer(groupId: String, fromGuid: String, toGuid: String): Boolean {
            val move = asked.getAndIncrement()
            entered(move).complete(Unit)
            held[move]?.await(5, TimeUnit.SECONDS)
            if (refusals.getAndUpdate { maxOf(0, it - 1) } > 0) return false
            val stored = serverGuids.toMutableList()
            stored.moveItem(stored.indexOf(fromGuid), stored.indexOf(toGuid))
            serverGuids = stored
            moves += Triple(groupId, fromGuid, toGuid)
            return true
        }

        override fun removeServer(guid: String) = true
        override fun removeAllServer() = 0
        override fun removeInvalidServerByGuid(guid: String) = 0
        override fun removeInvalidServersInGroup(groupId: String) = 0
        override fun clearAllTestDelayResults(guids: List<String>) = Unit
        override fun sortByTestResultsForSub(subId: String) = Unit
        override fun getSubsList() = listOf(SUB)
        override suspend fun importBatchConfig(server: String?, subscriptionId: String, updateUI: Boolean) = 0 to 0
        override fun updateConfigViaSubAll() = SubscriptionUpdateResult()
        override fun updateConfigViaSub(subscriptionCache: SubscriptionCache) = SubscriptionUpdateResult()
        override fun shareNonCustomConfigsToClipboard(guids: List<String>) = 0
        override fun share2QRCode(guid: String): Bitmap? = null
        override fun share2Clipboard(guid: String) = false
        override fun sendMsg2Service(msgId: Int, content: String) = Unit
        override fun queryServiceState() = Unit
        override fun sendMsg2TestService(msg: TestServiceMessage, requestId: String?) = Unit
        override fun cancelAllPing() = Unit
        override fun testCurrentServerRealPing(requestId: String) {
            currentTests += requestId
        }
        override fun syncSubscriptions() = Unit
        override fun initAssets() = Unit
        override fun close() = Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeEach
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterEach
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun serversChangedAwayFromTheScreenReloadTheirTab() = runBlocking {
        val source = FakeSource()
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b")

        // An update on the subscription page or in the subscription service, or an import, replaced the configs
        source.serverGuids = listOf("a", "c", "d")
        source.events.send(MainServiceEvent.ServersChanged)

        viewModel.awaitServers("a", "c", "d")
    }

    @Test
    fun theCurrentServerTestTakesTheTwoRepliesOfItsOwnRequestOnly() = runBlocking {
        // PattNG: the service answers the delay and then the IP lookup of one request. A cancel before the delay ends the
        // test; a cancelled IP lookup keeps the delay shown; a reply to an older request changes nothing.
        val source = FakeSource()
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b")
        source.events.send(MainServiceEvent.StateRunning)
        viewModel.awaitStatus(MainStatus.Connected)

        viewModel.testCurrentServerRealPing()
        val cancelled = source.currentTests.last()
        assertEquals(MainStatus.Testing, viewModel.uiState.value.status)
        source.events.send(MainServiceEvent.MeasureDelayCancelled(cancelled))
        viewModel.awaitStatus(MainStatus.Connected)
        assertFalse(viewModel.uiState.value.isTesting)

        viewModel.testCurrentServerRealPing()
        val answered = source.currentTests.last()
        assertNotEquals(cancelled, answered)
        val measured = ConnectionTestResult(delayMillis = 120)
        source.events.send(MainServiceEvent.MeasureDelayResult(measured, answered))
        viewModel.awaitStatus(MainStatus.ConnectionTest(measured))
        assertFalse(viewModel.uiState.value.isTesting)
        val located = measured.copy(country = "DE", ipAddress = "203.0.113.7")
        source.events.send(MainServiceEvent.MeasureDelayResult(located, answered))
        viewModel.awaitStatus(MainStatus.ConnectionTest(located))

        // Late: the cancelled request's reply and cancel, then this one's IP lookup cancelled.
        source.events.send(MainServiceEvent.MeasureDelayResult(ConnectionTestResult(delayMillis = 999), cancelled))
        source.events.send(MainServiceEvent.MeasureDelayCancelled(cancelled))
        source.events.send(MainServiceEvent.MeasureDelayCancelled(answered))
        delay(200)
        assertEquals(MainStatus.ConnectionTest(located), viewModel.uiState.value.status)
        assertFalse(viewModel.uiState.value.isTesting)
    }

    @Test
    fun aMoveIsShownAtOnceAndStoredByTheTwoGuids() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")

        viewModel.onAction(MainAction.MoveServer(SUB, "c", "a"))
        assertEquals(listOf("c", "a", "b"), viewModel.shownServers())
        // A second move, stored after the first.
        withTimeout(5_000) { viewModel.moveServer(SUB, "a", "b")!!.join() }

        assertEquals(listOf("c", "b", "a"), viewModel.shownServers())
        assertEquals(listOf(Triple(SUB, "c", "a"), Triple(SUB, "a", "b")), source.moves.toList())
        assertEquals(listOf("c", "b", "a"), source.serverGuids)
    }

    @Test
    fun aMoveOfAProfileNotShownOrInTheListOfEveryGroupShowsAndStoresNothing() = runBlocking {
        val source = FakeSource()
        source.showAllGroup = true
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b")
        viewModel.awaitServers("a", "b", groupId = "")

        assertNull(viewModel.moveServer(SUB, "gone", "a"))
        assertNull(viewModel.moveServer(SUB, "", "a"))
        assertNull(viewModel.moveServer(SUB, "a", "a"))
        // The list of every group, shown as the others are, is no stored list to move in.
        assertNull(viewModel.moveServer("", "b", "a"))

        assertEquals(listOf("a", "b"), viewModel.shownServers())
        assertEquals(listOf("a", "b"), viewModel.shownServers(groupId = ""))
        assertEquals(emptyList<Triple<String, String, String>>(), source.moves.toList())
    }

    @Test
    fun aMoveWhileTheListIsFilteredKeepsTheProfilesFilteredOutInTheGroup() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        // As while a search ends: the list shown is still the filtered one.
        viewModel.filterConfig("^(a|c)$")
        viewModel.awaitServers("a", "c")

        withTimeout(5_000) { viewModel.moveServer(SUB, "c", "a")!!.join() }
        viewModel.filterConfig("")

        // The group shows its whole list again, in the order stored, which the move did not cut down to the shown one.
        viewModel.awaitServers("c", "a", "b")
        assertEquals(listOf("c", "a", "b"), source.serverGuids)
    }

    @Test
    fun theMovesOfADragAreStoredInTurnEvenWhenTheScreenClosesBeforeTheyAre() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val owner = ViewModelStore()
        val viewModel = ViewModelProvider(owner, MainViewModel.Factory(mock<Application>(), source))[MainViewModel::class.java]
        viewModel.awaitServers("a", "b", "c")
        source.hold(0)

        viewModel.moveServer(SUB, "c", "a")
        withTimeout(5_000) { source.entered(0).await() }
        val last = viewModel.moveServer(SUB, "b", "c")!!
        // The screen closes while the first move is being stored and the second waits for it.
        owner.clear()
        source.release(0)
        withTimeout(5_000) { last.join() }

        assertEquals(listOf(Triple(SUB, "c", "a"), Triple(SUB, "b", "c")), source.moves.toList())
        assertEquals(listOf("b", "c", "a"), source.serverGuids)
    }

    @Test
    fun aReloadWhileAMoveIsBeingStoredReadsTheGroupOnceTheMoveIsStored() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        source.hold(0)

        val move = viewModel.moveServer(SUB, "c", "a")!!
        withTimeout(5_000) { source.entered(0).await() }
        // As on a change away from the screen: the groups read anew, their cached lists dropped first.
        val reload = viewModel.setupGroupTab(forceRefresh = true)
        delay(200)
        source.release(0)
        withTimeout(5_000) {
            move.join()
            reload.join()
        }

        // Read once the move was stored, not before it: the screen does not undo the move.
        assertEquals(listOf("c", "a", "b"), viewModel.shownServers())
    }

    @Test
    fun aMoveTheStorageRefusesIsToldAndTheGroupsShownAsStored() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        assertNull(viewModel.uiState.value.moveRefusal)
        source.refusals.set(1)

        withTimeout(5_000) { viewModel.moveServer(SUB, "c", "a")!!.join() }

        // Shown at once, then, refused, to be told, and the order as stored shown again.
        val refusal = checkNotNull(viewModel.uiState.value.moveRefusal)
        viewModel.awaitServers("a", "b", "c")
        viewModel.onAction(MainAction.MoveRefusalShown(refusal))
        assertNull(viewModel.uiState.value.moveRefusal)
    }

    @Test
    fun aRefusalSetAgainRightAfterTheLastWasToldIsToldToo() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        source.refusals.set(2)

        withTimeout(5_000) { viewModel.moveServer(SUB, "c", "a")!!.join() }
        val first = checkNotNull(viewModel.uiState.value.moveRefusal)
        viewModel.awaitServers("a", "b", "c")
        withTimeout(5_000) { viewModel.moveServer(SUB, "c", "a")!!.join() }
        val second = checkNotNull(viewModel.uiState.value.moveRefusal)

        // A number of its own, so the screen tells it, and the first one's late acknowledgement does not clear it.
        assertNotEquals(first, second)
        viewModel.onAction(MainAction.MoveRefusalShown(first))
        assertEquals(second, viewModel.uiState.value.moveRefusal)
        viewModel.onAction(MainAction.MoveRefusalShown(second))
        assertNull(viewModel.uiState.value.moveRefusal)
    }

    @Test
    fun aRefusedMoveIsToldOnceTheMovesQueuedBehindItAreStoredAndNotAgain() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        source.refusals.set(1)
        source.hold(0)
        source.hold(1)

        val refused = viewModel.moveServer(SUB, "c", "a")!!
        withTimeout(5_000) { source.entered(0).await() }
        val queued = viewModel.moveServer(SUB, "b", "c")!!
        source.release(0)
        withTimeout(5_000) { refused.join() }
        // Not yet: a move queued behind the refused one is still to be stored.
        assertNull(viewModel.uiState.value.moveRefusal)

        source.release(1)
        withTimeout(5_000) { queued.join() }
        val refusal = checkNotNull(viewModel.uiState.value.moveRefusal)
        // Shown anew once both were stored: the stored order, with the queued move in it.
        viewModel.awaitServers("a", "c", "b")
        assertEquals(listOf(Triple(SUB, "b", "c")), source.moves.toList())

        viewModel.onAction(MainAction.MoveRefusalShown(refusal))
        withTimeout(5_000) { viewModel.moveServer(SUB, "c", "a")!!.join() }
        // A later move the storage takes tells nothing.
        assertNull(viewModel.uiState.value.moveRefusal)
    }

    @Test
    fun aReadingTakenBeforeAMoveShownWasStoredIsHeldBackAndTheGroupShownAnewOnceItIs() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        val readGate = CountDownLatch(1)
        source.holdNextListRead = readGate
        // The stores of both moves wait in the storage until released: settled, they would have the group read anew,
        // and shown so, before the reading is looked at.
        source.hold(0)
        source.hold(1)

        // The groups are read anew, the reading held while it holds the group's load lock; two moves are shown meanwhile,
        // their stores waiting behind it, and an update stores another profile.
        val reload = viewModel.setupGroupTab(forceRefresh = true)
        withTimeout(5_000) { source.listReadHeld.await() }
        viewModel.moveServer(SUB, "c", "a")
        val last = viewModel.moveServer(SUB, "b", "c")!!
        assertEquals(listOf("b", "c", "a"), viewModel.shownServers())
        source.serverGuids = source.serverGuids + "d"
        readGate.countDown()
        withTimeout(5_000) { reload.join() }

        // That reading, taken before the moves were stored, would undo them on the screen: it is not shown.
        assertEquals(listOf("b", "c", "a"), viewModel.shownServers())
        source.release(0)
        source.release(1)
        withTimeout(5_000) { last.join() }
        // Shown anew once both moves are stored: with the profile the update stored.
        viewModel.awaitServers("b", "c", "a", "d")
        assertEquals(listOf("b", "c", "a", "d"), source.serverGuids)
    }

    @Test
    fun aReadingIsShownHeldBackOrTakenAnewAsItsFateSays() {
        fun fate(number: Int, lastShown: Int, settled: Int, shown: Int, unsettled: Int, asSearched: Boolean = true) =
            MainViewModel.readingFate(number, lastShown, settled, shown, unsettled, asSearched)
        assertEquals(MainViewModel.ReadingFate.SHOW, fate(number = 3, lastShown = 2, settled = 1, shown = 1, unsettled = 0))
        assertEquals(MainViewModel.ReadingFate.SHOW, fate(number = 2, lastShown = 2, settled = 0, shown = 0, unsettled = 0))
        // Older than the one shown: not shown over it, whatever its moves or search.
        assertEquals(MainViewModel.ReadingFate.OVERTAKEN, fate(number = 1, lastShown = 2, settled = 1, shown = 1, unsettled = 0))
        assertEquals(MainViewModel.ReadingFate.OVERTAKEN, fate(number = 1, lastShown = 2, settled = 0, shown = 1, unsettled = 1))
        assertEquals(MainViewModel.ReadingFate.OVERTAKEN, fate(number = 1, lastShown = 2, settled = 1, shown = 1, unsettled = 0, asSearched = false))
        // A move shown since it was taken: held back while the moves are being stored, taken anew once they are.
        assertEquals(MainViewModel.ReadingFate.HOLD_UNTIL_SETTLED, fate(number = 3, lastShown = 2, settled = 1, shown = 2, unsettled = 1))
        assertEquals(MainViewModel.ReadingFate.HOLD_UNTIL_SETTLED, fate(number = 3, lastShown = 2, settled = 1, shown = 2, unsettled = 1, asSearched = false))
        assertEquals(MainViewModel.ReadingFate.READ_AGAIN, fate(number = 3, lastShown = 2, settled = 1, shown = 2, unsettled = 0))
        // Filtered for a search changed since: taken anew.
        assertEquals(MainViewModel.ReadingFate.READ_AGAIN, fate(number = 3, lastShown = 2, settled = 1, shown = 1, unsettled = 0, asSearched = false))
    }

    @Test
    fun aReadingFilteredBeforeTheSearchChangedIsTakenAnewForTheNewOne() = runBlocking {
        val source = FakeSource()
        source.showAllGroup = true
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", groupId = "")
        source.holdNextAllRowsRead = CountDownLatch(1)
        val gate = source.holdNextAllRowsRead!!

        // A reading of the list of every group, filtered for no search, held before it is shown.
        viewModel.subscriptionIdChanged("")
        withTimeout(5_000) { source.readingHeld.await() }
        // The search changes, and the filter shows the cached list for it.
        viewModel.filterConfig("^a$")
        viewModel.awaitServers("a", groupId = "")
        gate.countDown()

        // The reading let go, filtered for the old search, is not shown as it is: it is taken and filtered anew.
        delay(500)
        assertEquals(listOf("a"), viewModel.shownServers(groupId = ""))
    }

    @Test
    fun aReadingOlderThanTheOneShownIsNotShownOverIt() = runBlocking {
        val source = FakeSource()
        source.showAllGroup = true
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", groupId = "")
        source.holdNextAllRowsRead = CountDownLatch(1)
        val gate = source.holdNextAllRowsRead!!

        // A reading of the list of every group, held after it read [a, b], before it is shown.
        viewModel.subscriptionIdChanged("")
        withTimeout(5_000) { source.readingHeld.await() }
        // A newer one, after an update stored c, is shown first.
        source.serverGuids = listOf("a", "b", "c")
        viewModel.reloadAllGroups(listOf(""))
        viewModel.awaitServers("a", "b", "c", groupId = "")
        gate.countDown()

        // The older reading, let go, is not shown over the newer one.
        delay(300)
        assertEquals(listOf("a", "b", "c"), viewModel.shownServers(groupId = ""))
    }

    @Test
    fun aRefusalWithAReadingHeldBackIsToldAndTheGroupsShownAsStored() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        source.refusals.set(1)
        val readGate = CountDownLatch(1)
        source.holdNextListRead = readGate
        // As above: the stores wait until released, so that nothing settles them before the reading is looked at.
        source.hold(0)
        source.hold(1)

        // A reading held while it holds the group's load lock, two moves queued behind it, the first to be refused.
        val reload = viewModel.setupGroupTab(forceRefresh = true)
        withTimeout(5_000) { source.listReadHeld.await() }
        viewModel.moveServer(SUB, "c", "a")
        val last = viewModel.moveServer(SUB, "b", "c")!!
        source.serverGuids = source.serverGuids + "d"
        readGate.countDown()
        withTimeout(5_000) { reload.join() }
        // The reading, taken before the moves were settled, is held back.
        assertEquals(listOf("b", "c", "a"), viewModel.shownServers())
        source.release(0)
        source.release(1)
        withTimeout(5_000) { last.join() }

        // Told once both are settled, and the groups shown as stored: the second move made, the first one not.
        assertNotEquals(null, viewModel.uiState.value.moveRefusal)
        viewModel.awaitServers("a", "c", "b", "d")
        assertEquals(listOf("a", "c", "b", "d"), source.serverGuids)
    }

    @Test
    fun theFilterShowingTheCachedListWhileAMoveIsBeingStoredIsHeldBackUntilItIs() = runBlocking {
        val source = FakeSource()
        source.serverGuids = listOf("a", "b", "c")
        val viewModel = MainViewModel(mock<Application>(), source)
        viewModel.awaitServers("a", "b", "c")
        source.hold(0)

        val move = viewModel.moveServer(SUB, "c", "a")!!
        withTimeout(5_000) { source.entered(0).await() }
        // The filter shows the cached lists after a pause; the cached list of the group is from before the move. Shown,
        // it would put [a, c] up, the move undone.
        viewModel.filterConfig("^(a|c)$")
        delay(1_000)
        assertEquals(listOf("c", "a", "b"), viewModel.shownServers())

        source.release(0)
        withTimeout(5_000) { move.join() }
        // Shown anew once the move is stored, filtered: the move kept, b filtered out.
        viewModel.awaitServers("c", "a")
        assertEquals(listOf("c", "a", "b"), source.serverGuids)
    }

    private fun MainViewModel.shownServers(groupId: String = SUB) = serverGroupState(groupId).value.servers.map(ServersCache::guid)

    private suspend fun MainViewModel.awaitStatus(status: MainStatus) {
        withTimeout(5_000) { uiState.first { it.status == status } }
    }

    private suspend fun MainViewModel.awaitServers(vararg guids: String, groupId: String = SUB) {
        withTimeout(5_000) {
            serverGroupState(groupId).first { state -> state.servers.map(ServersCache::guid) == guids.toList() }
        }
    }

    private companion object {
        const val SUB = "sub"
    }
}
