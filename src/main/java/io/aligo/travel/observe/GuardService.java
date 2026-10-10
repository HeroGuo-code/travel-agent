package io.aligo.travel.observe;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上线防护与运营观测四合一：
 * <ul>
 *   <li><b>IP 限流</b>：滑动窗口（默认 60s / 4 次），防公开演示被刷爆模型余额</li>
 *   <li><b>日预算</b>：累计真模型 token，超限自动降级 mock（成本防护设计）</li>
 *   <li><b>运营统计</b>：请求数 / 快慢车道 / 耗时分位 / token / 费用估算 / 工具调用计数</li>
 *   <li><b>故障模拟</b>：演示限流、预算降级、跨境延迟三种异常路径的观测表现</li>
 * </ul>
 */
@Component
public class GuardService {

    public enum FaultMode { NONE, RATE_LIMIT, BUDGET, LATENCY }

    @Value("${travel.guard.rate-limit-per-min:4}")
    private int rateLimitPerMin;

    @Value("${travel.guard.daily-budget-tokens:500000}")
    private long dailyBudgetTokens;

    /** deepseek/deepseek-v4.1-flash 计价（$/1M tokens），用于成本观测 */
    @Value("${travel.guard.price-in:0.3}")
    private double priceIn;

    @Value("${travel.guard.price-out:1.2}")
    private double priceOut;

    private final ConcurrentHashMap<String, Deque<Long>> rateWindows = new ConcurrentHashMap<>();
    private final AtomicLong dayInputTokens = new AtomicLong();
    private final AtomicLong dayOutputTokens = new AtomicLong();
    private volatile LocalDate budgetDay = LocalDate.now();
    private volatile FaultMode faultMode = FaultMode.NONE;

    // 运营统计（自启动累计，内存态，重启归零）
    private final AtomicInteger totalRequests = new AtomicInteger();
    private final AtomicInteger fastLane = new AtomicInteger();
    private final AtomicInteger slowLane = new AtomicInteger();
    private final AtomicInteger rejectedByRateLimit = new AtomicInteger();
    private final List<Long> elapsedSamples = java.util.Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentHashMap<String, AtomicInteger> toolCalls = new ConcurrentHashMap<>();

    /** 限流判定：滑动窗口。故障模拟 RATE_LIMIT 时恒拒绝。 */
    public boolean allow(String ip) {
        if (faultMode == FaultMode.RATE_LIMIT) {
            rejectedByRateLimit.incrementAndGet();
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<Long> q = rateWindows.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000L) {
                q.pollFirst();
            }
            if (q.size() >= rateLimitPerMin) {
                rejectedByRateLimit.incrementAndGet();
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    /** 日预算是否超限（超限后 ModelFactory 自动降级 mock）。故障模拟 BUDGET 时恒超限。 */
    public boolean isBudgetExceeded() {
        if (faultMode == FaultMode.BUDGET) {
            return true;
        }
        rollDayIfNeeded();
        return dayInputTokens.get() + dayOutputTokens.get() >= dailyBudgetTokens;
    }

    public boolean simulateLatency() {
        return faultMode == FaultMode.LATENCY;
    }

    public void setFaultMode(FaultMode mode) {
        this.faultMode = mode == null ? FaultMode.NONE : mode;
    }

    public FaultMode getFaultMode() {
        return faultMode;
    }

    /** 每次对话结束后记录统计。 */
    public void record(String lane, long elapsedMs, int inputTokens, int outputTokens, List<String> toolNames) {
        if (faultMode == FaultMode.BUDGET) {
            return; // 模拟模式不污染真实统计
        }
        rollDayIfNeeded();
        totalRequests.incrementAndGet();
        if ("fast".equals(lane)) {
            fastLane.incrementAndGet();
        } else {
            slowLane.incrementAndGet();
        }
        dayInputTokens.addAndGet(Math.max(inputTokens, 0));
        dayOutputTokens.addAndGet(Math.max(outputTokens, 0));
        synchronized (elapsedSamples) {
            elapsedSamples.add(elapsedMs);
            if (elapsedSamples.size() > 1000) {
                elapsedSamples.remove(0);
            }
        }
        if (toolNames != null) {
            toolNames.forEach(n -> toolCalls.computeIfAbsent(n, k -> new AtomicInteger()).incrementAndGet());
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRequests", totalRequests.get());
        m.put("fastLane", fastLane.get());
        m.put("slowLane", slowLane.get());
        m.put("rejectedByRateLimit", rejectedByRateLimit.get());
        long in = dayInputTokens.get();
        long out = dayOutputTokens.get();
        m.put("inputTokens", in);
        m.put("outputTokens", out);
        m.put("dailyBudgetTokens", dailyBudgetTokens);
        m.put("budgetExceeded", isBudgetExceeded());
        m.put("costUsd", Math.round((in / 1e6 * priceIn + out / 1e6 * priceOut) * 10000.0) / 10000.0);
        List<Long> s;
        synchronized (elapsedSamples) {
            s = new ArrayList<>(elapsedSamples);
        }
        if (!s.isEmpty()) {
            java.util.Collections.sort(s);
            m.put("p50Ms", s.get((s.size() - 1) / 2));
            m.put("p95Ms", s.get((int) Math.floor((s.size() - 1) * 0.95)));
            m.put("avgMs", s.stream().mapToLong(Long::longValue).sum() / s.size());
        }
        m.put("rateLimitPerMin", rateLimitPerMin);
        m.put("faultMode", faultMode.name());
        Map<String, Integer> tools = new LinkedHashMap<>();
        toolCalls.entrySet().stream()
                .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                .forEach(e -> tools.put(e.getKey(), e.getValue().get()));
        m.put("toolCalls", tools);
        return m;
    }

    private void rollDayIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!budgetDay.equals(today)) {
            synchronized (this) {
                if (!budgetDay.equals(today)) {
                    budgetDay = today;
                    dayInputTokens.set(0);
                    dayOutputTokens.set(0);
                }
            }
        }
    }
}
