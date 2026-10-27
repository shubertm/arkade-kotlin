package com.arkade.core.bitcoin

import com.arkade.utils.Log
import com.arkade.utils.warning
import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.utils.getOrElse

/** Returns whether this lock value is interpreted as a block height. */
fun Long.isHeightLock() = this < 500_000_000

/** Returns whether this lock value is interpreted as a Unix timestamp. */
fun Long.isTimeLock() = !isHeightLock()

internal fun Psbt.finalizeWitnessInputs(inputIndexes: List<Int>): Psbt {
    var finalizedPsbt = this
    inputIndexes.forEach { index ->
        val input = getInput(index) ?: return@forEach
        val signature = input.taprootKeySignature ?: return@forEach
        val witness = ScriptWitness(listOf(signature))
        finalizedPsbt =
            finalizedPsbt
                .finalizeWitnessInput(index, witness)
                .getOrElse {
                    Log.warning("PSBT", "Failed to finalize witness input, skipping")
                    return@forEach
                }
    }
    return finalizedPsbt
}

internal fun Psbt.getFullySignedTx(): Transaction =
    extract().getOrElse {
        throw IllegalStateException("Cannot extract a fully signed tx, $it")
    }
