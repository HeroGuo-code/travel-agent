package io.aligo.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置项：
 * <ul>
 *   <li>{@code travel.llm.provider}：{@code mock}（剧本模型，零依赖可复现）、
 *       {@code dashscope}（通义千问）或 {@code openrouter}（OpenRouter，OpenAI 兼容协议）</li>
 *   <li>{@code travel.llm.modelId}：AgentScope 模型 id，如 {@code dashscope:qwen-plus}、
 *       {@code openrouter:deepseek/deepseek-v4.1-flash}</li>
 *   <li>{@code travel.llm.baseUrl}：OpenAI 兼容端点，openrouter 模式默认
 *       {@code https://openrouter.ai/api/v1}</li>
 * </ul>
 * dashscope 模式需设置环境变量 {@code DASHSCOPE_API_KEY}；openrouter 模式需设置
 * 环境变量 {@code OPENROUTER_API_KEY}。缺 key 时自动降级 mock，保证演示可复现。
 */
@Component
@ConfigurationProperties(prefix = "travel.llm")
public class TravelProperties {

    /** mock | dashscope | openrouter */
    private String provider = "mock";
    /** AgentScope 模型 id（provider:model 由 ModelRegistry 解析） */
    private String modelId = "dashscope:qwen-plus";
    /** 是否容忍无 key 时自动降级到 mock */
    private boolean fallbackToMock = true;
    /** OpenAI 兼容端点（openrouter 模式使用） */
    private String baseUrl = "https://openrouter.ai/api/v1";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public boolean isFallbackToMock() {
        return fallbackToMock;
    }

    public void setFallbackToMock(boolean fallbackToMock) {
        this.fallbackToMock = fallbackToMock;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 真实生效的 provider：配置了真模型但对应 key 缺失时自动降级到 mock。 */
    public String effectiveProvider() {
        String envKey = "dashscope".equalsIgnoreCase(provider) ? "DASHSCOPE_API_KEY"
                : "openrouter".equalsIgnoreCase(provider) ? "OPENROUTER_API_KEY" : null;
        if (envKey != null) {
            String key = System.getenv(envKey);
            if (key == null || key.isBlank()) {
                return fallbackToMock ? "mock" : provider;
            }
        }
        return provider;
    }
}
