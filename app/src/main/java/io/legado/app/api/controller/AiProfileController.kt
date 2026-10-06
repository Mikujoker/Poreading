package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.model.AiProfileDraft
import io.legado.app.domain.model.AiProtocol
import io.legado.app.domain.model.AiTaskType
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import org.koin.core.context.GlobalContext

/**
 * Web 服务侧的 AI 配置入口：把「我的 → AI 设置」那套写成接口，方便脚本/agent 一次性配好对话模型。
 *
 * 只写不读密钥：`/getAiProfile` 只回模型名与 baseUrl，**绝不回 apiKey**。
 */
object AiProfileController {

    private val profileGateway by lazy { GlobalContext.get().get<AiProfileGateway>() }

    /**
     * body: {"providerName"?,"baseUrl","apiKey","modelName"?,"modelId","protocol"?,"maxOutputTokens"?}
     * 走 app 自己的 saveDefaultChatProfile，因此 CHAT 预设会被指向这个模型（getTaskPreset(CHAT) 立刻可用）。
     */
    suspend fun saveDefaultChat(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val body = GSON.fromJsonObject<Map<String, Any?>>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("数据格式错误")
        val baseUrl = body["baseUrl"]?.toString()?.trim().orEmpty()
        val apiKey = body["apiKey"]?.toString()?.trim().orEmpty()
        val modelId = body["modelId"]?.toString()?.trim().orEmpty()
        if (baseUrl.isEmpty() || apiKey.isEmpty() || modelId.isEmpty()) {
            return ReturnData().setErrorMsg("baseUrl / apiKey / modelId 都不能为空")
        }
        return runCatching {
            val preset = profileGateway.saveDefaultChatProfile(
                AiProfileDraft(
                    providerName = body["providerName"]?.toString()?.trim().orEmpty()
                        .ifEmpty { "Default AI Provider" },
                    protocol = body["protocol"]?.toString()?.trim().orEmpty()
                        .ifEmpty { AiProtocol.OPENAI_CHAT_COMPLETIONS },
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    modelName = body["modelName"]?.toString()?.trim().orEmpty().ifEmpty { modelId },
                    modelId = modelId,
                    maxOutputTokens = (body["maxOutputTokens"] as? Number)?.toInt() ?: 0,
                )
            )
            ReturnData().setData(
                linkedMapOf(
                    "taskType" to preset.taskType,
                    "name" to preset.name,
                    "model" to preset.model.id,
                    "modelId" to preset.model.modelId,
                )
            )
        }.getOrElse { ReturnData().setErrorMsg("保存失败：${it.localizedMessage}") }
    }

    /** 回读当前 CHAT 预设（不含密钥），用于确认配置是否生效 */
    suspend fun getDefaultChat(): ReturnData {
        val preset = profileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: return ReturnData().setErrorMsg("还没有配置 CHAT 预设（去「我的 → AI 设置」）")
        return ReturnData().setData(
            linkedMapOf(
                "taskType" to preset.taskType,
                "name" to preset.name,
                "model" to preset.model.id,
                "displayName" to preset.model.displayName,
                "modelId" to preset.model.modelId,
                "baseUrl" to preset.model.provider.baseUrl,
            )
        )
    }
}
