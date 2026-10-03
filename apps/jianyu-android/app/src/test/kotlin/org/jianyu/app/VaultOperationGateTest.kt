package org.jianyu.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultOperationGateTest {
    @Test
    fun `later vault operation cannot read stale state before first write completes`() = runBlocking {
        val gate = VaultOperationGate()
        val firstHasRead = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var state = 0
        val first = async {
            gate.run {
                val current = state
                firstHasRead.complete(Unit)
                releaseFirst.await()
                state = current + 1
            }
        }
        firstHasRead.await()
        val secondEntered = CompletableDeferred<Unit>()
        val second = async {
            gate.run {
                secondEntered.complete(Unit)
                state += 1
            }
        }
        yield()
        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.isCompleted)
        assertEquals(2, state)
    }
}
