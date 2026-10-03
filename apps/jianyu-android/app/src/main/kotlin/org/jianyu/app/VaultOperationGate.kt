package org.jianyu.app

import kotlinx.coroutines.sync.Mutex

/** Serializes one App instance's vault read-modify-write operations, including UI projection. */
internal class VaultOperationGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
