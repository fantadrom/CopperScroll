package com.fantadrom.copperscroll.storage.crypto

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CryptoCancellation {
    private val cancelled = AtomicBoolean()
    fun cancel() { cancelled.set(true) }
    internal fun check() { requireCrypto(!cancelled.get(), CryptoFailure.Cancelled) }
}

internal fun interface ArgonDerivation { fun derive(secret: ByteArray, envelope: Envelope): ByteArray }
internal object BcArgon : ArgonDerivation {
    override fun derive(secret: ByteArray, envelope: Envelope) = CryptoPrimitives.argon(secret,
        envelope.salt.copy(), envelope.parameters.memoryKiB, envelope.parameters.iterations, envelope.parameters.parallelism)
}

/** One process-wide worker, ZERO queued submits. Blocking API: callers must dispatch away from UI.
 * Cancellation requests a stop, never interrupts BC or releases the permit before actual return.
 */
internal object KdfWorker {
    private val occupied = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CopperScroll-KDF").apply { isDaemon = true }
    }
    internal class Submit(private val cancel: CryptoCancellation) {
        private var calls = 0
        fun check() = cancel.check()
        fun derive(secret: ByteArray, envelope: Envelope, engine: ArgonDerivation): ByteArray {
            envelope.validate(); requireCrypto(secret.size in 1..1024)
            check(); requireCrypto(++calls <= 2, CryptoFailure.ResourceLimit)
            val output = engine.derive(secret, envelope)
            try { check(); return output } catch (e: Throwable) { output.fill(0); throw e }
        }
    }
    fun <T : AutoCloseable> run(cancel: CryptoCancellation, block: (Submit) -> T): T {
        cancel.check()
        // Allocate coordination state before taking admission: allocation failure must not strand Busy.
        val result = CompletableFuture<T>()
        val finished = CountDownLatch(1)
        requireCrypto(occupied.compareAndSet(false, true), CryptoFailure.Busy)
        try {
            executor.execute {
                try {
                    val submit = Submit(cancel); submit.check()
                    val value = block(submit)
                    try { submit.check() } catch (e: Throwable) { value.close(); throw e }
                    result.complete(value)
                } catch (e: Throwable) { result.completeExceptionally(e) }
                finally { occupied.set(false); finished.countDown() }
            }
        } catch (_: OutOfMemoryError) {
            occupied.set(false); throw CryptoException(CryptoFailure.ResourceLimit)
        } catch (_: RuntimeException) { occupied.set(false); throw CryptoException(CryptoFailure.BackendUnavailable) }
        var interrupted = false
        try {
            while (true) {
                try {
                    finished.await()
                    val value = result.get()
                    try { cancel.check() } catch (e: Throwable) { value.close(); throw e }
                    return value
                } catch (_: InterruptedException) {
                    interrupted = true; cancel.cancel() // Wait for real quiescence before returning/cleanup.
                } catch (e: ExecutionException) {
                    val cause = e.cause
                    if (cause is CryptoException) throw cause
                    if (cause is OutOfMemoryError) throw CryptoException(CryptoFailure.ResourceLimit)
                    throw CryptoException(CryptoFailure.BackendUnavailable)
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
