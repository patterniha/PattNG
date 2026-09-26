package com.v2ray.ang.service

import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RealPingExecutionLimiterTest {

    @Test
    fun customConfigMeasurementsAreSerializedAcrossWorkers() {
        runBlocking {
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)

            List(8) {
                async(Dispatchers.Default) {
                    RealPingExecutionLimiter.run(EConfigType.CUSTOM, PLAIN_CONFIG) {
                        val current = active.incrementAndGet()
                        maxActive.accumulateAndGet(current, ::maxOf)
                        Thread.sleep(20)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()

            assertEquals(1, maxActive.get())
        }
    }

    @Test
    fun generatedConfigMeasurementsRemainConcurrent() {
        runBlocking {
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)

            val jobs = List(2) {
                async(Dispatchers.Default) {
                    RealPingExecutionLimiter.run(EConfigType.VMESS, PLAIN_CONFIG) {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }

            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
            jobs.awaitAll()
        }
    }

    @Test
    fun echOutboundConfigMeasurementsRunWhileNoOtherOneRuns() {
        // PattNG: Xray looks the ECH outbound up in the core that started last in the process.
        runBlocking {
            val active = AtomicInteger(0)
            val maxActive = AtomicInteger(0)
            val echActive = AtomicBoolean(false)
            val overlaps = AtomicInteger(0)

            List(12) { index ->
                async(Dispatchers.Default) {
                    if (index % 3 == 0) {
                        RealPingExecutionLimiter.run(EConfigType.VLESS, ECH_CONFIG) {
                            if (active.getAndIncrement() != 0) overlaps.incrementAndGet()
                            echActive.set(true)
                            Thread.sleep(20)
                            echActive.set(false)
                            active.decrementAndGet()
                        }
                    } else {
                        RealPingExecutionLimiter.run(EConfigType.VLESS, PLAIN_CONFIG) {
                            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                            if (echActive.get()) overlaps.incrementAndGet()
                            Thread.sleep(20)
                            if (echActive.get()) overlaps.incrementAndGet()
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()

            assertEquals(0, overlaps.get())
            assertTrue(maxActive.get() > 1)
        }
    }

    @Test
    fun echOutboundConfigMeasurementWaitsWithoutHoldingUpOthers() {
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val echMeasured = AtomicBoolean(false)

            val running = async(Dispatchers.Default) {
                RealPingExecutionLimiter.run(EConfigType.VMESS, PLAIN_CONFIG) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val ech = async(Dispatchers.Default) {
                RealPingExecutionLimiter.run(EConfigType.VLESS, ECH_CONFIG) { echMeasured.set(true) }
            }
            delay(100)
            // A measurement that comes while the ECH one waits for the running one still starts.
            val later = async(Dispatchers.Default) {
                RealPingExecutionLimiter.run(EConfigType.VMESS, PLAIN_CONFIG) { echMeasured.get() }
            }

            val laterSawEchMeasured = later.await()
            release.countDown()
            running.await()
            ech.await()

            assertFalse(laterSawEchMeasured)
            assertTrue(echMeasured.get())
        }
    }

    private companion object {
        const val PLAIN_CONFIG = """{"outbounds": [{"tag": "proxy", "protocol": "vless"}]}"""
        const val ECH_CONFIG =
            """{"outbounds": [{"tag": "proxy", "protocol": "vless", "streamSettings": {"tlsSettings": {"echSockopt": {"dialerProxy": "ech"}}}}]}"""
    }
}
