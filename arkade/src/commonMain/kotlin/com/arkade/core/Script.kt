package com.arkade.core

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OP_CHECKSEQUENCEVERIFY
import fr.acinq.bitcoin.OP_CHECKSIG
import fr.acinq.bitcoin.OP_CHECKSIGVERIFY
import fr.acinq.bitcoin.OP_DROP
import fr.acinq.bitcoin.OP_PUSHDATA
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.XonlyPublicKey

/**
 * @param serverPubKey is the Arkade operator's x-only public key
 * @param ownerPubKey is the `VTXO` owner's x-only public key
 * @return a multisig script for collaborative exit
 */
fun multisigScript(
    serverPubKey: XonlyPublicKey,
    ownerPubKey: XonlyPublicKey,
): ByteArray {
    val asm =
        listOf(
            OP_PUSHDATA(serverPubKey),
            OP_CHECKSIGVERIFY,
            OP_PUSHDATA(ownerPubKey),
            OP_CHECKSIG,
        )
    return Script.write(asm)
}

/**
 * @param lockTime is the wait time for the exit after on-chain confirmation
 * @param ownerPubKey is the x-only public key for the `VTXO` owner
 * @return a `CSV` script for unilateral exit
 * @throws IllegalArgumentException if the [lockTime] is not with the range [[0, 65535]]
 */
fun csvSigScript(
    lockTime: Long,
    ownerPubKey: XonlyPublicKey,
): ByteArray {
    require(lockTime in 0..0xFFFFL) { "Invalid lock time" }
    val asm =
        listOf(
            OP_PUSHDATA(Script.encodeNumber(lockTime)),
            OP_CHECKSEQUENCEVERIFY,
            OP_DROP,
            OP_PUSHDATA(ownerPubKey),
            OP_CHECKSIG,
        )
    return Script.write(asm)
}

/**
 * Builds a Taproot [ScriptTree] from a list of serialized tap leaf scripts.
 *
 * The resulting tree has the following structure:
 * - **1 leaf**: returns a single [ScriptTree.Leaf].
 * - **Even count**: pairs adjacent leaves into [ScriptTree.Branch] nodes, then reduces
 *   remaining branches pairwise until a single root branch remains.
 * - **Odd count**: the trailing unpaired leaf is merged into the last branch of the
 *   first pairing pass before the reduction loop.
 *
 * All leaf scripts are wrapped in a [ScriptTree.Leaf] with version `0`.
 *
 * @param leaves the non-empty list of raw serialized tap leaf scripts.
 * @return the root [ScriptTree] node for the given scripts.
 * @throws IllegalArgumentException if [leaves] is empty.
 * @throws IllegalStateException if an internal invariant is violated (should not occur).
 */
fun buildScriptTree(leaves: List<ByteArray>): ScriptTree {
    require(leaves.isNotEmpty()) { "At least one leaf is required" }
    if (leaves.size == 1) {
        return ScriptTree.Leaf(ByteVector(leaves.single()), 0)
    }

    val leaves = leaves.map { ScriptTree.Leaf(ByteVector(it), 0) }
    val branches = mutableListOf<ScriptTree.Branch>()

    for (leafIndex in leaves.indices step 2) {
        if (leafIndex == leaves.size - 1) {
            val lastBranch = branches.removeLastOrNull()
            if (lastBranch != null) {
                branches.add(ScriptTree.Branch(lastBranch, leaves[leafIndex]))
                continue
            } else {
                throw IllegalStateException("This should not happen")
            }
        }

        val branch = ScriptTree.Branch(leaves[leafIndex], leaves[leafIndex + 1])
        branches.add(branch)
    }

    while (branches.isNotEmpty()) {
        if (branches.size == 1) {
            return branches.single()
        }

        val right = branches.removeAt(0)
        val left = branches.removeAt(0)
        val branch = ScriptTree.Branch(left, right)
        branches.add(branch)
    }
    throw IllegalStateException("This should never happen")
}
