package com.arkade.core.services

import com.arkade.core.ArkServerInfo
import com.arkade.core.batches.BatchEvent
import com.arkade.core.batches.BatchSession
import com.arkade.core.coins.ArkCoin
import com.arkade.core.intents.ArkIntent
import com.arkade.core.intents.IntentState
import com.arkade.core.intents.RegisterIntentMessage
import com.arkade.core.tryPut
import com.arkade.core.tryRemove
import com.arkade.core.wallet.Wallet
import com.arkade.network.ArkadeClient
import com.arkade.repositories.contracts.ContractRepo
import com.arkade.repositories.intents.IntentRepo
import com.arkade.utils.Log
import com.arkade.utils.debug
import com.arkade.utils.error
import com.arkade.utils.info
import com.arkade.utils.warning
import fr.acinq.bitcoin.Crypto.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/**
 * Coordinates the lifecycle of batches on behalf of the wallet's registered intents.
 *
 * Consumes the server's batch event stream, matches each new batch to the currently
 * [activeIntents] it concerns, and creates/derives one [BatchSession] per matched intent to
 * finalize the batch and cooperatively sign the resulting VTXO tree.
 *
 * @property client Used to obtain the batch event stream and server info, and to confirm intent
 * registrations.
 * @property wallet Used to look up VTXOs/contracts for an intent's inputs and to sign
 * transactions on behalf of [BatchSession]s.
 * @property contractsRepo Used to resolve the [com.arkade.core.contracts.ArkContract] backing
 * each VTXO referenced by an intent.
 */
