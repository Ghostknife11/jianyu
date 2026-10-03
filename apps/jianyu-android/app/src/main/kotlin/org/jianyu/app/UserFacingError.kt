package org.jianyu.app

import java.io.IOException
import java.security.GeneralSecurityException

internal enum class ErrorContext { STARTUP, ACTION, LOCAL_SAVE }

private val directlyActionableMessages = setOf(
    "家庭中没有可署名的家长或监护人",
    "需要由本人确认这次成年交接导出",
    "需要由本人确认这次成年交接留存选择",
    "请填写家长或监护人的称呼",
    "请填写家庭称呼",
    "请填写你的称呼",
    "请填写孩子的称呼",
    discoveryBlankInterestMessage(org.jianyu.core.model.LifecycleStage.CO_SELECT),
    discoveryBlankInterestMessage(org.jianyu.core.model.LifecycleStage.HAND_OVER),
    currentInterestClueMessage(org.jianyu.core.model.LifecycleStage.CO_SELECT),
    currentInterestClueMessage(org.jianyu.core.model.LifecycleStage.HAND_OVER),
    "“只留给自己”只适用于放权阶段",
    discoveryMissingChildConfirmationMessage,
    discoveryMissingAiMessage(org.jianyu.core.model.LifecycleStage.CO_SELECT),
    discoveryMissingAiMessage(org.jianyu.core.model.LifecycleStage.HAND_OVER),
    "请先确认本次发送给 AI 的最小上下文",
    "请由本人确认删除这条选择",
    "请选择有效的出生日期",
    "当前版本支持 4 岁起的家庭成员；16 岁起进入成年交接",
    "当前版本支持 4 岁起的家庭成员；现在不寻找入口或新建观察",
    "请填写科目",
    "请填写考试或测验名称",
    "请填写有效得分",
    "请填写有效满分",
    "满分必须大于 0",
    "得分必须在 0 到满分之间",
    "请选择考试或测验日期",
    "共玩阶段不记录考试分数；可在机会发现中补充老师的观察",
    "日期必须在孩子出生后且不晚于今天",
    "请填写有效班级平均分",
    "班级平均分必须在 0 到满分之间",
    "请填写有效百分位",
    "百分位必须在 0 到 100 之间",
    "请填写 AI 服务名称",
    "请填写模型名称",
    "请填写 API 密钥",
    "AI 地址必须是有效的 HTTPS 地址",
    "AI 地址不能包含账号、查询参数或片段",
    "请填写世界信息服务名称",
    "世界信息地址必须是有效的 HTTPS 地址",
    "世界信息地址不能包含账号、查询参数或片段",
    "正在保存连接，请稍后再删除家庭资料",
    "尚未连接加密文件夹",
    "无法打开导出位置",
    "无法读取恢复包",
    "恢复包超过大小限制",
    "恢复包大小无效",
    "恢复包格式无效",
    "不支持的恢复包版本",
    "恢复码格式无效",
    "恢复码错误或恢复包已被修改",
    "恢复包中的家庭数据无效",
    "本机资料已变化，请重新检查恢复包",
    "请重新检查恢复包",
)

/** Prevents protocol errors, server response bodies, paths, and English internals from entering product UI. */
internal fun Throwable.asUserFacingMessage(context: ErrorContext = ErrorContext.ACTION): String {
    if (context == ErrorContext.STARTUP) {
        return "本机家庭资料没有正常打开。为保护现有内容，暂时不能新建家庭。请勿卸载或清除应用；可以重试打开。"
    }
    val chain = generateSequence(this) { it.cause }.take(6).toList()
    val safe = chain.asSequence().mapNotNull(Throwable::message).firstOrNull(directlyActionableMessages::contains)
    if (safe != null) return safe

    val messages = chain.mapNotNull(Throwable::message)
    return when {
        context == ErrorContext.LOCAL_SAVE ->
            "本机保存没有完成。请检查设备存储状态后重试。"
        chain.any { it is SecurityException } ->
            "系统没有授予所需的访问权限。请重新选择位置后再试。"
        chain.any { it is GeneralSecurityException } ->
            "加密资料没有通过校验。请确认文件与恢复码是否匹配。"
        chain.any { it is IOException } ->
            "网络或文件读取没有完成。请检查连接与所选位置后再试。"
        messages.any { it.startsWith("AI 服务返回 ") } ->
            "AI 服务没有完成这次请求。请检查服务地址、模型和账户状态后再试。"
        messages.any { it.startsWith("AI 响应过大") || it.startsWith("AI 返回的候选") } ->
            "AI 返回的内容无法安全读取。请换一个模型或稍后再试。"
        messages.any { it.startsWith("世界信息服务返回 ") || it.contains("World Brief") } ->
            "世界信息服务本次没有完成。其他本地资料不会受影响。"
        else ->
            "这次操作没有完成，资料不会被当作成功写入。请稍后再试。"
    }
}

/** A post-request vault failure cannot undo data that an external service may already have received. */
internal fun postRequestLocalSaveMessage(contextPersisted: Boolean): String =
    "这次可能已联系外部服务，但本机没有保存最终结果。" +
        if (contextPersisted) {
            "输入的线索已保存在本机；再次寻找可能重复发送。请先检查本机存储。"
        } else {
            "输入的线索没有保存在本机；再次寻找可能重复发送。请先检查本机存储。"
        }
