package org.jianyu.app

import org.jianyu.core.domain.VaultRepository
import org.jianyu.core.model.FamilyState

/** The UI may show only the latest family state whose vault write actually completed. */
internal class ConfirmedVaultSnapshot(
    initial: FamilyState,
    private val repository: VaultRepository,
) {
    var family: FamilyState = initial
        private set

    var saveFailed: Boolean = false
        private set

    suspend fun save(next: FamilyState) {
        try {
            repository.save(next)
        } catch (error: Throwable) {
            saveFailed = true
            throw error
        }
        family = next
        saveFailed = false
    }
}
