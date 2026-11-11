/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.queue

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Persistent [JobStore]; survives process death. */
class SqliteJobStore(context: Context) : JobStore {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "processing_jobs.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_uri TEXT NOT NULL UNIQUE,
                    look_id TEXT,
                    intensity REAL NOT NULL,
                    watermark TEXT NOT NULL,
                    ops TEXT,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    next_attempt_at INTEGER NOT NULL,
                    output_uri TEXT,
                    error TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL)"""
            )
            db.execSQL(INDEX)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE jobs ADD COLUMN ops TEXT")
            if (oldVersion < 3) db.execSQL(INDEX)
        }
    }

    override fun insert(job: Job): Long = helper.writableDatabase.insertOrThrow("jobs", null, values(job))

    override fun update(job: Job) {
        helper.writableDatabase.update("jobs", values(job), "id = ?", arrayOf(job.id.toString()))
    }

    override fun get(id: Long) = query("id = ?", arrayOf(id.toString())).firstOrNull()

    override fun findBySource(sourceUri: String) = query("source_uri = ?", arrayOf(sourceUri)).firstOrNull()

    override fun all() = query(null, null)

    override fun pending() = query("state IN ('QUEUED','RUNNING','WRITTEN')", null)

    override fun delete(id: Long) {
        helper.writableDatabase.delete("jobs", "id = ?", arrayOf(id.toString()))
    }

    private fun query(where: String?, args: Array<String>?): List<Job> =
        helper.readableDatabase.query("jobs", null, where, args, null, null, "id").use { c ->
            buildList { while (c.moveToNext()) add(c.toJob()) }
        }

    private fun values(job: Job) = ContentValues().apply {
        put("source_uri", job.sourceUri)
        put("look_id", job.lookId)
        put("intensity", job.intensity)
        put("watermark", job.watermark)
        put("ops", job.ops)
        put("state", job.state.name)
        put("attempts", job.attempts)
        put("next_attempt_at", job.nextAttemptAt)
        put("output_uri", job.outputUri)
        put("error", job.error)
        put("created_at", job.createdAt)
        put("updated_at", job.updatedAt)
    }

    private fun Cursor.toJob() = Job(
        id = getLong(getColumnIndexOrThrow("id")),
        sourceUri = getString(getColumnIndexOrThrow("source_uri")),
        lookId = getString(getColumnIndexOrThrow("look_id")),
        intensity = getFloat(getColumnIndexOrThrow("intensity")),
        watermark = getString(getColumnIndexOrThrow("watermark")),
        ops = getString(getColumnIndexOrThrow("ops")),
        state = JobState.valueOf(getString(getColumnIndexOrThrow("state"))),
        attempts = getInt(getColumnIndexOrThrow("attempts")),
        nextAttemptAt = getLong(getColumnIndexOrThrow("next_attempt_at")),
        outputUri = getString(getColumnIndexOrThrow("output_uri")),
        error = getString(getColumnIndexOrThrow("error")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )
}

private const val INDEX = "CREATE INDEX IF NOT EXISTS jobs_state_due ON jobs (state, next_attempt_at)"
