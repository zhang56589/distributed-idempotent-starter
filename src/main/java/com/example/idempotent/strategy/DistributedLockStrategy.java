package com.example.idempotent.strategy;

import com.example.idempotent.annotation.Idempotent;
import com.example.idempotent.exception.IdempotentException;
import com.example.idempotent.properties.IdempotentProperties;
import com.example.idempotent.utils.SpELUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * <p>策略三：基于 Redisson 分布式锁</p>
 *
 * <h3>与 SPEL_FINGERPRINT 的区别</h3>
 * <ul>
 *     <li>fingerprint 是"一次性消费券"，setIfAbsent 后不管锁是否被释放都能阻止重复</li>
 *     <li>distributed_lock 是"互斥锁"，未释放前其他线程无法进入，适合并发高、业务耗时长的场景</li>
 * </ul>
 *
 * <h3>执行流程</h3>
 * <ol>
 *     <li>解析 SpEL key → 拼接 Redisson 锁名：{@code prefix + "lock:" + spelValue}</li>
 *     <li>{@code RLock.tryLock(waitTime, leaseTime, unit)}：
 *         waitTime 默认 3s，leaseTime 取注解 timeout</li>
 *     <li>拿到锁则执行业务方法，失败则抛 IdempotentException</li>
 *     <li>afterInvoke 默认主动 unlock（releaseAfterMethod=true），避免死锁</li>
 * </ol>
 *
 * <p>注意：此策略依赖 RedissonClient 存在；若宿主未引入 redisson-spring-boot-starter，
 * 自动装配类会判定 {@code @ConditionalOnBean(RedissonClient.class)} 跳过注册，
 * 此时选择 DISTRIBUTED_LOCK 策略的方法会在启动阶段因"无可用策略 bean"报错，符合 fail-fast 预期。</p>
 *
 * @author distributed-idempotent-starter
 */
public class DistributedLockStrategy implements IdempotentStrategy {

    private static final Logger log = LoggerFactory.getLogger(DistributedLockStrategy.class);

    private final RedissonClient redissonClient;
    private final IdempotentProperties properties;

    public DistributedLockStrategy(RedissonClient redissonClient, IdempotentProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Override
    public IdempotentStrategyEnum getStrategyType() {
        return IdempotentStrategyEnum.DISTRIBUTED_LOCK;
    }

    @Override
    public Object beforeInvoke(StrategyContext ctx) {
        Idempotent annotation = ctx.annotation();

        String spelValue = SpELUtils.parseToString(ctx.method(), ctx.args(), annotation.key());
        if (spelValue == null || spelValue.isBlank()) {
            throw new IdempotentException(
                    "幂等校验失败：DISTRIBUTED_LOCK 策略必须指定有效的 key（SpEL 表达式）");
        }

        String lockName = ctx.redisKeyPrefix() + "lock:" + spelValue;
        RLock lock = redissonClient.getLock(lockName);

        long waitTime = properties.getRedissonStrategy().getLockWaitTime();
        long leaseTime = annotation.timeout();
        TimeUnit unit = annotation.unit();

        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime, leaseTime, unit);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IdempotentException("幂等校验失败：获取分布式锁被中断", ex);
        }

        if (!acquired) {
            log.info("[DISTRIBUTED_LOCK] 获取锁失败 lockName={}", lockName);
            throw new IdempotentException("幂等校验失败：分布式锁获取超时，key=" + spelValue);
        }
        return lock;
    }

    @Override
    public void afterInvoke(StrategyContext ctx, Object handle, Throwable ex) {
        if (!(handle instanceof RLock lock)) {
            return;
        }
        boolean release = ctx.annotation().releaseAfterMethod();
        // DISTRIBUTED_LOCK 默认 release=true（避免死锁）；若注解显式设 false，让 Redisson watchdog 续期后自动释放
        if (!lock.isHeldByCurrentThread()) {
            log.warn("[DISTRIBUTED_LOCK] 当前线程不再持有锁，跳过 unlock");
            return;
        }
        try {
            if (release) {
                lock.unlock();
            }
        } catch (Exception unlockEx) {
            log.warn("[DISTRIBUTED_LOCK] 主动释放锁异常 lockName={}", lock.getName(), unlockEx);
        }
    }
}
