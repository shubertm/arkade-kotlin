package com.arkade.core.batches

/**
 * Callback interface for reacting to the lifecycle events of an in-progress batch.
 *
 * Implementations (see [BatchSession]) receive one callback per stage of a batch as it
 * progresses from finalization through VTXO tree signing.
 */
interface BatchEventHandler {
    /**
     * Called when the server requests finalization of the batch.
     *
     * Implementations are expected to build and sign any required forfeit transactions for
     * the inputs being spent, sign the commitment transaction for boarding inputs, and submit
     * the results back to the server.
     *
     * @param event The finalization event, carrying the (unsigned) commitment transaction.
     * @param connectors The connector tree nodes provided by the server, used to fund forfeit
     * transaction inputs.
     */
    suspend fun onBatchFinalization(
        event: BatchEvent.BatchFinalizationEvent,
        connectors: List<TxTreeNode>,
    )

    /**
     * Called when the batch has failed.
     */
    suspend fun onBatchFailed(event: BatchEvent.BatchFailedEvent)

    /**
     * Called when signing of the VTXO tree has started.
     */
    suspend fun onTreeSigningStarted(event: BatchEvent.TreeSigningStartedEvent)

    /**
     * Called when the VTXO tree signing nonces have been aggregated by the server.
     */
    suspend fun onTreeNoncesAggregated(event: BatchEvent.TreeNoncesAggregatedEvent)

    /**
     * Called when a VTXO tree transaction is received from the server.
     */
    suspend fun onTreeTx(event: BatchEvent.TreeTxEvent)

    /**
     * Called when a VTXO tree signature is received from the server.
     */
    suspend fun onTreeSignature(event: BatchEvent.TreeSignatureEvent) {}

    /**
     * Called when VTXO tree signing nonces are received from the server.
     */
    suspend fun onTreeNonces(event: BatchEvent.TreeNoncesEvent)
}
