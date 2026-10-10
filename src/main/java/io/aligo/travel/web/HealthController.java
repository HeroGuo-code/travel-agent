package io.aligo.travel.web;

import io.aligo.travel.model.ModelFactory;
import io.aligo.travel.observe.GuardService;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查（供 Cloudflare/云监控拨测与运维自查）：进程状态 + 模型模式 + 运行时长。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final ModelFactory modelFactory;
    private final GuardService guard;

    public HealthController(ModelFactory modelFactory, GuardService guard) {
        this.modelFactory = modelFactory;
        this.guard = guard;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("provider", modelFactory.activeProvider());
        m.put("budgetExceeded", guard.isBudgetExceeded());
        m.put("faultMode", guard.getFaultMode().name());
        m.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("version", "1.0.0");
        return m;
    }
}
