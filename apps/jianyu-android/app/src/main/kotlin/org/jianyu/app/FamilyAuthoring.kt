package org.jianyu.app

import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Child
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole

internal fun FamilyMember.canAuthorCaregiverActions(): Boolean =
    role == MemberRole.CAREGIVER || role == MemberRole.GUARDIAN

internal fun FamilyState.resolveCaregiverAuthor(preferredMemberId: String?): FamilyMember {
    val eligible = members.filter(FamilyMember::canAuthorCaregiverActions)
    require(eligible.isNotEmpty()) { "家庭中没有可署名的家长或监护人" }
    return eligible.firstOrNull { it.id == preferredMemberId } ?: eligible.first()
}

internal fun FamilyState.resolveDiscoveryAuthor(
    child: Child,
    stage: LifecycleStage,
    preferredCaregiverId: String?,
): FamilyMember = if (stage == LifecycleStage.HAND_OVER) {
    members.firstOrNull {
        it.id == child.memberId && it.role == MemberRole.CHILD && it.subjectId == child.id
    } ?: throw IllegalStateException("放权阶段缺少孩子本人署名，无法保存这次表达")
} else {
    resolveCaregiverAuthor(preferredCaregiverId)
}
