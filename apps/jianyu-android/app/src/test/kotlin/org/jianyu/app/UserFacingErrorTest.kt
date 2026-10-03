package org.jianyu.app

import java.io.IOException
import org.jianyu.core.model.LifecycleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserFacingErrorTest {
    @Test
    fun `stage aware discovery copy survives the safe error boundary`() {
        listOf(LifecycleStage.CO_SELECT, LifecycleStage.HAND_OVER).forEach { stage ->
            listOf(
                discoveryBlankInterestMessage(stage),
                currentInterestClueMessage(stage),
                discoveryMissingAiMessage(stage),
            ).forEach { message ->
                assertEquals(message, IllegalArgumentException(message).asUserFacingMessage())
            }
        }
        assertEquals(
            discoveryMissingChildConfirmationMessage,
            IllegalStateException(discoveryMissingChildConfirmationMessage).asUserFacingMessage(),
        )
    }

    @Test
    fun `actionable validation remains specific`() {
        assertEquals(
            "得分必须在 0 到满分之间",
            IllegalArgumentException("得分必须在 0 到满分之间").asUserFacingMessage(),
        )
    }

    @Test
    fun `source connection errors keep the same Chinese terms as their forms`() {
        listOf(
            "请填写 API 密钥",
            "AI 地址必须是有效的 HTTPS 地址",
            "AI 地址不能包含账号、查询参数或片段",
            "世界信息地址必须是有效的 HTTPS 地址",
            "世界信息地址不能包含账号、查询参数或片段",
            "正在保存连接，请稍后再删除家庭资料",
        ).forEach { message ->
            assertEquals(message, IllegalArgumentException(message).asUserFacingMessage())
        }
    }

    @Test
    fun `AI server body never reaches product UI`() {
        val result = IllegalStateException("AI 服务返回 401：{\"secret\":\"raw-provider-body\"}")
            .asUserFacingMessage()

        assertEquals("AI 服务没有完成这次请求。请检查服务地址、模型和账户状态后再试。", result)
        assertFalse(result.contains("raw-provider-body"))
    }

    @Test
    fun `technical sync failures use one product-language fallback`() {
        assertEquals(
            "这次操作没有完成，资料不会被当作成功写入。请稍后再试。",
            IllegalArgumentException("Sync frame sequence gap, fork, or rollback").asUserFacingMessage(),
        )
    }

    @Test
    fun `wrapped IO failure remains actionable`() {
        assertEquals(
            "网络或文件读取没有完成。请检查连接与所选位置后再试。",
            IllegalStateException("wrapper", IOException("socket reset")).asUserFacingMessage(),
        )
    }

    @Test
    fun `local form save failure does not invent a network or chosen file location`() {
        assertEquals(
            "本机保存没有完成。请检查设备存储状态后重试。",
            IOException("Is a directory").asUserFacingMessage(ErrorContext.LOCAL_SAVE),
        )
    }

    @Test
    fun `post request save failure warns that retry may disclose twice`() {
        assertEquals(
            "这次可能已联系外部服务，但本机没有保存最终结果。输入的线索已保存在本机；再次寻找可能重复发送。请先检查本机存储。",
            postRequestLocalSaveMessage(contextPersisted = true),
        )
        assertEquals(
            "这次可能已联系外部服务，但本机没有保存最终结果。输入的线索没有保存在本机；再次寻找可能重复发送。请先检查本机存储。",
            postRequestLocalSaveMessage(contextPersisted = false),
        )
    }

    @Test
    fun `startup failure does not expose vault internals`() {
        val expected = "本机家庭资料没有正常打开。为保护现有内容，暂时不能新建家庭。请勿卸载或清除应用；可以重试打开。"
        assertEquals(expected, IllegalArgumentException("Unsupported vault format").asUserFacingMessage(ErrorContext.STARTUP))
        assertEquals(expected, IOException("file details").asUserFacingMessage(ErrorContext.STARTUP))
    }
}
