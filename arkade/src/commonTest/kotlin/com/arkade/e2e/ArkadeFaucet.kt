package com.arkade.e2e

import com.arkade.core.ArkAddress
import com.arkade.core.contracts.ArkNoteContract
import com.arkade.core.intents.IntentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

object ArkadeFaucet {
    private const val NOTE_SATS: Long = 10_000_000L
    private const val MAX_TOP_UPS: Int = 4
    private const val MAX_SEND_ATTEMPTS: Int = 6

    private val renewalThreshold: Duration = 45.toDuration(DurationUnit.SECONDS)
    private val expirySafetyWindow = 20.toDuration(DurationUnit.SECONDS)
    private val renewalWaitTimeout: Duration = 90.toDuration(DurationUnit.SECONDS)
    private val inputHoldingStates: List<IntentState> =
        listOf(
            IntentState.WAITING_TO_SUBMIT,
            IntentState.WAITING_FOR_BATCH,
            IntentState.BATCH_IN_PROGRESS,
        )
    private val retryDelay = 2.toDuration(DurationUnit.SECONDS)
    private val topUpTimeout = 3.toDuration(DurationUnit.MINUTES)
    private val gate: Semaphore = Semaphore(1, 1)
    private var faucet: Deferred<Faucet>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun fund(
        address: String,
        amount: Long,
    ): String {
        require(amount > 0) { "Amount must be > 0" }
        val address = ArkAddress.decode(address)

        val faucet = getFaucet()

        return gate.withPermit {
            faucet.send(address, amount)
        }
    }

    private suspend fun getFaucet(): Faucet {
        faucet?.let { return it.await() }
        return gate
            .withPermit {
                faucet ?: scope.async { Faucet.start() }.also {
                    faucet = it
                }
            }.await()
    }

    class Faucet private constructor() {
        fun send(
            address: ArkAddress,
            amount: Long,
        ): String {
            TODO("Not yet implemented")
        }

        private suspend fun topUp() {
            val balance = checkBalance()

            val note = DockerHelper.createArkNote(NOTE_SATS)
            if (note.isEmpty()) {
                throw UnsupportedOperationException("arkd refused to issue a note for the faucet")
            }

            val contract = ArkNoteContract.parse("", note)

            try {
                withTimeout(topUpTimeout) {
                    while (checkBalance() <= balance) {
                        delay(retryDelay)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw TimeoutException(
                    "Faucet note of $NOTE_SATS sats did not become spendable within ${topUpTimeout.inWholeSeconds}s " +
                        "(balance still ${checkBalance()} sats). " +
                        "The note redemption batch did not land — check arkd's batch log.",
                ).apply { initCause(e) }
            }
        }

        private suspend fun checkBalance(): Long {
            TODO("Not yet implemented")
        }

        companion object {
            suspend fun start(): Faucet {
                val faucet = Faucet()
                faucet.topUp()
                return faucet
            }
        }
    }
}
