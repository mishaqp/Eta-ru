package io.github.mangi.eta.agent.device

import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes operations that touch the one physical Android UI shared by all Agent runs.
 * Network/model work remains parallel; only screen reads and screen mutations use this gate.
 */
internal object AgentDeviceActionCoordinator {
    private const val WAIT_SLICE_MS = 500L
    private val lock = ReentrantLock(true)
    private val owner = AtomicReference<String?>(null)

    fun <T> withLease(
        runId: String,
        toolName: String,
        block: () -> T,
    ): T {
        if (toolName !in DEVICE_UI_TOOL_NAMES) return block()

        val startedAt = System.nanoTime()
        var acquired = false
        try {
            while (!acquired) {
                acquired = lock.tryLock(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                if (!acquired && Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Agent device action wait interrupted")
                }
            }
            owner.set(runId)
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            if (waitedMs >= WAIT_SLICE_MS) {
                AndroidAgentLogger.debug {
                    "Agent device action waited: run_id=$runId tool=$toolName waited_ms=$waitedMs"
                }
            }
            return block()
        } finally {
            if (acquired) {
                owner.compareAndSet(runId, null)
                lock.unlock()
            }
        }
    }

    fun currentOwner(): String? = owner.get()

    private val DEVICE_UI_TOOL_NAMES = setOf(
        "get_current_context",
        "launch_app",
        "open_uri",
        "observe_screen",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "input_text",
        "replace_text",
        "clear_text",
        "set_clipboard",
        "get_clipboard",
        "paste_text",
        "press_key",
        "wait_for_text",
        "wait_for_package",
        "open_system_panel",
        "set_setting",
        "set_device_state",
        "app_state_control",
    )
}
