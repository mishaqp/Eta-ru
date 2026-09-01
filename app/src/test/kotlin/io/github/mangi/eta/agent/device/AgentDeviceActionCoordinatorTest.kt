package io.github.mangi.eta.agent.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceActionCoordinatorTest {
    @Test
    fun screenActionsAreSerializedAcrossRuns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val secondFinished = CountDownLatch(1)

        val first = Thread {
            AgentDeviceActionCoordinator.withLease("run-a", "tap") {
                val current = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { old -> maxOf(old, current) }
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                inFlight.decrementAndGet()
            }
        }
        val second = Thread {
            entered.await(2, TimeUnit.SECONDS)
            AgentDeviceActionCoordinator.withLease("run-b", "tap") {
                val current = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { old -> maxOf(old, current) }
                inFlight.decrementAndGet()
                secondFinished.countDown()
            }
        }

        first.start()
        second.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertTrue(!secondFinished.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
        first.join(2_000)
        second.join(2_000)
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun nonScreenWorkDoesNotUseTheDeviceGate() {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val start = CountDownLatch(2)
        val release = CountDownLatch(1)
        val threads = listOf("run-a", "run-b").map { runId ->
            Thread {
                AgentDeviceActionCoordinator.withLease(runId, "run_command") {
                    val current = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { old -> maxOf(old, current) }
                    start.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    inFlight.decrementAndGet()
                }
            }
        }
        threads.forEach(Thread::start)
        assertTrue(start.await(2, TimeUnit.SECONDS))
        release.countDown()
        threads.forEach { it.join(2_000) }
        assertEquals(2, maxInFlight.get())
    }
}
