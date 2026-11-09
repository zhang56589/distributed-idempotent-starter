package com.example.idempotent.strategy;

/**
 * <p>幂等策略枚举</p>
 * <ol>
 *     <li>{@link #REDIS_TOKEN} —— 前端防重提交：客户端先申请 token，请求时携带，后端原子校验+删除</li>
 *     <li>{@link #SPEL_FINGERPRINT} —— 服务端防重：SpEL 解析参数 → 生成 MD5 指纹 → setIfAbsent 加锁</li>
 *     <li>{@link #DISTRIBUTED_LOCK} —— Redisson 分布式锁：拦截后直接尝试获取 RLock</li>
 * </ol>
 *
 * @author distributed-idempotent-starter
 */
public enum IdempotentStrategyEnum {

    /**
     * 基于 Redis Token —— 前端防重提交
     */
    REDIS_TOKEN,

    /**
     * 基于 SpEL 参数指纹 —— 服务端防重
     */
    SPEL_FINGERPRINT,

    /**
     * 基于 Redisson 分布式锁
     */
    DISTRIBUTED_LOCK
}
