/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.flow

import android.database.Cursor
import kotlinx.coroutines.flow.Flow

interface QueryFlow<T> {
    /**
     * A flow of the data specified by the query
     */
    fun flowData(): Flow<List<T>>

    /**
     * A flow of the cursor specified by the query
     */
    fun flowCursor(): Flow<Cursor?>
}
