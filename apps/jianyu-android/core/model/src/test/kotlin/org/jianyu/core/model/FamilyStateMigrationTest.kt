package org.jianyu.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyStateMigrationTest {
    @Test
    fun `v2 state migrates to current schema without losing events`() {
        val old = FamilyState(
            schema = "org.jianyu.family-vault/v2",
            household = Household("h", "合成家庭", "2026-01-01T00:00:00Z"),
            members = listOf(FamilyMember("m", "合成家长", MemberRole.CAREGIVER, createdAt = "2026-01-01T00:00:00Z")),
            children = emptyList(),
            events = emptyList(),
        )
        val migrated = migrateFamilyState(old)
        assertEquals(CURRENT_FAMILY_STATE_SCHEMA, migrated.schema)
        assertEquals(old.household, migrated.household)
    }

    @Test
    fun `v3 state gains empty sync and tombstone state deterministically`() {
        val old = FamilyState(
            schema = "org.jianyu.family-vault/v3",
            household = Household("h", "合成家庭", "2026-01-01T00:00:00Z"),
            members = emptyList(),
            children = emptyList(),
        )

        val migrated = migrateFamilyState(old)

        assertEquals(CURRENT_FAMILY_STATE_SCHEMA, migrated.schema)
        assertEquals(emptyList<DeletionTombstone>(), migrated.tombstones)
        assertEquals(FamilySyncState(), migrated.syncState)
        assertEquals(migrated, migrateFamilyState(migrated))
    }

    @Test
    fun `v4 year-only child migrates without inventing a birthday`() {
        val old = FamilyState(
            schema = "org.jianyu.family-vault/v4",
            household = Household("h", "合成家庭", "2026-01-01T00:00:00Z"),
            members = listOf(FamilyMember("member-child", "孩子", MemberRole.CHILD, "child", "2026-01-01T00:00:00Z")),
            children = listOf(Child("child", "member-child", "孩子", 2013, "2026-01-01T00:00:00Z")),
        )

        val migrated = migrateFamilyState(old)

        assertEquals(CURRENT_FAMILY_STATE_SCHEMA, migrated.schema)
        assertEquals(2013, migrated.children.single().birthYear)
        assertEquals(null, migrated.children.single().birthDate)
    }
}
