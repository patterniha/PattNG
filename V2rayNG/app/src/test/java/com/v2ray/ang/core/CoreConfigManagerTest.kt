package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.CoreConfigContext
import com.v2ray.ang.dto.V2rayConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreConfigManagerTest {

    private fun socks(address: String, port: Int) = V2rayConfig.OutboundBean(
        protocol = "socks",
        settings = V2rayConfig.OutboundBean.OutSettingsBean(address = address, port = port),
    )

    @Test
    fun aProfileNamedExitNodeCannotRouteBesideAnAetherCore() {
        val warp = ProfileItem.create(EConfigType.AETHER).apply { remarks = "warp"; aetherProtocol = "wg" }
        val named = ProfileItem.create(EConfigType.VLESS).apply { remarks = AppConfig.TAG_EXIT_NODE; server = "1.2.3.4"; serverPort = "443" }
        val outbounds = listOf(
            CoreConfigContext.ResolvedOutbound(AppConfig.TAG_PROXY, warp, listOf(warp), CoreResolvedType.NORMAL),
            CoreConfigContext.ResolvedOutbound(AppConfig.TAG_EXIT_NODE, named, listOf(named), CoreResolvedType.NORMAL),
        )
        assertTrue(CoreConfigManager.takesExitNodeName(AetherDependency.of(outbounds), outbounds))
        // Without an Aether core the name is nobody's.
        assertFalse(CoreConfigManager.takesExitNodeName(AetherDependency.None, outbounds))
        assertFalse(CoreConfigManager.takesExitNodeName(AetherDependency.of(outbounds.take(1)), outbounds.take(1)))
    }

    @Test
    fun aConfigurationWhoseMainServerProducedNoOutboundIsRefusedRatherThanRunDirect() {
        fun config(vararg tags: String, balancer: String? = null) = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = ArrayList(tags.map { V2rayConfig.OutboundBean(tag = it, protocol = if (it == AppConfig.TAG_DIRECT) "freedom" else "vless") }),
            routing = V2rayConfig.RoutingBean(
                domainStrategy = "AsIs",
                rules = arrayListOf(),
                balancers = balancer?.let { listOf(V2rayConfig.RoutingBean.BalancerBean(tag = it, selector = listOf("${AppConfig.TAG_PROXY}-"))) },
            ),
        )
        // The main server's outbound was skipped: what is left would send everything out directly.
        assertTrue(CoreConfigManager.lacksMainOutbound(config(AppConfig.TAG_DIRECT, "block")))
        // Nor does the policy group of a routing target stand in for it.
        assertTrue(
            CoreConfigManager.lacksMainOutbound(
                config(AppConfig.TAG_DIRECT, "proxy-germany-1-a", balancer = "${AppConfig.TAG_BALANCER_PRE}-germany")
            )
        )
        // The main server's outbound, or the balancer of a policy group that is the main server.
        assertFalse(CoreConfigManager.lacksMainOutbound(config(AppConfig.TAG_PROXY, AppConfig.TAG_DIRECT, "block")))
        assertFalse(CoreConfigManager.lacksMainOutbound(config("proxy-proxy-1-a", AppConfig.TAG_DIRECT, balancer = AppConfig.TAG_BALANCER)))
    }

    @Test
    fun aRuleWhoseTargetWasNotBuiltIsNamedForTheSessionToBeRefused() {
        fun config(vararg tags: String, rules: List<V2rayConfig.RoutingBean.RulesBean>) = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = ArrayList(tags.map { V2rayConfig.OutboundBean(tag = it, protocol = "vless") }),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = ArrayList(rules)),
        )
        fun rule(outboundTag: String? = null, balancerTag: String? = null) =
            V2rayConfig.RoutingBean.RulesBean(domain = listOf("example.com"), outboundTag = outboundTag, balancerTag = balancerTag)

        // A rule to a profile that was built, to a built-in outbound, or to the balancer of a group has its way out.
        val built = listOf(rule("germany"), rule(AppConfig.TAG_DIRECT), rule(AppConfig.TAG_BLOCKED), rule(AppConfig.TAG_PROXY), rule(balancerTag = "balancer-group"))
        assertNull(CoreConfigManager.unbuiltRoutingTarget(config(AppConfig.TAG_PROXY, "germany", rules = built)))
        // One to a profile that was not built is named, the first of them.
        val unbuilt = listOf(rule(AppConfig.TAG_DIRECT), rule("routed chain"), rule("france"))
        assertEquals("routed chain", CoreConfigManager.unbuiltRoutingTarget(config(AppConfig.TAG_PROXY, rules = unbuilt)))
        // And so is the fallback of a policy group whose profile could not be built.
        val withFallback = config(AppConfig.TAG_PROXY, rules = emptyList()).apply {
            routing.balancers = listOf(
                V2rayConfig.RoutingBean.BalancerBean(
                    tag = AppConfig.TAG_BALANCER,
                    selector = listOf("${AppConfig.TAG_PROXY}-"),
                    fallbackTag = "unbuilt fallback",
                )
            )
        }
        assertEquals("unbuilt fallback", CoreConfigManager.unbuiltRoutingTarget(withFallback))
        assertNull(CoreConfigManager.unbuiltRoutingTarget(withFallback.apply { outbounds.add(V2rayConfig.OutboundBean(tag = "unbuilt fallback", protocol = "vless")) }))
    }

    @Test
    fun aProxyChainIsBuiltWholeOrNotAtAll() {
        val entry = ProfileItem.create(EConfigType.VLESS).apply { remarks = "entry" }
        val middle = ProfileItem.create(EConfigType.VLESS).apply { remarks = "middle" }
        val exit = ProfileItem.create(EConfigType.VLESS).apply { remarks = "exit" }
        val build = { profile: ProfileItem -> V2rayConfig.OutboundBean(tag = profile.remarks, protocol = "vless") }

        val whole = CoreConfigManager.chainHops(listOf(entry, middle, exit), build)
        assertEquals(listOf("entry", "middle", "exit"), whole?.map { it.second.tag })
        assertEquals(listOf(entry, middle, exit), whole?.map { it.first })
        // A hop that builds no outbound, wherever it stands, leaves no chain rather than a shorter one.
        for (broken in listOf(entry, middle, exit)) {
            assertNull(CoreConfigManager.chainHops(listOf(entry, middle, exit)) { profile -> build(profile).takeUnless { profile === broken } })
        }
        assertNull(CoreConfigManager.chainHops(emptyList(), build))
    }

    @Test
    fun aCoreThatDialsOutThroughAChainHopNeedsThatHop() {
        val warp = ProfileItem.create(EConfigType.AETHER).apply { remarks = "warp"; aetherProtocol = "wg" }
        val hop = ProfileItem.create(EConfigType.VLESS).apply { remarks = "hop"; server = "1.2.3.4"; serverPort = "443" }
        val chained = AetherCore.of(warp).copy(exit = AetherExit.through(listOf(hop)))
        val exitNode = V2rayConfig.OutboundBean(tag = AppConfig.TAG_EXIT_NODE, protocol = "vless")
        val toCore = socks(AppConfig.LOOPBACK, 10819).apply { tag = AppConfig.TAG_PROXY }

        assertTrue(CoreConfigManager.lacksChainHop(chained, listOf(toCore)))
        assertFalse(CoreConfigManager.lacksChainHop(chained, listOf(toCore, exitNode)))
        // A core that dials out plainly gets its own exit-node.
        assertFalse(CoreConfigManager.lacksChainHop(AetherCore.of(warp), listOf(toCore)))
    }

    @Test
    fun whatTheAetherCoreSendsOutLeavesThroughXray() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10808, protocol = "socks")),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819), V2rayConfig.OutboundBean(tag = "direct", protocol = "freedom")),
            routing = V2rayConfig.RoutingBean(
                domainStrategy = "AsIs",
                rules = arrayListOf(V2rayConfig.RoutingBean.RulesBean(domain = listOf("geosite:private"), outboundTag = "direct")),
            ),
        )
        val core = CoreConfigManager.routeAetherThroughXray(config, AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!, 10822)!!

        val inbound = config.inbounds.last()
        assertEquals(AppConfig.TAG_SECONDARY_SOCKS, inbound.tag)
        assertEquals(10822, inbound.port)
        assertEquals("mixed", inbound.protocol)
        assertEquals(AppConfig.LOOPBACK, inbound.listen)
        assertEquals(true, inbound.settings?.udp)
        assertNull(inbound.sniffing)

        val outbound = config.outbounds.last()
        assertEquals(AppConfig.TAG_EXIT_NODE, outbound.tag)
        assertEquals("freedom", outbound.protocol)
        assertNull(outbound.mux)

        // What comes in on that inbound goes out by that outbound, before any other rule is asked.
        assertEquals(2, config.routing.rules.size)
        assertEquals(listOf(AppConfig.TAG_SECONDARY_SOCKS), config.routing.rules.first().inboundTag)
        assertEquals(AppConfig.TAG_EXIT_NODE, config.routing.rules.first().outboundTag)

        assertEquals("socks5://127.0.0.1:10822", core.arguments.last())
    }

    @Test
    fun aConfigurationThatListensOnTheSecondarySocksPortItselfIsLeftAsItIs() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(V2rayConfig.InboundBean(tag = "socks", port = 10822, protocol = "socks")),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )

        assertNull(CoreConfigManager.routeAetherThroughXray(config, AetherCore.ofCommand("aether --bind 127.0.0.1:10819")!!, 10822))
        assertEquals(1, config.inbounds.size)
        assertEquals(1, config.outbounds.size)
        assertTrue(config.routing.rules.isEmpty())
    }

    @Test
    fun theExitNodeCarriesTheFinalMaskAndDialModeOfTheAetherProfile() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )
        val mask = """{"tcp": [{"type": "fragment"}]}"""
        val core = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!.copy(exit = AetherExit(mask, "code-1"))

        val routed = CoreConfigManager.routeAetherThroughXray(config, core, 10822)!!

        val exitNode = config.outbounds.last()
        assertEquals(AppConfig.TAG_EXIT_NODE, exitNode.tag)
        assertEquals("freedom", exitNode.protocol)
        assertEquals(JsonUtil.parseString(mask), exitNode.streamSettings?.finalmask)
        assertEquals("code-1", exitNode.streamSettings?.sockopt?.dialMode)
        // The outbound to the core stays as it was: what it reaches is on the loopback address.
        assertNull(config.outbounds.first().streamSettings?.sockopt?.dialMode)
        assertEquals(core.exit, routed.exit)
    }

    @Test
    fun aProfileChosenAsTheExitNodeIsTheExitNodeChangedInItsTagAlone() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )
        val node = V2rayConfig.OutboundBean(tag = AppConfig.TAG_PROXY, protocol = "trojan")
        val core = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!.copy(exit = AetherExit(node = "germany"))

        val routed = CoreConfigManager.routeAetherThroughXray(config, core, 10822) {
            if (it == "germany") ExitNodeOutbound.Built(node) else ExitNodeOutbound.NotFound
        }!!

        val exitNode = config.outbounds.last()
        assertEquals(AppConfig.TAG_EXIT_NODE, exitNode.tag)
        assertEquals("trojan", exitNode.protocol)
        assertEquals(AppConfig.TAG_EXIT_NODE, config.routing.rules.first().outboundTag)
        assertEquals(core.exit, routed.exit)
        assertEquals("socks5://127.0.0.1:10822", routed.arguments.last())
    }

    @Test
    fun aCoreWhoseExitNodeNameGivesNoOutboundDoesNotStartWithoutIt() {
        val gone = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!.copy(exit = AetherExit(node = "gone"))
        val toCore = socks(AppConfig.LOOPBACK, 10819).apply { tag = AppConfig.TAG_PROXY }
        val vless = V2rayConfig.OutboundBean(tag = AppConfig.TAG_PROXY, protocol = "vless")
        // Renamed or deleted, the name of several, or the one that gives no outbound: each is told as itself.
        for (problem in listOf(ExitNodeOutbound.NotFound, ExitNodeOutbound.SameName, ExitNodeOutbound.NoOutbound)) {
            assertEquals(problem, CoreConfigManager.exitNodeProblem(gone, listOf(toCore)) { problem })
        }
        assertNull(CoreConfigManager.exitNodeProblem(gone, listOf(toCore)) { ExitNodeOutbound.Built(vless) })
        // A chain's hop that is the exit-node already, or freedom, needs no node.
        val hop = V2rayConfig.OutboundBean(tag = AppConfig.TAG_EXIT_NODE, protocol = "vless")
        assertNull(CoreConfigManager.exitNodeProblem(gone, listOf(toCore, hop)) { ExitNodeOutbound.NotFound })
        assertNull(CoreConfigManager.exitNodeProblem(gone.copy(exit = AetherExit.PLAIN), listOf(toCore)) { error("no node to look up") })
        // A core with an upstream of its own dials out through no exit-node at all.
        val own = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --upstream socks5://127.0.0.1:1080")!!.copy(exit = AetherExit(node = "gone"))
        assertNull(CoreConfigManager.exitNodeProblem(own, listOf(toCore)) { ExitNodeOutbound.NotFound })

        // And the configuration is left as it was.
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(toCore),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )
        assertNull(CoreConfigManager.routeAetherThroughXray(config, gone, 10822) { ExitNodeOutbound.NotFound })
        assertTrue(config.inbounds.isEmpty())
        assertEquals(listOf(toCore), config.outbounds)
        assertTrue(config.routing.rules.isEmpty())
    }

    @Test
    fun theExitNodeIsLookedUpOnceForTheCheckAndTheOutboundAlike() {
        // What a lookup finds can change between two, as when an update renames the profile; the first answer counts.
        val answers = ArrayDeque(listOf<ExitNodeOutbound>(ExitNodeOutbound.Built(V2rayConfig.OutboundBean(tag = AppConfig.TAG_PROXY, protocol = "trojan")), ExitNodeOutbound.NotFound))
        var lookups = 0
        val node = CoreConfigManager.lookedUpOnce { lookups++; answers.removeFirst() }
        val core = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!.copy(exit = AetherExit(node = "germany"))
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )

        assertNull(CoreConfigManager.exitNodeProblem(core, config.outbounds, node))
        // Else the port of the secondary inbound would be blamed for the profile that went missing in between.
        assertEquals("socks5://127.0.0.1:10822", CoreConfigManager.routeAetherThroughXray(config, core, 10822, node)!!.arguments.last())
        assertEquals("trojan", config.outbounds.last().protocol)
        assertEquals(1, lookups)
    }

    @Test
    fun theSessionsCoreCarriesTheContentOfItsExitNodeInItsKey() {
        val core = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --protocol masque")!!.copy(exit = AetherExit(node = "germany"))
        val built = ExitNodeOutbound.Built(V2rayConfig.OutboundBean(tag = AppConfig.TAG_PROXY, protocol = "trojan"), content = "c0ffee")

        assertEquals(AetherExit(node = "germany", nodeContent = "c0ffee"), CoreConfigManager.withNodeContent(core, { built }).exit)
        // Without a node, or a node that gave no outbound, the core is as it was.
        assertEquals(core, CoreConfigManager.withNodeContent(core) { ExitNodeOutbound.NotFound })
        val plain = core.copy(exit = AetherExit.PLAIN)
        assertEquals(plain, CoreConfigManager.withNodeContent(plain) { error("no node to look up") })
    }

    @Test
    fun aCoreWithAnUpstreamOfItsOwnLeavesTheConfigurationAlone() {
        val config = V2rayConfig(
            log = V2rayConfig.LogBean(),
            inbounds = arrayListOf(),
            outbounds = arrayListOf(socks(AppConfig.LOOPBACK, 10819)),
            routing = V2rayConfig.RoutingBean(domainStrategy = "AsIs", rules = arrayListOf()),
        )
        val own = AetherCore.ofCommand("aether --bind 127.0.0.1:10819 --upstream socks5://127.0.0.1:1080")!!

        assertEquals(own, CoreConfigManager.routeAetherThroughXray(config, own, 10822))
        assertTrue(config.inbounds.isEmpty())
        assertEquals(1, config.outbounds.size)
        assertTrue(config.routing.rules.isEmpty())
    }

    @Test
    fun aLatencyTestsOutboundsCarryNoMuxAndPassEveryNameOn() {
        // A test has no DNS: a name looked up by Xray would be asked of the phone's own resolver, outside the tunnel.
        val outbounds = listOf(
            socks("127.0.0.1", 10819).apply { targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6 },
            socks("203.0.113.7", 1080).apply { targetStrategy = "UseIPv4" },
            socks("203.0.113.8", 1080),
        )
        assertTrue(outbounds.all { it.mux != null })

        CoreConfigManager.trimOutboundsForSpeedtest(outbounds)

        assertTrue(outbounds.all { it.mux == null && it.targetStrategy == null })
    }

    @Test
    fun aLatencyTestRefusesWhatTheSessionRefusesForTheCoresOfItsGroupsFallback() {
        val member = ProfileItem.create(EConfigType.AETHER).apply { remarks = "member warp"; aetherProtocol = "wg" }
        val other = ProfileItem.create(EConfigType.AETHER).apply { remarks = "chain warp"; aetherProtocol = "masque" }
        val entry = ProfileItem.create(EConfigType.VLESS).apply { remarks = "entry"; server = "203.0.113.7"; serverPort = "443" }
        val group = CoreConfigContext.ResolvedOutbound(AppConfig.TAG_PROXY, member, listOf(member), CoreResolvedType.POLICYGROUP)
        // The fallback, a chain whose Aether hop dials out through entry: another core.
        val fallback = CoreConfigContext.ResolvedOutbound("fallback", other, listOf(other, entry), CoreResolvedType.PROXYCHAIN)

        assertEquals(AetherDependency.Conflicting, CoreConfigManager.speedtestCoresRefusal(listOf(group, fallback)))
        // The primary alone, or a fallback without a core of its own, or on the same core, is measured.
        assertNull(CoreConfigManager.speedtestCoresRefusal(listOf(group)))
        val plain = CoreConfigContext.ResolvedOutbound("fallback", entry, listOf(entry), CoreResolvedType.NORMAL)
        assertNull(CoreConfigManager.speedtestCoresRefusal(listOf(group, plain)))
        val same = CoreConfigContext.ResolvedOutbound("fallback", member, listOf(member), CoreResolvedType.NORMAL)
        assertNull(CoreConfigManager.speedtestCoresRefusal(listOf(group, same)))
    }

    @Test
    fun aLeastPingOrLeastLoadGroupFallsBackToItsOwnFirstMember() {
        val group = ProfileItem.create(EConfigType.POLICYGROUP).apply { policyGroupFallbackTag = "other" }
        val first = "proxy-group-1-a"
        // Their own first member, whatever fallback the group names, so that a probe missing or failing does not send a
        // matched rule through the main profile.
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.LEAST_PING, group, first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.LEAST_LOAD, group, first))
        // Random and round robin that test their members: the fallback the group names, or else their first member.
        assertEquals("other", CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.RANDOM, group, first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.ROUND_ROBIN, group.copy(policyGroupFallbackTag = ""), first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.ROUND_ROBIN, group.copy(policyGroupFallbackTag = "  "), first))
        assertEquals(first, CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.RANDOM, group.copy(policyGroupFallbackTag = AppConfig.TAG_PROXY), first))
        // Random or round robin that do not test their members: none.
        assertNull(CoreConfigManager.resolvePolicyGroupFallbackTag(BalancerStrategyType.RANDOM, group.copy(policyGroupTestOutbounds = false), first))
    }

    @Test
    fun onlyLeastPingAndCheckedRandomOrRoundRobinGroupsGetTheStandardObservatory() {
        assertTrue(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.LEAST_PING, "proxy-group-1-a"))
        assertTrue(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.LEAST_PING, null))
        // Least load keeps its burst observatory alone, its fallback set now.
        assertFalse(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.LEAST_LOAD, "proxy-group-1-a"))
        assertTrue(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.RANDOM, "proxy-group-1-a"))
        assertFalse(CoreConfigManager.shouldUseStandardObservatory(BalancerStrategyType.ROUND_ROBIN, null))
    }
}
