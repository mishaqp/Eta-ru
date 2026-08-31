package io.github.mangi.eta.agent.task

/** Names reserved for persistent task management. Tasks cannot recursively create tasks. */
internal object AgentTaskToolNames {
    const val SCHEDULE = "schedule_job"
    const val LIST = "list_jobs"
    const val HISTORY = "get_job_history"
    const val DELETE = "delete_job"
    const val PAUSE = "pause_job"
    const val RESUME = "resume_job"
    const val TRIGGER = "trigger_job_now"

    val ALL = setOf(SCHEDULE, LIST, HISTORY, DELETE, PAUSE, RESUME, TRIGGER)
}
