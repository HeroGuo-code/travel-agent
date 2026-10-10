package io.aligo.travel.web;

import io.aligo.travel.agent.OrchestratorService;
import io.aligo.travel.agent.OrchestratorService.ChatRequest;
import io.aligo.travel.agent.OrchestratorService.EventSink;
import io.aligo.travel.observe.GuardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天 SSE 端点（对应文章"流式输出层"-"构建结构化最终响应并生成符合 SSE 格式的输出流"）。
 *
 * <p>事件协议：
 * <ul>
 *   <li>{@code session_started} 会话与模型模式（含预算降级标记）</li>
 *   <li>{@code meta} 快慢车道 + 意图决策（显式推理）</li>
 *   <li>{@code task_update} 思考链任务节点（TaskCollector 发布订阅）</li>
 *   <li>{@code think} / {@code text} 思考增量 / 回答增量</li>
 *   <li>{@code card} 结构化卡片（_card 标记抽取自 tool_result）</li>
 *   <li>{@code ping} 心跳（编排期间每 5s，保活 Cloudflare/Nginx 代理链路，前端忽略）</li>
 *   <li>{@code agent_start/agent_end/warn/error/done}</li>
 * </ul>
 *
 * <p>上线防护：IP 滑动窗口限流（超限直接以 SSE error 事件友好提示，而非 HTTP 429，
 * 保证 EventSource 前端可正常渲染文案）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final OrchestratorService orchestrator;
    private final GuardService guard;
    private final ObjectMapper om = new ObjectMapper();
    private final ScheduledExecutorService heartbeatPool =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public ChatController(OrchestratorService orchestrator, GuardService guard) {
        this.orchestrator = orchestrator;
        this.guard = guard;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String sessionId,
                             @RequestParam(defaultValue = "demo-user") String userId,
                             @RequestParam(required = false) String tenantId,
                             @RequestParam String query,
                             HttpServletRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        String ip = clientIp(request);

        // 限流：不通过则以 SSE error 事件返回友好文案（前端 EventSource 可正常显示）
        if (!guard.allow(ip)) {
            safeSend(emitter, "error", Map.of("message",
                    "演示请求过于频繁（每 IP 每分钟 " + rateLimitText() + " 次），请稍候再试。"));
            emitter.complete();
            return emitter;
        }

        // 心跳：编排期间每 5s 发 ping，防止跨境代理链路空闲超时（CF 100s / Nginx read timeout）
        ScheduledFuture<?> hb = heartbeatPool.scheduleAtFixedRate(() -> {
            safeSend(emitter, "ping", Map.of("ts", System.currentTimeMillis()));
        }, 5, 5, TimeUnit.SECONDS);

        EventSink sink = new EventSink() {
            @Override
            public void send(String type, Object payload) {
                safeSend(emitter, type, payload);
            }

            @Override
            public void complete() {
                hb.cancel(false);
                emitter.complete();
            }
        };
        emitter.onCompletion(() -> hb.cancel(false));
        emitter.onTimeout(() -> hb.cancel(false));

        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chat-worker");
            t.setDaemon(true);
            return t;
        }).submit(() -> orchestrator.handle(new ChatRequest(sessionId, userId, tenantId, query), sink));
        return emitter;
    }

    private String rateLimitText() {
        return String.valueOf(guard.stats().get("rateLimitPerMin"));
    }

    private void safeSend(SseEmitter emitter, String type, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(type).data(om.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("sse send failed: {}", e.getMessage());
        }
    }

    /** 取真实客户端 IP（穿透 Nginx/Cloudflare 的 X-Forwarded-For）。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
