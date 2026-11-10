package com.v2ray.ang.ui.server

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.AetherFingerprint
import com.v2ray.ang.enums.AetherIpVersion
import com.v2ray.ang.enums.AetherObfuscation
import com.v2ray.ang.enums.AetherProtocol
import com.v2ray.ang.enums.AetherPsiphon
import com.v2ray.ang.enums.AetherPsiphonCdnSet
import com.v2ray.ang.enums.AetherScanMode
import com.v2ray.ang.enums.AetherTor
import com.v2ray.ang.enums.AetherTransport
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.AetherFmt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerUiStateTest {

    @Test
    fun aetherOptionsAreNormalizedToValuesTheDropdownsKnow() {
        val profile = ProfileItem.create(EConfigType.AETHER).apply {
            aetherProtocol = "gool"
            aetherTransport = "h2"
            aetherScanMode = "from-a-newer-version"
            aetherObfuscation = null
            aetherIpVersion = "both"
        }

        val state = ServerUiState.from(profile)

        assertEquals(AetherProtocol.GOOL.type, state.aetherProtocol)
        assertEquals(AetherTransport.HTTP2.type, state.aetherTransport)
        assertEquals(AetherScanMode.BALANCED.type, state.aetherScanMode)
        assertEquals(AetherObfuscation.AUTO.type, state.aetherObfuscation)
        assertEquals(AetherIpVersion.DUAL.type, state.aetherIpVersion)
    }

    @Test
    fun aNewAetherProfileStartsWithTheDefaultsAndNoPort() {
        val state = ServerUiState.from(ProfileItem.create(EConfigType.AETHER))

        assertEquals(AetherProtocol.WIREGUARD.type, state.aetherProtocol)
        assertEquals(AetherTransport.HTTP3.type, state.aetherTransport)
        assertEquals("", state.port)
    }

    @Test
    fun theEchOutboundIsCarriedToTheProfileAndStoredOnlyWhenSet() {
        val json = """{"tag": "ech-out", "protocol": "freedom"}"""
        val profile = ProfileItem.create(EConfigType.VLESS).apply { echOutbound = json }

        val state = ServerUiState.from(profile)
        assertEquals(json, state.echOutbound)
        assertEquals(json, state.toProfileItem(profile).echOutbound)

        state.echOutbound = "  "
        assertNull(state.toProfileItem(profile).echOutbound)
    }

    @Test
    fun theCurvePreferencesAndSessionResumptionAreCarriedToTheProfile() {
        // PattNG: the editor's curvePreferences dropdown and enableSessionResumption switch, after cipherSuites.
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            security = AppConfig.TLS
            curvePreferences = "X25519MLKEM768,X25519"
            enableSessionResumption = true
        }

        val state = ServerUiState.from(profile)
        assertEquals("X25519MLKEM768,X25519", state.curvePreferences)
        assertTrue(state.enableSessionResumption)
        val built = state.toProfileItem(profile)
        assertEquals("X25519MLKEM768,X25519", built.curvePreferences)
        assertEquals(true, built.enableSessionResumption)

        // A profile stored before they existed starts with none and off.
        val older = ServerUiState.from(ProfileItem.create(EConfigType.VLESS))
        assertEquals("", older.curvePreferences)
        assertEquals(false, older.enableSessionResumption)
        state.curvePreferences = ""
        state.enableSessionResumption = false
        assertEquals("", state.toProfileItem(profile).curvePreferences)
        assertEquals(false, state.toProfileItem(profile).enableSessionResumption)
    }

    @Test
    fun theTargetStrategyDefaultsToAsIsAndIsStoredOnlyWhenChanged() {
        val profile = ProfileItem.create(EConfigType.VLESS)

        val untouched = ServerUiState.from(profile)
        // None is chosen while the profile follows its default, which the screen shows.
        assertEquals("", untouched.targetStrategy)
        assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, untouched.shownTargetStrategy)
        assertNull(untouched.toProfileItem(profile).targetStrategy)

        untouched.targetStrategy = "UseIPv4v6"
        assertEquals("UseIPv4v6", untouched.shownTargetStrategy)
        assertEquals("UseIPv4v6", untouched.toProfileItem(profile).targetStrategy)

        val stored = ServerUiState.from(ProfileItem.create(EConfigType.AETHER).apply { targetStrategy = "ForceIP" })
        assertEquals("ForceIP", stored.targetStrategy)
    }

    @Test
    fun anAetherProfileThroughWarpDefaultsToForceIPv4v6AndAWireguardOneToAsIs() {
        val aether = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(aether)
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, state.shownTargetStrategy)
        // The default, chosen, is stored as none, so that the profile follows it.
        state.targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6
        assertNull(state.toProfileItem(aether).targetStrategy)
        // AsIs is not its default, so it is stored as it is, and read back so.
        state.targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS
        val asIs = state.toProfileItem(aether)
        assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, asIs.targetStrategy)
        assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, ServerUiState.from(asIs).shownTargetStrategy)

        // A WireGuard tunnel looks names up itself, with the profile's own DNS: it passes them on as they are.
        val wireguard = ProfileItem.create(EConfigType.WIREGUARD)
        val wireguardState = ServerUiState.from(wireguard)
        assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, wireguardState.shownTargetStrategy)
        assertNull(wireguardState.toProfileItem(wireguard).targetStrategy)
        wireguardState.targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, wireguardState.toProfileItem(wireguard).targetStrategy)
        // One that stored AsIs while ForceIPv4v6 was the default of its type follows its default, AsIs, once saved again.
        val storedAsIs = wireguard.copy(targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS)
        assertNull(ServerUiState.from(storedAsIs).toProfileItem(storedAsIs).targetStrategy)
    }

    @Test
    fun anAetherProfilesDefaultFollowsWhereTorAndPsiphonStandOnTheScreen() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)

        // Inside the tunnel, or alone, Tor and Psiphon carry the traffic last and look names up at their exit.
        for (tor in listOf(AetherTor.CHAIN, AetherTor.ONLY)) {
            state.aetherTor = tor.type
            assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, state.shownTargetStrategy, tor.name)
            assertNull(state.toProfileItem(profile).targetStrategy, tor.name)
        }
        // Around the tunnel, they leave the traffic to WARP.
        state.aetherTor = AetherTor.REVERSE.type
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, state.shownTargetStrategy)
        state.aetherTor = AetherTor.OFF.type
        for (psiphon in listOf(AetherPsiphon.CHAIN, AetherPsiphon.ONLY)) {
            state.aetherPsiphon = psiphon.type
            assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, state.shownTargetStrategy, psiphon.name)
        }
        state.aetherPsiphon = AetherPsiphon.REVERSE.type
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, state.shownTargetStrategy)

        // A choice is weighed against the default of the profile as it is saved: on one whose traffic leaves through
        // Tor, ForceIPv4v6 is stored, and AsIs is not.
        state.aetherPsiphon = AetherPsiphon.OFF.type
        state.aetherTor = AetherTor.CHAIN.type
        state.targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, state.toProfileItem(profile).targetStrategy)
        state.targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS
        assertNull(state.toProfileItem(profile).targetStrategy)
    }

    @Test
    fun aCommandOfItsOwnSaysWhereAnAetherProfilesTrafficLeaves() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        val built = com.v2ray.ang.core.AetherCore.of(state.toProfileItem(profile, 20808), 20808).command

        // The settings leave through WARP, the command through Tor; the command runs, so the traffic leaves through Tor.
        state.aetherCommand = "$built --tor"
        assertEquals(AppConfig.TARGET_STRATEGY_AS_IS, state.shownTargetStrategy)
        val stored = state.toProfileItem(profile, 20808)
        assertEquals("$built --tor", stored.aetherCommand)
        assertNull(stored.targetStrategy)
        state.targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6
        assertEquals(AppConfig.TARGET_STRATEGY_FORCE_IPV4V6, state.toProfileItem(profile, 20808).targetStrategy)
    }

    @Test
    fun aListenPortAProfileStillCarriesIsKeptAsItWasStored() {
        // Profiles stored while each profile had a listen port of its own may carry one still; the editor neither shows nor drops it.
        val stored = ProfileItem.create(EConfigType.AETHER).apply { aetherListenPort = "20808" }
        assertEquals("20808", ServerUiState.from(stored).toProfileItem(stored).aetherListenPort)

        val fresh = ProfileItem.create(EConfigType.AETHER)
        assertNull(ServerUiState.from(fresh).toProfileItem(fresh).aetherListenPort)
    }

    @Test
    fun psiphonIsOffByDefaultAndItsSettingsAreStoredOnlyWhileItIsOn() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        assertEquals("off", state.aetherPsiphon)
        assertEquals("auto", state.aetherPsiphonMode)
        assertEquals(true, state.aetherPsiphonBundledList)
        assertNull(state.toProfileItem(profile).aetherPsiphon)
        assertNull(state.toProfileItem(profile).aetherPsiphonMode)

        state.aetherPsiphonMode = "cdn"
        state.aetherPsiphonRegion = "DE"
        state.aetherPsiphonBundledList = false
        // Settings of a Psiphon that is off are not kept.
        assertNull(state.toProfileItem(profile).aetherPsiphonMode)
        assertNull(state.toProfileItem(profile).aetherPsiphonRegion)
        assertNull(state.toProfileItem(profile).aetherPsiphonBundledList)

        state.aetherPsiphon = "chain"
        val stored = state.toProfileItem(profile)
        assertEquals("chain", stored.aetherPsiphon)
        assertEquals("cdn", stored.aetherPsiphonMode)
        assertEquals("DE", stored.aetherPsiphonRegion)
        assertEquals(false, stored.aetherPsiphonBundledList)
        assertNull(stored.aetherPsiphonCdnIps)

        val reloaded = ServerUiState.from(stored)
        assertEquals("chain", reloaded.aetherPsiphon)
        assertEquals("cdn", reloaded.aetherPsiphonMode)
        assertEquals("DE", reloaded.aetherPsiphonRegion)
        assertEquals(false, reloaded.aetherPsiphonBundledList)
        // Yes is the default and is stored as nothing.
        reloaded.aetherPsiphonBundledList = true
        assertNull(reloaded.toProfileItem(stored).aetherPsiphonBundledList)
        assertEquals("", reloaded.aetherPsiphonCdnIps)
    }

    @Test
    fun torIsOffByDefaultAndItsSettingsAreStoredOnlyWhileItIsOn() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        assertEquals("off", state.aetherTor)
        assertEquals("auto", state.aetherTorBridges)
        assertEquals("", state.aetherTorBridgeLines)
        assertNull(state.toProfileItem(profile).aetherTor)

        state.aetherTorBridges = "own"
        state.aetherTorBridgeLines = "obfs4 192.0.2.55:38114 316E64 cert=abc iat-mode=0"
        // Settings of a Tor that is off are not kept.
        assertNull(state.toProfileItem(profile).aetherTorBridges)
        assertNull(state.toProfileItem(profile).aetherTorBridgeLines)

        state.aetherTor = "reverse"
        val stored = state.toProfileItem(profile)
        assertEquals("reverse", stored.aetherTor)
        assertEquals("own", stored.aetherTorBridges)
        assertEquals("obfs4 192.0.2.55:38114 316E64 cert=abc iat-mode=0", stored.aetherTorBridgeLines)

        val reloaded = ServerUiState.from(stored)
        assertEquals("reverse", reloaded.aetherTor)
        assertEquals("own", reloaded.aetherTorBridges)
        assertEquals("obfs4 192.0.2.55:38114 316E64 cert=abc iat-mode=0", reloaded.aetherTorBridgeLines)
    }

    @Test
    fun theTuningFieldsStartEmptyAndAreStoredOnlyWhenSet() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        assertEquals(AetherObfuscation.AUTO.type, state.aetherObfuscation)
        assertEquals(false, state.aetherEch)
        assertEquals("", state.aetherDns)
        assertEquals("", state.aetherExitLoc)
        assertEquals("auto", state.aetherTorRelays)
        val stored = state.toProfileItem(profile)
        assertEquals(false, stored.aetherEch)
        assertNull(stored.aetherDns)
        assertNull(stored.aetherExitLoc)
        assertNull(stored.aetherTorRelays)

        state.aetherEch = true
        state.aetherDns = "1.1.1.1"
        state.aetherExitLoc = "!IR"
        state.aetherTorRelays = "only"
        // The bridge pool belongs to Tor and is kept only while Tor is on.
        assertNull(state.toProfileItem(profile).aetherTorRelays)
        state.aetherTor = "chain"
        val tuned = state.toProfileItem(profile)
        assertEquals(true, tuned.aetherEch)
        assertEquals("1.1.1.1", tuned.aetherDns)
        assertEquals("!IR", tuned.aetherExitLoc)
        assertEquals("only", tuned.aetherTorRelays)

        val reloaded = ServerUiState.from(tuned)
        assertEquals(true, reloaded.aetherEch)
        assertEquals("1.1.1.1", reloaded.aetherDns)
        assertEquals("!IR", reloaded.aetherExitLoc)
        assertEquals("only", reloaded.aetherTorRelays)
    }

    @Test
    fun theExitNodeIsTheNameOfAProfileAndIsStoredOnlyWhenOneIsChosen() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        assertEquals("", state.aetherExitNode)
        assertNull(state.toProfileItem(profile).aetherExitNode)

        state.aetherExitNode = "germany"
        val chosen = state.toProfileItem(profile)
        assertEquals("germany", chosen.aetherExitNode)
        assertEquals("germany", ServerUiState.from(chosen).aetherExitNode)
        // The finalMask and the dialMode set before are kept, out of use, for freedom again.
        state.finalMask = """{"tcp": []}"""
        assertEquals("""{"tcp": []}""", state.toProfileItem(profile).finalMask)
        // No other type of profile has one.
        state.configType = EConfigType.VLESS
        assertNull(state.toProfileItem(profile).aetherExitNode)
    }

    @Test
    fun theCdnSetsAreChosenOneByOneAndStoredInTheOrderTheCoreTriesThem() {
        val blank = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(blank.apply { aetherPsiphon = "chain" })
        assertTrue(state.aetherPsiphonCdnSetChoice.isEmpty())

        state.setPsiphonCdnSet(AetherPsiphonCdnSet.FASTLY, true)
        state.setPsiphonCdnSet(AetherPsiphonCdnSet.CLOUDFLARE, true)
        assertEquals(setOf(AetherPsiphonCdnSet.CLOUDFLARE, AetherPsiphonCdnSet.FASTLY), state.aetherPsiphonCdnSetChoice)
        assertEquals("cloudflare,fastly", state.toProfileItem(blank).aetherPsiphonCdnSets)

        state.setPsiphonCdnSet(AetherPsiphonCdnSet.FASTLY, false)
        assertEquals("cloudflare", state.aetherPsiphonCdnSets)
        state.setPsiphonCdnSet(AetherPsiphonCdnSet.CLOUDFLARE, false)
        assertNull(state.toProfileItem(blank).aetherPsiphonCdnSets)

        // A choice from before comes back as it was, and goes with Psiphon when Psiphon goes.
        val reloaded = ServerUiState.from(blank.apply { aetherPsiphonCdnSets = "github,vercel" })
        assertEquals(setOf(AetherPsiphonCdnSet.VERCEL, AetherPsiphonCdnSet.GITHUB), reloaded.aetherPsiphonCdnSetChoice)
        reloaded.aetherPsiphon = "off"
        assertNull(reloaded.toProfileItem(blank).aetherPsiphonCdnSets)
    }

    @Test
    fun theFoldedSettingsAnnounceThemselvesOnlyWhenOneHoldsAValue() {
        val state = ServerUiState.from(ProfileItem.create(EConfigType.AETHER))
        assertEquals(AetherIpVersion.V4.type, state.aetherIpVersion)
        assertEquals(false, state.hasOtherAetherSettings)

        // The IP version stands outside the fold, so it does not count.
        state.aetherIpVersion = AetherIpVersion.DUAL.type
        assertEquals(false, state.hasOtherAetherSettings)

        state.aetherDns = "1.1.1.1"
        assertEquals(true, state.hasOtherAetherSettings)
        state.aetherDns = ""
        state.aetherExitLoc = "!IR"
        assertEquals(true, state.hasOtherAetherSettings)
        state.aetherExitLoc = ""
        state.targetStrategy = "UseIPv4v6"
        assertEquals(true, state.hasOtherAetherSettings)
        // AsIs is no default of Aether's: it counts as a setting of its own.
        state.targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS
        assertEquals(true, state.hasOtherAetherSettings)
        state.targetStrategy = AppConfig.TARGET_STRATEGY_FORCE_IPV4V6
        assertEquals(false, state.hasOtherAetherSettings)
        // Where Tor carries the traffic last, AsIs is the default, and ForceIPv4v6 a setting of its own.
        state.aetherTor = AetherTor.CHAIN.type
        assertEquals(true, state.hasOtherAetherSettings)
        state.targetStrategy = AppConfig.TARGET_STRATEGY_AS_IS
        assertEquals(false, state.hasOtherAetherSettings)
        state.aetherTor = AetherTor.OFF.type
        state.targetStrategy = ""
        // The exit-node's finalMask and dialMode stand outside the fold, after the fingerprint.
        state.finalMask = """{"tcp": []}"""
        state.dialMode = "custom"
        assertEquals(false, state.hasOtherAetherSettings)
    }

    @Test
    fun theFingerprintStartsAsChromesAndIsStoredAsChosen() {
        val state = ServerUiState.from(ProfileItem.create(EConfigType.AETHER))
        assertEquals(AetherFingerprint.CHROME.type, state.aetherFingerprint)

        state.aetherFingerprint = AetherFingerprint.GO.type
        val stored = state.toProfileItem(ProfileItem.create(EConfigType.AETHER))
        assertEquals("go", stored.aetherFingerprint)
        assertEquals("go", ServerUiState.from(stored).aetherFingerprint)

        // A value no build wrote reads as Chrome's.
        assertEquals("chrome", ServerUiState.from(stored.copy(aetherFingerprint = "lynx")).aetherFingerprint)
    }

    @Test
    fun theEchResolverAndDomainStartAtTheirDefaultsAndAreKeptWhetherEchIsOnOrOff() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        assertEquals(AppConfig.AETHER_ECH_DNS, state.aetherEchDns)
        assertEquals(AppConfig.AETHER_ECH_DOMAIN, state.aetherEchDomain)

        state.aetherEchDns = "https://doq.dns4all.eu/dns-query"
        state.aetherEchDomain = "ip.gs"
        for (ech in listOf(true, false)) {
            state.aetherEch = ech
            val stored = state.toProfileItem(profile)
            assertEquals("https://doq.dns4all.eu/dns-query", stored.aetherEchDns)
            assertEquals("ip.gs", stored.aetherEchDomain)
            val reloaded = ServerUiState.from(stored)
            assertEquals(ech, reloaded.aetherEch)
            assertEquals("https://doq.dns4all.eu/dns-query", reloaded.aetherEchDns)
            assertEquals("ip.gs", reloaded.aetherEchDomain)
        }

        // A field left empty is the default again.
        state.aetherEchDns = " "
        assertNull(state.toProfileItem(profile).aetherEchDns)
        assertEquals(AppConfig.AETHER_ECH_DNS, ServerUiState.from(state.toProfileItem(profile)).aetherEchDns)
    }

    @Test
    fun theMasqueServerNameStartsAtWwwCloudflareComAndIsKeptWhateverTheProtocol() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        // Shown filled in, before anything is set.
        assertEquals("www.cloudflare.com", state.aetherMasqueSni)

        state.aetherMasqueSni = "consumer-masque.cloudflareclient.com"
        for (protocol in AetherProtocol.entries) {
            state.aetherProtocol = protocol.type
            val stored = state.toProfileItem(profile)
            assertEquals("consumer-masque.cloudflareclient.com", stored.aetherMasqueSni, protocol.type)
            assertEquals("consumer-masque.cloudflareclient.com", ServerUiState.from(stored).aetherMasqueSni, protocol.type)
        }

        // A field left empty is the default again.
        state.aetherMasqueSni = " "
        assertNull(state.toProfileItem(profile).aetherMasqueSni)
        assertEquals("www.cloudflare.com", ServerUiState.from(state.toProfileItem(profile)).aetherMasqueSni)
        // Only an Aether profile carries one.
        val vless = ServerUiState.from(ProfileItem.create(EConfigType.VLESS))
        assertNull(vless.toProfileItem(ProfileItem.create(EConfigType.VLESS)).aetherMasqueSni)
    }

    @Test
    fun aCommandIsStoredOnlyWhenItSaysMoreThanTheSettings() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        assertEquals("", state.aetherCommand)
        assertNull(state.toProfileItem(profile).aetherCommand)

        // The command the settings build, typed back in, is no command of its own.
        val built = com.v2ray.ang.core.AetherCore.of(state.toProfileItem(profile)).command
        state.aetherCommand = " $built "
        assertNull(state.toProfileItem(profile).aetherCommand)

        state.aetherCommand = "$built --dns 1.1.1.1"
        assertEquals("$built --dns 1.1.1.1", state.toProfileItem(profile).aetherCommand)

        val reloaded = ServerUiState.from(state.toProfileItem(profile))
        assertEquals("$built --dns 1.1.1.1", reloaded.aetherCommand)
    }

    @Test
    fun aCommandIsWeighedAgainstTheSettingsOnTheListenPortTheScreenHolds() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        val built = com.v2ray.ang.core.AetherCore.of(state.toProfileItem(profile, 20808), 20808).command
        assertTrue("127.0.0.1:20808" in built, built)
        state.aetherCommand = built
        assertNull(state.toProfileItem(profile, 20808).aetherCommand)
        // On another port the same words say something else than the settings do.
        assertEquals(built, state.toProfileItem(profile, 10819).aetherCommand)
    }

    @Test
    fun theProfileTheEditorHoldsIsBuiltAgainEqualWhileItsChecksNormalizeACopy() {
        val initial = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(initial)
        val held = state.toProfileItem(initial, 20808)
        val checked = held.copy()

        assertNull(AetherFmt.normalize(checked))
        // Normalized, the default ECH resolver and domain are left out: the profile checked is not the one held, so the
        // screen weighs the one it holds, which the outcome of the check carries.
        assertNotEquals(held, checked)
        assertEquals(AppConfig.AETHER_ECH_DNS, held.aetherEchDns)
        // Built again from the same screen it is equal, and not once the screen is edited.
        assertEquals(held, state.toProfileItem(initial, 20808))
        state.remarks = "edited"
        assertNotEquals(held, state.toProfileItem(initial, 20808))
    }

    @Test
    fun theSavedStateKeepsACommandAsTypedWithoutWeighingIt() {
        val profile = ProfileItem.create(EConfigType.AETHER)
        val state = ServerUiState.from(profile)
        val built = com.v2ray.ang.core.AetherCore.of(state.toProfileItem(profile, 10819), 10819).command
        state.aetherCommand = built

        // Weighed, the command the settings build is none of its own; kept as typed, it stays, and reads back the same.
        assertNull(state.toProfileItem(profile, 10819).aetherCommand)
        val saved = state.toProfileItem(ProfileItem.create(EConfigType.AETHER), keepCommand = true)
        assertEquals(built, saved.aetherCommand)
        assertEquals(built, ServerUiState.from(saved).aetherCommand)
    }

    @Test
    fun theHysteria2ObfsShownIsTheOneTheOutboundUses() {
        // PattNG: packet sizes make a profile gecko (ProfileItem.isGeckoObfs), so the screen shows gecko for them.
        fun shown(fields: ProfileItem.() -> Unit) =
            ServerUiState.from(ProfileItem.create(EConfigType.HYSTERIA2).apply(fields)).shownHysteria2Obfs

        assertEquals("none", shown { })
        assertEquals("salamander", shown { obfsPassword = "secret" })
        assertEquals("salamander", shown { obfs = "salamander"; obfsPassword = "secret" })
        assertEquals("gecko", shown { obfs = "gecko"; obfsPassword = "secret" })
        assertEquals("gecko", shown { obfs = "salamander"; obfsPassword = "secret"; minPacketSize = "600" })
        assertEquals("gecko", shown { obfsPassword = "secret"; maxPacketSize = "1300" })
        assertEquals("none", shown { obfs = "none"; obfsPassword = "secret" })
    }

    @Test
    fun anotherHysteria2ObfsDropsWhatWouldKeepTheOldOne() {
        // PattNG: as a gecko link is imported, with its default packet sizes.
        val profile = ProfileItem.create(EConfigType.HYSTERIA2).apply {
            obfs = "gecko"
            obfsPassword = "secret"
            minPacketSize = "512"
            maxPacketSize = "1200"
        }
        val state = ServerUiState.from(profile)

        state.chooseHysteria2Obfs("salamander")
        assertEquals("salamander", state.shownHysteria2Obfs)
        val salamander = state.toProfileItem(profile)
        assertEquals("salamander", salamander.obfs)
        assertEquals("secret", salamander.obfsPassword)
        assertFalse(salamander.isGeckoObfs())

        state.chooseHysteria2Obfs("gecko")
        assertEquals("gecko", state.shownHysteria2Obfs)
        assertTrue(state.toProfileItem(profile).isGeckoObfs())

        state.chooseHysteria2Obfs("none")
        assertEquals("none", state.shownHysteria2Obfs)
        val none = state.toProfileItem(profile)
        assertNull(none.obfs)
        assertEquals("", none.obfsPassword)
        assertNull(none.minPacketSize)
        assertNull(none.maxPacketSize)
    }
}
