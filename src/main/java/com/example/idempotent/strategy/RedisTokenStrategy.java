package com.example.idempotent.strategy;

import com.example.idempotent.annotation.Idempotent;
import com.example.idempotent.exception.IdempotentException;
import com.example.idempotent.properties.IdempotentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>策略一：基于 Redis Token 的前端防重提交</p>
 *
 * <h3>交互流程</h3>
 * <pre>
 * ① 客户端 GET /idempotent/token  → 服务端生成 UUID，写入 SETNX key=prefix+UUID, value=业务唯一标识, TTL=tokenExpireSeconds
 * ② 客户端提交业务接口时在请求头携带 X-Idempotent-Token: {UUID}
 * ③ Aspect 拦截业务方法 → 从请求头读 token 后塞入 StrategyContext.clientToken
 *    → 调本策略 beforeInvoke：执行 verify_token.lua（GET 匹配 → DEL → 返回 1 放行；否则返回 0 / -1 抛异常）
 * ④ 业务方法执行结束 → afterInvoke：因 token 已被脚本删除，无需额外清理
 * </pre>
 *
 * <p>设计约束：</p>
 * <ul>
 *     <li>Redis key 形如 {@code idempotent:token:{uuid}}，value 可存业务唯一标识（可选，当前实现 value=uuid 本身）</li>
 *     <li>Token 是"一次性消费券"：Lua 脚本内完成 GET+DEL，天然防并发重放</li>
 *     <li>Strategy 不直接依赖 Spring Web / Servlet，header 解析由 Aspect 上层完成，保持本类可单测</li>
 * </ul>
 *
 * @author distributed-idempotent-starter
 */
public class RedisTokenStrategy implements IdempotentStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenStrategy.class);

    /** token 在 Redis 中的 key 片段 */
    public static final String TOKEN_KEY_FRAGMENT = "token:";

    /** Lua 校验脚本（verify + delete 原子执行） */
    private final DefaultRedisScript<Long> verifyAndDeleteScript;

    private final StringRedisTemplate redisTemplate;
    private final IdempotentProperties properties;

    /**
     * 构造器注入 —— 由 IdempotentAutoConfiguration 统一装配
     *
     * @param redisTemplate StringRedisTemplate（宿主工程注入）
     * @param script        已加载的 {@code verify_token.lua} 脚本实例
     * @param properties    全局配置
     */
    public RedisTokenStrategy(StringRedisTemplate redisTemplate,
                              DefaultRedisScript<Long> script,
                              IdempotentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.verifyAndDeleteScript = script;
        this.properties = properties;
    }

    @Override
    public IdempotentStrategyEnum getStrategyType() {
        return IdempotentStrategyEnum.REDIS_TOKEN;
    }

    // ===================== 对外 API（宿主 Controller 可注入生成 token） =====================

    /**
     * <p>生成一个新的幂等 Token 并写入 Redis</p>
     * <p>宿主工程可提供 /idempotent/token 接口调用此方法；
     * 如果需要绑定业务唯一标识（防串用），可将 businessKey 作为 value 存入</p>
     *
     * @param businessKey 可选 —— 业务唯一标识（如 userId + orderNo），用于 Lua 校验时额外比对；传 null 则 value=token
     * @return 生成的 UUID token
     */
    public String generateToken(String businessKey) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String tokenKey = properties.getPrefix() + TOKEN_KEY_FRAGMENT + token;
        long expireSeconds = properties.getTokenStrategy().getTokenExpireSeconds();
        String value = (businessKey == null || businessKey.isBlank()) ? token : businessKey;

        Boolean ok = redisTemplate.opsForValue()
                .setIfAbsent(tokenKey, value, expireSeconds, TimeUnit.SECONDS);
        if (ok == null || !ok) {
            // 极端情况下撞 UUID，重试一次
            log.warn("Token 生成时发生碰撞，重试一次");
            return generateToken(businessKey);
        }
        return token;
    }

    // ===================== IdempotentStrategy 实现 =====================

    @Override
    public Object beforeInvoke(StrategyContext ctx) {
        // Aspect 上层已从请求头解析好 clientToken 并塞入 ctx
        String clientToken = ctx.clientToken();

        if (clientToken == null || clientToken.isBlank()) {
            throw new IdempotentException(
                    "幂等校验失败：缺少请求头 " + properties.getHeaderToken()
                            + "（请先调用 " + properties.getTokenPath() + " 获取 token）");
        }

        String tokenKey = ctx.redisKeyPrefix() + TOKEN_KEY_FRAGMENT + clientToken;

        // 执行 Lua：KEYS=[tokenKey], ARGV=[clientToken]
        // value 比较时，token 模式下 value 就是 token 本身，所以用 clientToken 比对
        Number result = redisTemplate.execute(
                verifyAndDeleteScript,
                Collections.singletonList(tokenKey),
                clientToken
        );
        long code = result == null ? 0L : result.longValue();

        if (code == 1L) {
            // 校验通过 —— 返回 handle 供 afterInvoke 识别
            return clientToken;
        }
        if (code == -1L) {
            throw new IdempotentException("幂等校验失败：token 不匹配（可能串用 / 伪造）");
        }
        // code == 0 或其他：token 不存在（过期 / 未申请 / 已被消费）
        throw new IdempotentException("幂等校验失败：token 不存在或已被消费，请重新申请");
    }

    @Override
    public void afterInvoke(StrategyContext ctx, Object handle, Throwable ex) {
        // token 已在 beforeInvoke 里被 Lua 原子删除，无需清理
        // 若 afterInvoke 拿到异常（如业务异常），也不应恢复 token —— 客户端会重试并申请新 token
    }
}
