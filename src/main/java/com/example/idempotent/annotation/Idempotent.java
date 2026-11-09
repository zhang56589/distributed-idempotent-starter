package com.example.idempotent.annotation;

import com.example.idempotent.strategy.IdempotentStrategyEnum;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * <p>幂等注解 —— 无侵入式接口防重与防重放</p>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 策略一：前端防重提交 —— 客户端先调 /idempotent/token 拿 token，请求头带上 X-Idempotent-Token
 * &#64;Idempotent(strategy = IdempotentStrategyEnum.REDIS_TOKEN, timeout = 5)
 * public R submitOrder(OrderDTO dto) { ... }
 *
 * // 策略二：服务端防重 —— SpEL 指纹 + setIfAbsent
 * &#64;Idempotent(strategy = IdempotentStrategyEnum.SPEL_FINGERPRINT, key = "#dto.userId + '_' + #dto.orderNo", timeout = 10)
 * public R createOrder(OrderDTO dto) { ... }
 *
 * // 策略三：Redisson 分布式锁
 * &#64;Idempotent(strategy = IdempotentStrategyEnum.DISTRIBUTED_LOCK, key = "'order:' + #dto.orderNo", timeout = 30)
 * public R pay(OrderDTO dto) { ... }
 * </pre>
 *
 * @author distributed-idempotent-starter
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * <p>幂等过期时间</p>
     * <ul>
     *     <li>REDIS_TOKEN 策略：token 的有效期，默认 5 秒</li>
     *     <li>SPEL_FINGERPRINT 策略：指纹 key 的保留时间，默认 5 秒</li>
     *     <li>DISTRIBUTED_LOCK 策略：锁的 leaseTime，默认 5 秒</li>
     * </ul>
     */
    long timeout() default 5;

    /**
     * 时间单位，默认秒
     */
    TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * 幂等策略，默认 {@link IdempotentStrategyEnum#REDIS_TOKEN}
     */
    IdempotentStrategyEnum strategy() default IdempotentStrategyEnum.REDIS_TOKEN;

    /**
     * <p>SpEL 表达式 —— 用于生成幂等 key</p>
     * <p>仅 SPEL_FINGERPRINT / DISTRIBUTED_LOCK 策略生效；
     * REDIS_TOKEN 策略会从请求头读取 token，该值可留空</p>
     * <p>示例：</p>
     * <ul>
     *     <li><code>"#dto.userId"</code></li>
     *     <li><code>"'order:' + #dto.orderNo"</code></li>
     *     <li><code>"T(com.example.UserContext).getCurrentUserId()"</code></li>
     * </ul>
     */
    String key() default "";

    /**
     * 业务完成后是否主动释放锁 / 删除指纹 key
     * <ul>
     *     <li>REDIS_TOKEN：固定为 false（删除即"消费"，不可释放）</li>
     *     <li>SPEL_FINGERPRINT：默认 false（让过期自然失效），对需要"只执行一次且立即防重"的场景可设为 true</li>
     *     <li>DISTRIBUTED_LOCK：默认 true（业务执行完毕主动释放锁）</li>
     * </ul>
     */
    boolean releaseAfterMethod() default false;
}
