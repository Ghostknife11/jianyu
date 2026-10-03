package org.jianyu.app

import kotlinx.coroutines.runBlocking
import org.jianyu.core.domain.VaultRepository
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConfirmedVaultSnapshotTest {
    @Test
    fun failedFirstWriteDoesNotPublishUnsavedDiscoveryContext() = runBlocking {
        val original = family("original")
        val draft = family("draft")
        val repository = RecordingRepository(failOnSave = 1)
        val snapshot = ConfirmedVaultSnapshot(original, repository)

        val failure = runCatching { snapshot.save(draft) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertSame(original, snapshot.family)
        assertTrue(snapshot.saveFailed)
    }

    @Test
    fun failedReceiptWriteKeepsOnlyTheEarlierConfirmedContext() = runBlocking {
        val original = family("original")
        val savedContext = family("context")
        val unsavedReceipt = family("receipt")
        val repository = RecordingRepository(failOnSave = 2)
        val snapshot = ConfirmedVaultSnapshot(original, repository)

        snapshot.save(savedContext)
        assertSame(savedContext, snapshot.family)
        val failure = runCatching { snapshot.save(unsavedReceipt) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertSame(savedContext, snapshot.family)
        assertTrue(snapshot.saveFailed)
    }

    @Test
    fun completedWritePublishesExactlyTheSavedState() = runBlocking {
        val original = family("original")
        val saved = family("saved")
        val snapshot = ConfirmedVaultSnapshot(original, RecordingRepository())

        snapshot.save(saved)

        assertSame(saved, snapshot.family)
        assertFalse(snapshot.saveFailed)
    }

    private fun family(name: String) = FamilyState(
        household = Household("synthetic-household", name, "2026-09-27T00:00:00Z"),
        members = emptyList(),
        children = emptyList(),
    )

    private class RecordingRepository(private val failOnSave: Int = -1) : VaultRepository {
        private var saves = 0

        override suspend fun load(): FamilyState? = null
        override suspend fun save(state: FamilyState) {
            saves++
            if (saves == failOnSave) throw IOException("synthetic write failure")
        }
        override suspend fun erase() = Unit
        override suspend fun exportEncrypted(): ByteArray = byteArrayOf()
        override suspend fun importEncrypted(bytes: ByteArray) = Unit
    }
}
