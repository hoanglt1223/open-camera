/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.queue

/**
 * Job lifecycle:
 *
 * ```
 * QUEUED -> RUNNING -> WRITTEN -> DONE
 *    ^         |
 *    +--retry--+--(attempts exhausted)--> FAILED
 * ```
 *
 * WRITTEN means the processed file is fully stored (still pending) and its URI
 * is recorded; from there only the publish step remains, which is idempotent.
 */
enum class JobState { QUEUED, RUNNING, WRITTEN, DONE, FAILED }

data class Job(
    val id: Long = 0,
    val sourceUri: String,
    val lookId: String?,
    val intensity: Float,
    val watermark: String,
    /** ML steps as [dev.opencamera.pipeline.ai.AiOps] JSON; null for look/frame-only jobs. */
    val ops: String? = null,
    val state: JobState = JobState.QUEUED,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val outputUri: String? = null,
    val error: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

interface JobStore {
    /** Inserts [job] and returns its id. */
    fun insert(job: Job): Long
    fun update(job: Job)
    fun get(id: Long): Job?
    fun findBySource(sourceUri: String): Job?
    fun all(): List<Job>
    fun delete(id: Long)

    /** Jobs in a pending state; stores override this with an indexed query. */
    fun pending(): List<Job> = all().filter { it.state.isPending }
}

class InMemoryJobStore : JobStore {
    private val jobs = LinkedHashMap<Long, Job>()
    private var nextId = 1L

    @Synchronized
    override fun insert(job: Job): Long = nextId++.also { jobs[it] = job.copy(id = it) }

    @Synchronized
    override fun update(job: Job) {
        jobs[job.id] = job
    }

    @Synchronized
    override fun get(id: Long) = jobs[id]

    @Synchronized
    override fun findBySource(sourceUri: String) = jobs.values.firstOrNull { it.sourceUri == sourceUri }

    @Synchronized
    override fun all() = jobs.values.toList()

    @Synchronized
    override fun delete(id: Long) {
        jobs.remove(id)
    }
}

/** Pure state machine over a [JobStore]; no Android dependencies. */
class ProcessingQueue(
    private val store: JobStore,
    private val clock: () -> Long = System::currentTimeMillis,
    val maxAttempts: Int = 3,
    private val baseBackoffMs: Long = 2_000,
    private val keepFinishedMs: Long = 24 * 60 * 60 * 1000L,
) {
    /** Adds a job; a second request for the same source returns the existing job unless it is a finished one and [ops] is set. */
    @Synchronized
    fun enqueue(sourceUri: String, lookId: String?, intensity: Float, watermark: String, ops: String? = null): Job {
        store.findBySource(sourceUri)?.let {
            // A finished job may be replaced by a new edit of the same photo.
            if (it.state.isPending || ops == null) return it
            store.delete(it.id)
        }
        val now = clock()
        val job = Job(
            sourceUri = sourceUri, lookId = lookId, intensity = intensity, watermark = watermark, ops = ops,
            createdAt = now, updatedAt = now, nextAttemptAt = now,
        )
        return job.copy(id = store.insert(job))
    }

    /**
     * Called once when a worker starts. A job left RUNNING was interrupted by process
     * death; it counts as a failed attempt so a crashing image cannot loop forever.
     */
    @Synchronized
    fun recover() {
        val now = clock()
        store.pending().filter { it.state == JobState.RUNNING }.forEach {
            store.update(failed(it, "Interrupted", retryable = true, now))
        }
        store.all().filter {
            (it.state == JobState.DONE || it.state == JobState.FAILED) && now - it.updatedAt > keepFinishedMs
        }.forEach { store.delete(it.id) }
    }

    /**
     * Claims the next job that is due. QUEUED jobs move to RUNNING; WRITTEN jobs are
     * returned as is so the caller finishes publishing them.
     */
    @Synchronized
    fun claimNext(): Job? {
        val now = clock()
        val job = store.pending()
            .filter { (it.state == JobState.QUEUED || it.state == JobState.WRITTEN) && it.nextAttemptAt <= now }
            .minWithOrNull(compareBy<Job> { it.nextAttemptAt }.thenBy { it.id }) ?: return null
        if (job.state == JobState.WRITTEN) return job
        return job.copy(state = JobState.RUNNING, updatedAt = now).also(store::update)
    }

    @Synchronized
    fun markWritten(id: Long, outputUri: String): Job = transition(id, JobState.RUNNING) {
        it.copy(state = JobState.WRITTEN, outputUri = outputUri, error = null)
    }

    @Synchronized
    fun markDone(id: Long): Job = transition(id, JobState.WRITTEN) { it.copy(state = JobState.DONE, error = null) }

    /** Records a failure from RUNNING or WRITTEN; retries with exponential backoff while attempts remain. */
    @Synchronized
    fun markFailed(id: Long, error: String, retryable: Boolean): Job {
        val job = requireNotNull(store.get(id)) { "No job $id" }
        check(job.state == JobState.RUNNING || job.state == JobState.WRITTEN) {
            "Cannot fail job $id in ${job.state}"
        }
        return failed(job, error, retryable, clock()).also(store::update)
    }

    @Synchronized
    fun pendingCount(): Int = store.pending().size

    /** Earliest time a pending job becomes due, or null if nothing is pending. */
    @Synchronized
    fun nextDueAt(): Long? = store.pending().minOfOrNull { it.nextAttemptAt }

    @Synchronized
    fun get(id: Long) = store.get(id)

    private fun failed(job: Job, error: String, retryable: Boolean, now: Long): Job {
        val attempts = job.attempts + 1
        // A WRITTEN job keeps its output; a retry resumes at the publish step.
        val retryState = if (job.state == JobState.WRITTEN) JobState.WRITTEN else JobState.QUEUED
        return if (retryable && attempts < maxAttempts) {
            job.copy(
                state = retryState, attempts = attempts, error = error, updatedAt = now,
                nextAttemptAt = now + baseBackoffMs * (1L shl (attempts - 1)),
            )
        } else {
            job.copy(state = JobState.FAILED, attempts = attempts, error = error, updatedAt = now)
        }
    }

    private fun transition(id: Long, from: JobState, change: (Job) -> Job): Job {
        val job = requireNotNull(store.get(id)) { "No job $id" }
        check(job.state == from) { "Job $id is ${job.state}, expected $from" }
        return change(job).copy(updatedAt = clock()).also(store::update)
    }
}

val JobState.isPending get() = this == JobState.QUEUED || this == JobState.RUNNING || this == JobState.WRITTEN
