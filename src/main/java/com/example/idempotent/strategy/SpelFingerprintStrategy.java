package com.example.idempotent.strategy;

import com.example.idempotent.annotation.Idempotent;
import com.example.idempotent.exception.IdempotentException;
import com.example.idempotent.properties.IdempotentProperties;
import com.example.idempotent.utils.SpELUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * <p>策略二：基于 SpEL 参数指纹的服务端防重</p>
 *
 * <h3>指纹生成公式</h3>
 * <pre>
 *   fingerprint = MD5( uri + "#" + methodSignature + "#" + spelValue )
 *   redisKey    = prefix + "fp:" + fingerprint
 * </pre>
 *
 * <h3>执行流程</h3>
 * <ol>
 *     <li>解析注解上的 SpEL（如 {@code #dto.userId + '_' + #dto.orderNo}）得到业务唯一键</li>
 *     <li>拼接 URI + 方法签名 + SpEL 值 → MD5 指纹</li>
 *     <li>{@code SETNX redisKey=1 EX timeout}：返回 true 则首次请求，执行业务；
 *         返回 false 则判定为重复请求，抛 {@link IdempotentException}</li>
 *     <li>业务执行后：
 *         <ul>
 *             <li>{@code releaseAfterMethod=false}（默认）：让过期自动失效 → 短时间窗口内持续拦截同一业务</li>
 *             <li>{@code releaseAfterMethod=true}：主动 DEL → 只拦截同一时刻的并发，业务跑完立即放行后续请求</li>
 *         </ul>
 *     </li>
 * </ol>
 *
 * @author distributed-idempotent-starter
 */
public class SpelFingerprintStrategy implements IdempotentStrategy {

    private static final Logger log = LoggerFactory.getLogger(SpelFingerprintStrategy.class);

    private final StringRedisTemplate redisTemplate;
    private final IdempotentProperties properties;

    public SpelFingerprintStrategy(StringRedisTemplate redisTemplate, IdempotentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public IdempotentStrategyEnum getStrategyType() {
        return IdempotentStrategyEnum.SPEL_FINGERPRINT;
    }

    @Override
    public Object beforeInvoke(StrategyContext ctx) {
        Idempotent annotation = ctx.annotation();

        // 1) 解析 SpEL
        String spelValue = SpELUtils.parseToString(ctx.method(), ctx.args(), annotation.key());
        if (spelValue == null || spelValue.isBlank()) {
            throw new IdempotentException(
                    "幂等校验失败：SpEL key 解析结果为空，请检查注解 key 属性");
        }

        // 2) 生成指纹
        String fingerprint = buildFingerprint(ctx.method(), spelValue);
        String redisKey = ctx.redisKeyPrefix() + "fp:" + fingerprint;

        // 3) SETNX
        long timeout = annotation.timeout();
        TimeUnit unit = annotation.unit();
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", timeout, unit);

        if (first == null || !first) {
            // 已存在 → 重复请求
            log.info("[SPEL_FINGERPRINT] 命中重复请求 method={}, spelValue={}", ctx.method().getName(), spelValue);
            throw new IdempotentException(
                    "幂等校验失败：检测到重复请求，key=" + annotation.key() + ", value=" + spelValue);
        }

        // 返回 redisKey 作为 handle（afterInvoke 可能用到）
        return redisKey;
    }

    @Override
    public void afterInvoke(StrategyContext ctx, Object handle, Throwable ex) {
        if (handle == null) {
            return;
        }
        boolean release = ctx.annotation().releaseAfterMethod();
        if (release) {
            redisTemplate.delete((String) handle);
        }
        // release=false 时让 Redis TTL 自然过期即可
    }

    // ===================== 私有辅助 =====================

    /**
     * 拼接方法签名 + spelValue → MD5 指纹（public 便于单测验证）
     */
    public static String buildFingerprint(java.lang.reflect.Method method, String spelValue) {
        // URI 取类全名 + 方法名（不依赖 Web 环境也能生成稳定指纹）
        String signature = method.getDeclaringClass().getName() + "#" + method.getName();
        String raw = signature + "|" + spelValue;
        return md5Hex(raw);
    }

    public static String md5Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new RuntimeException("MD5 计算异常", ex);
        }
    }
}
