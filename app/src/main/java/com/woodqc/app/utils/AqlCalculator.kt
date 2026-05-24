package com.woodqc.app.utils

/**
 * AQL (Acceptable Quality Level) Calculator
 * Standard: ISO 2859-1, General Inspection Level II, AQL 2.5
 *
 * Used by TJX, Kirkland, and most international retail buyers.
 * AQL 2.5 means: accept max 2.5% defect rate in a shipment.
 */
object AqlCalculator {

    data class AqlPlan(
        val batchSize: Int,
        val sampleSize: Int,       // How many items to inspect
        val acceptNumber: Int,     // If rejects <= this → PASS SHIPMENT
        val rejectNumber: Int      // If rejects >= this → HOLD SHIPMENT
    )

    data class AqlResult(
        val scannedSoFar: Int,
        val rejectsSoFar: Int,
        val sampleSize: Int,
        val acceptNumber: Int,
        val isComplete: Boolean,   // True when scannedSoFar >= sampleSize
        val verdict: ShipmentVerdict
    )

    enum class ShipmentVerdict {
        SCANNING,           // Still scanning — not enough samples yet
        PASS_SHIPMENT,      // Sample complete, rejects within AQL limit
        HOLD_SHIPMENT       // Too many rejects — hold this batch
    }

    /**
     * Returns the AQL sampling plan for a given batch size.
     * Table: ISO 2859-1, General Level II, AQL 2.5
     */
    fun getPlan(batchSize: Int): AqlPlan {
        return when {
            batchSize <= 8      -> AqlPlan(batchSize, sampleSize = 2,   acceptNumber = 0, rejectNumber = 1)
            batchSize <= 15     -> AqlPlan(batchSize, sampleSize = 3,   acceptNumber = 0, rejectNumber = 1)
            batchSize <= 25     -> AqlPlan(batchSize, sampleSize = 5,   acceptNumber = 0, rejectNumber = 1)
            batchSize <= 50     -> AqlPlan(batchSize, sampleSize = 8,   acceptNumber = 0, rejectNumber = 1)
            batchSize <= 90     -> AqlPlan(batchSize, sampleSize = 13,  acceptNumber = 1, rejectNumber = 2)
            batchSize <= 150    -> AqlPlan(batchSize, sampleSize = 20,  acceptNumber = 1, rejectNumber = 2)
            batchSize <= 280    -> AqlPlan(batchSize, sampleSize = 32,  acceptNumber = 2, rejectNumber = 3)
            batchSize <= 500    -> AqlPlan(batchSize, sampleSize = 50,  acceptNumber = 3, rejectNumber = 4)
            batchSize <= 1200   -> AqlPlan(batchSize, sampleSize = 80,  acceptNumber = 5, rejectNumber = 6)
            batchSize <= 3200   -> AqlPlan(batchSize, sampleSize = 125, acceptNumber = 7, rejectNumber = 8)
            batchSize <= 10000  -> AqlPlan(batchSize, sampleSize = 200, acceptNumber = 10, rejectNumber = 11)
            batchSize <= 35000  -> AqlPlan(batchSize, sampleSize = 315, acceptNumber = 14, rejectNumber = 15)
            batchSize <= 150000 -> AqlPlan(batchSize, sampleSize = 500, acceptNumber = 21, rejectNumber = 22)
            else                -> AqlPlan(batchSize, sampleSize = 800, acceptNumber = 21, rejectNumber = 22)
        }
    }

    /**
     * Evaluates the current scan progress against the AQL plan.
     * Call this after every scan to update the batch verdict.
     */
    fun evaluate(plan: AqlPlan, scanned: Int, rejects: Int): AqlResult {
        val isComplete = scanned >= plan.sampleSize

        val verdict = when {
            !isComplete                         -> ShipmentVerdict.SCANNING
            rejects <= plan.acceptNumber        -> ShipmentVerdict.PASS_SHIPMENT
            else                                -> ShipmentVerdict.HOLD_SHIPMENT
        }

        // Early termination: if rejects already exceed accept number before
        // sample is complete, we can call HOLD early
        val earlyHold = !isComplete && rejects >= plan.rejectNumber
        val finalVerdict = if (earlyHold) ShipmentVerdict.HOLD_SHIPMENT else verdict

        return AqlResult(
            scannedSoFar  = scanned,
            rejectsSoFar  = rejects,
            sampleSize    = plan.sampleSize,
            acceptNumber  = plan.acceptNumber,
            isComplete    = isComplete || earlyHold,
            verdict       = finalVerdict
        )
    }
}