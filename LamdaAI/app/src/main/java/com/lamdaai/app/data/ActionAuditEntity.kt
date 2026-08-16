package com.lamdaai.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One row per screen action LamdaAccessibilityService actually executed (or
 * was denied), sensitive or not. Exists so a user can look back at
 * "everything this app tapped/typed on my behalf" after the fact — see
 * ActionGate's [logger] hook, which is what writes these.
 *
 * Deliberately outside the ActionGate/ScreenAction classes themselves —
 * accessibility/ has no Room dependency, and shouldn't need one just to log.
 */
@Entity(tableName = "action_audit")
data class ActionAuditEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long,
    /** Human-readable one-liner from ScreenAction.describe(), e.g. `Tap "Delete"`. */
    val description: String,
    /** Package the action targeted, if known (null for BACK/HOME/RECENTS). */
    val packageName: String?,
    /** Whether SensitivityClassifier flagged this and routed it through confirmation. */
    val wasSensitive: Boolean,
    /** True if the action actually ran; false if denied or the underlying node call failed. */
    val executed: Boolean
)
