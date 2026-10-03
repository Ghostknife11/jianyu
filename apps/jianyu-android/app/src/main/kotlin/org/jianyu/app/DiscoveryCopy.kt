package org.jianyu.app

import org.jianyu.core.model.LifecycleStage

/** Shared wording for the composer and its fail-closed submission path. */
internal fun currentInterestClueMessage(stage: LifecycleStage): String =
    "这次没有适合找入口的主动线索。" +
        if (stage == LifecycleStage.HAND_OVER) {
            "你可以先留白，或再写一句。"
        } else {
            "可以先留白，或补充孩子的想法。"
        }

internal fun discoveryBlankInterestMessage(stage: LifecycleStage): String =
    if (stage == LifecycleStage.HAND_OVER) {
        "请先写下你这次主动想探索的事"
    } else {
        "请先写下孩子当前主动在意的真实线索"
    }

internal fun discoveryMissingAiMessage(stage: LifecycleStage): String =
    if (stage == LifecycleStage.HAND_OVER) {
        "这台共享设备还没有连接 AI；请家长设置，或先看离线演示"
    } else {
        "请先连接 AI 服务；也可以选择离线演示"
    }

internal const val discoveryMissingChildConfirmationMessage =
    "请先确认你同意这次寻找入口和相应的数据使用"
