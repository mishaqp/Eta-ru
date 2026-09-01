package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire

/** 用持久 checkpoint、终态 outbox 与 Runtime 活跃状态共同判定恢复动作。 */
internal object AgentRunRecoveryCoordinator {
    data class Completed(
        val result: AgentRuntimeWire.CompletedRun,
        val checkpoint: AgentRunCheckpointStore.Checkpoint?,
    )

    data class Plan(
        val completed: List<Completed>,
        val reattach: AgentRunCheckpointStore.Checkpoint?,
        val reattachAll: List<AgentRunCheckpointStore.Checkpoint> = emptyList(),
        val interrupted: List<AgentRunCheckpointStore.Checkpoint>,
    )

    fun plan(
        checkpoints: List<AgentRunCheckpointStore.Checkpoint>,
        completedRuns: List<AgentRuntimeWire.CompletedRun>,
        activeStateKnown: Boolean,
        terminalStateKnown: Boolean,
        activeRunId: String?,
        locallyObservedRunId: String?,
        activeRunIds: Set<String> = emptySet(),
        locallyObservedRunIds: Set<String> = emptySet(),
    ): Plan {
        val observedRunIds = locallyObservedRunIds + listOfNotNull(locallyObservedRunId)
        val knownActiveRunIds = activeRunIds + listOfNotNull(activeRunId)
        val uiCheckpoints = checkpoints
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .associateBy { it.runId }
        val completed = completedRuns
            .asSequence()
            .filter { it.handoff.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            .filterNot { it.stableRunId in observedRunIds }
            .sortedBy { it.createdAt }
            .map { run -> Completed(run, uiCheckpoints[run.stableRunId]) }
            .toList()
        val completedRunIds = completed.mapTo(mutableSetOf()) { it.result.stableRunId }
        val unresolved = uiCheckpoints.values
            .filterNot { it.runId in observedRunIds || it.runId in completedRunIds }
            .sortedBy { it.createdAt }
        val active = unresolved
            .takeIf { activeStateKnown }
            ?.filter { it.runId in knownActiveRunIds }
            .orEmpty()

        return Plan(
            completed = completed,
            reattach = active.firstOrNull(),
            reattachAll = active,
            interrupted = if (activeStateKnown && terminalStateKnown) {
                unresolved.filterNot { it.runId in knownActiveRunIds }
            } else {
                emptyList()
            },
        )
    }

    internal val AgentRuntimeWire.CompletedRun.stableRunId: String
        get() = result.runId.ifBlank { handoff.id }
}