class BatchManagementService(
    private val client: ArkadeClient,
    private val wallet: Wallet,
    private val contractsRepo: ContractRepo,
    private val intentsRepo: IntentRepo,
) {
    private var streamId: String? = null
    private val activeIntents: MutableMap<String, ArkIntent> = mutableMapOf()
    private val activeBatchSessions: MutableMap<String, BatchSession> = mutableMapOf()
    private val batchIdToIntentIds = mutableMapOf<String, HashSet<String>>()

    private val batchMutex = Mutex()
    private val topicUpdateSemaphore = Semaphore(1)

    private val disposed: Boolean = false

    private var initialTopics: List<String> = emptyList()

    /**
     * Starts the batch event stream and processes incoming events.
     *
     * Loads active intents, subscribes to intent changes, and retries recoverable stream failures up to eight times.
     */
    suspend fun start() {
        loadActiveIntents()

        Log.debug(LOG_TAG, "Starting an event stream")

        streamId = null

        initialTopics = getAllTopics()

        intentsRepo.intentChanged = ::onIntentChanged

        client
            .getBatchEventStream(initialTopics)
            .retryWhen { cause, retries ->
                if (cause is CancellationException) throw cause

                Log.error(LOG_TAG, "Error in batch event stream, restarting in 5 seconds")
                delay(EVENT_STREAM_RETRY_DELAY)
                retries <= 7
            }.collect { event ->
                processEvent(event)
            }
    }

    fun dispose() {
        if (disposed) return
        intentsRepo.disposeOnIntentChanged()
    }

    /**
     * Called when an intent's state changes.
     * @param intent The intent whose state has changed.
     */
    private suspend fun onIntentChanged(intent: ArkIntent) {
        if (intent.id != null) {
            when (intent.state) {
                IntentState.WAITING_FOR_BATCH -> {
                    if (activeIntents.tryPut(intent.id, intent)) {
                        val topics = getTopicsForIntent(intent)
                        updateTopics(addTopics = topics)
                    }
                }
                IntentState.CANCELLED, IntentState.BATCH_FAILED, IntentState.BATCH_SUCCEEDED -> {
                    if (activeIntents.tryRemove(intent.id)) {
                        val topics = getTopicsForIntent(intent)
                        updateTopics(removeTopics = topics)
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Updates the stream's topic subscription.
     * @param addTopics The topics to add to the subscription.
     * @param removeTopics The topics to remove from the subscription.
     */
    private suspend fun updateTopics(
        addTopics: List<String> = emptyList(),
        removeTopics: List<String> = emptyList(),
    ) {
        topicUpdateSemaphore.withPermit {
            runCatching {
                if (streamId == null) {
                    Log.debug(LOG_TAG, "Stream not yet started, skipping topic update")
                    return
                }
                client.updateStreamTopics(streamId!!, addTopics, removeTopics)
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.warning(LOG_TAG, "Failed to update stream topics: $e")
            }
        }
    }

    /**
     * Generates a list of topics associated with an intent's VTXOs.
     * @param intent the intent for which to generate topics.
     * @return a list of topics.
     */
    private fun getTopicsForIntent(intent: ArkIntent): List<String> {
        val vtxoTopics = intent.vtxos.map { "${it.hash.value.toHex()}:${it.index}" }
        val cosignerTopics = extractCosignerKeys(intent.registerProofMessage)
        return vtxoTopics + cosignerTopics
    }

    /**
     * Extracts cosigner public keys from a registration proof message.
     *
     * @param registerProofMessage The serialized registration proof message.
     * @return The cosigner public keys, or an empty list if the message cannot be parsed.
     */
    private fun extractCosignerKeys(registerProofMessage: String): List<String> =
        try {
            val message = RegisterIntentMessage.fromString(registerProofMessage)
            message.cosignersPublicKeys
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Collects the distinct event-stream topics for all active intents.
     *
     * @return The unique topics associated with active intents.
     */
    private fun getAllTopics(): List<String> =
        activeIntents.values
            .flatMap { intent ->
                getTopicsForIntent(intent)
            }.distinct()

    /**
     * Processes a batch stream event and updates session state or stream subscriptions as needed.
     *
     * Stream-start events record the stream ID and reconcile subscribed topics with the active intents.
     * Batch-start events initialize sessions for matching intents. Other events are forwarded to their
     * associated batch sessions.
     */
    private suspend fun processEvent(event: BatchEvent) {
        when (event) {
            is BatchEvent.StreamStartedEvent -> {
                streamId = event.id
                Log.info(LOG_TAG, "Batch stream started with id: $streamId")

                val reconciledAddedTopics = getAllTopics()
                val reconciledRemovedTopics =
                    initialTopics.filter { topic ->
                        !reconciledAddedTopics.contains(topic)
                    }
                if (reconciledAddedTopics.isNotEmpty() || reconciledRemovedTopics.isNotEmpty()) {
                    updateTopics(reconciledAddedTopics, reconciledRemovedTopics)
                }
            }
            is BatchEvent.BatchStartedEvent -> {
                handleBatchStartedForAllIntents(event)
            }
            else -> {
                handleBatchEvent(event)
            }
        }
    }

    /**
     * Selects the [activeIntents] concerned by [event] and creates a [BatchSession] for each.
     *
     * An intent is selected when the SHA-256 hash of its id is present in
     * [BatchEvent.BatchStartedEvent.intentIdHashes]. Intents that already have a session in
     * [activeBatchSessions] are skipped. For each remaining intent, [setupBatchSession] is
     * called; failures are logged and do not prevent other intents from being set up.
     *
     * @param event The batch-started event carrying the hashed ids of the intents included in
     * the new batch.
     */
    private suspend fun handleBatchStartedForAllIntents(event: BatchEvent.BatchStartedEvent) {
        val intentHashMap =
            activeIntents.mapKeys { entry ->
                sha256(entry.key.encodeToByteArray()).toHexString()
            }

        val selectedIntents =
            try {
                event.intentIdHashes.mapNotNull { intentHash ->
                    intentHashMap[intentHash]
                }
            } catch (e: Exception) {
                throw e
            }

        if (selectedIntents.isEmpty()) return

        val walletIds =
            selectedIntents
                .map { intent ->
                    intent.walletId
                }.distinct()
                .toTypedArray()

        if (walletIds.isEmpty()) return

        val serverInfo = client.getInfo()

        selectedIntents.forEach { intent ->
            val intentId = intent.id
            if (activeBatchSessions.containsKey(intentId)) {
                return@forEach
            }
            try {
                setupBatchSession(intent, serverInfo, event)
            } catch (e: Exception) {
                Log.warning(LOG_TAG, "Failed to handle batch started event for intent $intentId: $e")
            }
        }
    }

    /**
     * Builds and starts a [BatchSession] for [intent]'s VTXOs, then confirms the intent's
     * registration with the server.
     *
     * Loads [intent]'s VTXOs (including already-spent ones, so unrolled/swept coins are still
     * resolvable) and their backing contracts, converts each to an [com.arkade.core.coins.ArkCoin],
     * and creates and [BatchSession.init]-ializes a session for them. The session is registered
     * in [activeBatchSessions] and its batch id is associated with [intent]'s id in
     * [batchIdToIntentIds] before the registration is confirmed via
     * [ArkadeClient.confirmIntentRegistration] and the intent is persisted via
     * [Wallet.saveIntent].
     *
     * @param intent The intent whose inputs are being registered in this batch.
     * @param serverInfo The server info used to resolve each VTXO's script to its contract.
     * @param event The batch-started event that triggered this session.
     * @throws IllegalArgumentException if a VTXO or its backing contract cannot be found in
     * storage.
     */
    private suspend fun setupBatchSession(
        intent: ArkIntent,
        serverInfo: ArkServerInfo,
        event: BatchEvent.BatchStartedEvent,
    ) {
        val intentId = requireNotNull(intent.id)
        try {
            val vtxos =
                wallet.getVtxos(
                    outpoints = intent.vtxos.toTypedArray(),
                    includeSpent = true,
                )

            val vtxosScripts =
                vtxos
                    .map { vtxo ->
                        vtxo.script
                    }.toHashSet()
                    .toTypedArray()

            val contracts =
                contractsRepo.getAll(
                    intent.walletId,
                    vtxosScripts,
                )

            val spendableCoins = mutableListOf<ArkCoin>()
            intent.vtxos.forEach { outpoint ->
                val vtxo =
                    vtxos.find { vtxo ->
                        vtxo.outpoint == outpoint
                    }

                if (vtxo == null) {
                    Log.error(LOG_TAG, "VTXO $outpoint not found in storage for intent $intentId")
                    throw IllegalArgumentException("VTXO $outpoint not found in storage for intent $intentId")
                }

                val contract =
                    contracts.find { contract ->
                        contract.getScriptPubKey(serverInfo.network) == vtxo.script
                    }
                if (contract == null) {
                    Log.error(LOG_TAG, "Contract for VTXO $outpoint not found in storage for intent $intentId")
                    throw IllegalArgumentException("Contract for VTXO $outpoint not found in storage for intent $intentId")
                }

                if (vtxo.isSpent) return@forEach

                spendableCoins.add(contract.toArkCoin(vtxo))
            }
            val batchSession =
                BatchSession(
                    client,
                    wallet,
                    intent,
                    spendableCoins,
                    event,
                )

            batchSession.init()

            batchMutex.withLock {
                val batchIntentIds =
                    batchIdToIntentIds.getOrPut(event.id) {
                        hashSetOf()
                    }
                batchIntentIds.add(intentId)
                activeBatchSessions[intentId] = batchSession
            }

            try {
                client.confirmIntentRegistration(intentId)
                wallet.saveIntent(intent)
            } catch (e: Exception) {
                Log.error(LOG_TAG, "Failed to confirm intent registration for intent $intentId: $e")
                throw e
            }
        } catch (e: Exception) {
            Log.error(LOG_TAG, "Failed to setup batch session for intent $intentId: $e")
            throw e
        }
    }

    /**
     * Dispatches a non-startup [event] to the [BatchSession]s registered for its batch, via
     * [BatchEvent.getBatchId] and [batchIdToIntentIds].
     *
     * If no intent ids are known for the event's batch id, the event is logged and dropped.
     *
     * @param event The batch event to forward to its associated session(s).
     */
    private suspend fun handleBatchEvent(event: BatchEvent) {
        val batchId = event.getBatchId()
        val intentIds =
            batchMutex.withLock {
                batchIdToIntentIds[batchId]?.toSet()
            }
        if (intentIds == null) {
            Log.warning(LOG_TAG, "No intent ids found for batch $batchId")
            return
        }
        for (id in intentIds) {
            var isComplete = false
            batchMutex.withLock {
                val batchSession = activeBatchSessions[id] ?: continue
                isComplete = batchSession.processEvent(event)
            }

            if (activeIntents[id] == null) continue

            if (isComplete) {
                cleanUpBatchSession(id, batchId!!)
            }
        }
    }

    /**
     * Extracts the batch id carried by this event, or `null` for event types not tied to a
     * specific batch (e.g. [BatchEvent.StreamStartedEvent], [BatchEvent.HeartbeatEvent]).
     */
    private fun BatchEvent.getBatchId(): String? =
        when (this) {
            is BatchEvent.BatchFinalizedEvent -> id
            is BatchEvent.BatchFinalizationEvent -> id
            is BatchEvent.BatchFailedEvent -> id
            is BatchEvent.TreeTxEvent -> id
            is BatchEvent.TreeNoncesEvent -> id
            is BatchEvent.TreeSignatureEvent -> id
            is BatchEvent.TreeNoncesAggregatedEvent -> id
            is BatchEvent.TreeSigningStartedEvent -> id
            else -> null
        }

    /**
     * Loads intents that may require active batch processing and restores the in-memory intent state.
     *
     * Duplicate VTXO claims are resolved by retaining the newest non-overlapping intents and
     * cancelling the others. On the first run, orphaned batch-in-progress intents are marked as
     * succeeded when they have a commitment transaction or cancelled when they do not.
     *
     * @param isFirstRun Whether to perform startup recovery for orphaned batch-in-progress intents.
     */
    private suspend fun loadActiveIntents(isFirstRun: Boolean = true) {
        val activeIntentStates = arrayOf(IntentState.WAITING_TO_SUBMIT, IntentState.WAITING_FOR_BATCH, IntentState.BATCH_IN_PROGRESS)
        var allActiveIntents = wallet.getIntents(activeIntentStates)

        val vtxoToIntents: MutableMap<String, MutableList<ArkIntent>> = mutableMapOf()

        allActiveIntents.forEach { intent ->
            intent.vtxos.forEach { vtxo ->
                val key = "${vtxo.hash.value.toHex()}:${vtxo.index}"
                if (!vtxoToIntents.containsKey(key)) {
                    vtxoToIntents[key] = emptyList<ArkIntent>().toMutableList()
                }
                vtxoToIntents[key]?.add(intent)
            }
        }

        val duplicateVtxos = vtxoToIntents.filter { entry -> entry.value.size > 1 }

        if (duplicateVtxos.any()) {
            Log.warning(LOG_TAG, "Found ${duplicateVtxos.size} VTXOs with multiple intents - cleaning up duplicates")

            val allLatestIntents =
                duplicateVtxos.values
                    .map { intents ->
                        intents.maxBy { intent -> intent.updatedAt }
                    }.sortedByDescending { intent -> intent.updatedAt }

            val intentsToKeep: HashSet<ArkIntent> = hashSetOf()

            allLatestIntents.forEach { intent ->
                if (intentsToKeep.isNotEmpty()) {
                    val isAlreadyKept =
                        intentsToKeep.any { keptIntent -> intent.vtxos.any { vtxo -> keptIntent.vtxos.contains(vtxo) } }
                    if (isAlreadyKept) return@forEach
                }
                intentsToKeep.add(intent)
            }

            val intentsToCancel = (duplicateVtxos.values.flatten().toHashSet() - intentsToKeep).map { intent -> intent.txId }.toHashSet()

            intentsToCancel.forEach { intentTxId ->
                val intent = allActiveIntents.first { intent -> intent.txId == intentTxId }
                Log.warning(
                    LOG_TAG,
                    "Cancelling duplicate intent $intentTxId (Intent: ${intent.id}) - VTXO already claimed by another intent",
                )

                val cancelledIntent =
                    intent.copy(
                        state = IntentState.CANCELLED,
                        cancellationReason = "Duplicate intent for same VTXO -cleaned up on startup",
                        updatedAt = Clock.System.now().toEpochMilliseconds(),
                    )

                intentsRepo.save(cancelledIntent)
            }

            allActiveIntents =
                allActiveIntents.filter { intent ->
                    !intentsToCancel.contains(intent.txId)
                }
        }

        allActiveIntents.forEach { intent ->
            if (intent.id == null) {
                Log.debug(LOG_TAG, "Skipping intent with null IntentId (IntentTxId: ${intent.txId})")
                return@forEach
            }

            if (isFirstRun && intent.state == IntentState.BATCH_IN_PROGRESS) {
                if (intent.commitmentTxId != null) {
                    Log.info(
                        LOG_TAG,
                        "Orphaned BatchInProgress intent ${intent.id} has commitment tx ${intent.commitmentTxId} - marking as succeeded",
                    )
                    val succeededIntent =
                        intent.copy(
                            state = IntentState.BATCH_SUCCEEDED,
                            cancellationReason = null,
                            updatedAt = Clock.System.now().toEpochMilliseconds(),
                        )
                    intentsRepo.save(succeededIntent)
                    return@forEach
                }

                Log.warning(LOG_TAG, "Cancelling orphaned BatchInProgress intent ${intent.id} on startup (no active batch session)")

                val cancelledIntent =
                    intent.copy(
                        state = IntentState.CANCELLED,
                        cancellationReason = "Orphaned BatchInProgress intent - no active batch session after restart",
                        updatedAt = Clock.System.now().toEpochMilliseconds(),
                    )
                intentsRepo.save(cancelledIntent)
                return@forEach
            }

            Log.debug(LOG_TAG, "Loaded active intent ${intent.id} in state ${intent.state}")

            activeIntents[intent.id] = intent
        }
    }

    /**
     * Removes a completed intent session and updates its batch association.
     *
     * @param intentId The identifier of the intent whose session is being removed.
     * @param batchId The identifier of the batch associated with the intent.
     */
    private suspend fun cleanUpBatchSession(
        intentId: String,
        batchId: String,
    ) {
        batchMutex.withLock {
            activeBatchSessions.tryRemove(intentId)
            val intentIds = batchIdToIntentIds[batchId]
            if (!intentIds.isNullOrEmpty()) {
                intentIds.remove(intentId)

                if (intentIds.isEmpty()) {
                    batchIdToIntentIds.tryRemove(batchId)
                }
            }
        }
    }

    companion object {
        private const val LOG_TAG = "BatchManagementService"
        private const val EVENT_STREAM_RETRY_DELAY: Long = 5000
    }
}
