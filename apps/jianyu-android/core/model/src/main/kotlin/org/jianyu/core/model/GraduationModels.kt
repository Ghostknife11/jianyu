package org.jianyu.core.model

import kotlinx.serialization.Serializable

@Serializable
data class GraduationSubject(
    val subjectId: String,
    val memberId: String,
    val displayName: String,
    val birthYear: Int,
    val birthDate: String? = null,
)

@Serializable
data class GraduationAuthor(
    val memberId: String,
    val role: MemberRole,
)

@Serializable
data class GraduationArchive(
    val schema: String = "org.foe.graduation-archive/v2",
    val archiveId: String,
    val createdAt: String,
    val subject: GraduationSubject,
    val authors: List<GraduationAuthor>,
    val evidence: List<Evidence>,
    val hypotheses: List<Hypothesis>,
    val choices: List<FamilyChoice>,
    val events: List<FamilyEvent>,
    val tombstones: List<DeletionTombstone>,
    val humanReadableMarkdown: String,
)
