package com.example.idempotent;

import com.example.idempotent.annotation.Idempotent;
import com.example.idempotent.properties.IdempotentProperties;
import com.example.idempotent.strategy.IdempotentStrategyEnum;
import com.example.idempotent.strategy.RedisTokenStrategy;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>演示 Controller —— 宿主工程可参考此用法</p>
 *
 * <p>本类仅作示例教学，位于 src/test/java 不会打入 starter jar</p>
 *
 * @author distributed-idempotent-starter
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private final RedisTokenStrategy redisTokenStrategy;

    public DemoController(RedisTokenStrategy redisTokenStrategy) {
        this.redisTokenStrategy = redisTokenStrategy;
    }

    // ===================== ① 生成幂等 Token（宿主需自行实现 /idempotent/token） =====================

    /**
     * <p>生成幂等 Token —— 宿主工程需提供此接口</p>
     * <p>客户端先 GET /demo/token 拿到 token，然后在业务请求头带上 X-Idempotent-Token 再提交业务接口</p>
     */
    @GetMapping("/token")
    public Map<String, Object> getToken(
            @RequestParam(required = false) String businessKey) {
        String token = redisTokenStrategy.generateToken(businessKey);
        Map<String, Object> resp = new HashMap<>();
        resp.put("token", token);
        resp.put("header", "X-Idempotent-Token");
        resp.put("ttlSeconds", 300);
        return resp;
    }

    // ===================== ② 策略一：Redis Token 前端防重提交 =====================

    /**
     * <p>策略一：客户端先申请 token → 请求头携带 → 后端 Lua 原子校验+删除</p>
     * <p>连续重复请求会因 token 已被消费而拦截，返回幂等异常 JSON</p>
     */
    @PostMapping("/submit")
    @Idempotent(strategy = IdempotentStrategyEnum.REDIS_TOKEN, timeout = 10)
    public Map<String, Object> submit(@RequestBody Map<String, Object> payload) {
        // 业务逻辑...
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", "OK");
        resp.put("message", "提交成功");
        resp.put("payload", payload);
        resp.put("at", System.currentTimeMillis());
        return resp;
    }

    // ===================== ③ 策略二：SpEL 参数指纹服务端防重 =====================

    /**
     * <p>策略二：按 SpEL 表达式生成指纹 → setIfAbsent 防并发重复</p>
     * <p>同一个 userId + orderNo 在 60s 内只能执行一次</p>
     */
    @PostMapping("/pay")
    @Idempotent(strategy = IdempotentStrategyEnum.SPEL_FINGERPRINT,
            key = "#dto.userId + '_' + #dto.orderNo",
            timeout = 60,
            releaseAfterMethod = false)
    public Map<String, Object> pay(@RequestBody PayDTO dto) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", "OK");
        resp.put("message", "支付成功");
        resp.put("orderNo", dto.getOrderNo());
        return resp;
    }

    // ===================== ④ 策略三：Redisson 分布式锁 =====================

    /**
     * <p>策略三：Redisson 分布式锁 —— 宿主需引入 redisson-spring-boot-starter</p>
     */
    @PostMapping("/deduct")
    @Idempotent(strategy = IdempotentStrategyEnum.DISTRIBUTED_LOCK,
            key = "'stock:' + #productId",
            timeout = 30,
            releaseAfterMethod = true)
    public Map<String, Object> deduct(@RequestParam Long productId,
                                       @RequestParam int count) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("code", "OK");
        resp.put("message", "扣减成功");
        resp.put("productId", productId);
        resp.put("count", count);
        return resp;
    }

    // ===================== DTO =====================

    public static class PayDTO {
        private String userId;
        private String orderNo;
        // Lombok @Data 或手写 getter/setter
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    }
}
