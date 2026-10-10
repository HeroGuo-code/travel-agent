package io.aligo.travel.model;

import io.agentscope.core.model.Model;
import io.aligo.travel.config.TravelProperties;
import io.aligo.travel.observe.GuardService;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型工厂：按配置产出 Mock 剧本模型、通义千问（DashScope）或 OpenRouter（OpenAI 兼容）。
 *
 * <p>对应文章选型决策："AgentScope 对 Qwen 系列模型原生支持"；无 API Key 或当日
 * 真模型 token 预算耗尽时自动降级 Mock，保证演示在任何环境可复现且成本可控。
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final TravelProperties props;
    private final GuardService guard;

    public ModelFactory(TravelProperties props, GuardService guard) {
        this.props = props;
        this.guard = guard;
    }

    /** 每次构建新的 Model 实例（tenant 隔离以 mock 更自然）。 */
    public Model create(String tenantId) {
        if (guard.isBudgetExceeded()) {
            log.warn("daily LLM token budget exceeded, fallback to mock");
            return new ScriptedMockModel(tenantId);
        }
        String provider = props.effectiveProvider();
        if ("dashscope".equals(provider)) {
            log.info("LLM provider=DashScope modelId={}", props.getModelId());
            return DashScopeChatModel.builder()
                    .modelName(stripPrefix(props.getModelId()))
                    .build();
        }
        if ("openrouter".equals(provider)) {
            log.info("LLM provider=OpenRouter modelId={} baseUrl={}", props.getModelId(), props.getBaseUrl());
            return OpenAIChatModel.builder()
                    .baseUrl(props.getBaseUrl())
                    .apiKey(System.getenv("OPENROUTER_API_KEY"))
                    .modelName(stripPrefix(props.getModelId()))
                    .build();
        }
        log.info("LLM provider=mock (scripted)");
        return new ScriptedMockModel(tenantId);
    }

    /** 生效模式（UI badge 用）：预算耗尽或无 key 时显示 mock。 */
    public String activeProvider() {
        if (guard.isBudgetExceeded()) {
            return "mock";
        }
        return props.effectiveProvider();
    }

    private String stripPrefix(String modelId) {
        int i = modelId.indexOf(':');
        return i >= 0 ? modelId.substring(i + 1) : modelId;
    }
}
