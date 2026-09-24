package com.filestech.pass_tech.testing

import com.filestech.pass_tech.core.vault.Slot
import com.filestech.pass_tech.core.vault.SlotFiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val TIMEOUT_SECONDS = 10L

/**
 * Delegates to real files, and can hold the next read until the test opens the gate: freezes a vault
 * operation midway, on whatever thread runs it, so the test can act while it is in progress.
 */
class GatedFiles(private val delegate: SlotFiles) : SlotFiles by delegate {

    class Gate {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        /** Blocks until an operation is held at this gate. */
        fun awaitEntered() {
            check(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "no operation reached the gate" }
        }

        fun open() {
            release.countDown()
        }

        internal fun hold() {
            entered.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the gate was never opened" }
        }
    }

    private val armed = AtomicReference<Gate?>(null)

    /** The next [read], from any thread, waits at the returned gate. */
    fun arm(): Gate = Gate().also(armed::set)

    override fun read(slot: Slot): String? {
        armed.getAndSet(null)?.hold()
        return delegate.read(slot)
    }
}
