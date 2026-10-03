package org.jianyu.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncSettingsTest {
    @Test
    fun `new folder settings contain a valid distinct transport identity and encrypted-data key`() {
        val first = FolderSyncSettings.create("content://documents/tree/primary%3AJianyu", "我的文件夹")
        val second = FolderSyncSettings.create("content://documents/tree/primary%3AJianyu", "我的文件夹")

        first.validate()
        assertFalse(first.deviceId == second.deviceId)
        assertFalse(first.keyId == second.keyId)
        assertEquals(first.keyId, first.householdKey().keyId)
        assertFalse(first.toString().contains(first.treeUri))
        assertFalse(first.toString().contains(first.keyMaterial))
    }

    @Test
    fun `folder settings reject non-content URI and malformed key`() {
        val valid = FolderSyncSettings.create("content://documents/tree/primary%3AJianyu", "我的文件夹")
        assertFails { valid.copy(treeUri = "https://example.com/folder").validate() }
        assertFails { valid.copy(keyMaterial = "not-a-key").validate() }
        assertFails { valid.copy(displayName = "").validate() }
    }

    @Test
    fun `moving a connection can preserve its device identity and key`() {
        val current = FolderSyncSettings.create("content://documents/tree/primary%3AJianyu", "旧文件夹")
        val moved = current.copy(
            treeUri = "content://documents/tree/primary%3AJianyu-new",
            displayName = "新文件夹",
        )

        moved.validate()
        assertEquals(current.deviceId, moved.deviceId)
        assertEquals(current.keyId, moved.keyId)
        assertEquals(current.keyMaterial, moved.keyMaterial)
    }

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected validation failure", failed)
    }
}
