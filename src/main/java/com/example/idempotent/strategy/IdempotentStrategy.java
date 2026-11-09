package com.example.idempotent.strategy;

import com.example.idempotent.annotation.Idempotent;

import java.lang.reflect.Method;

/**
 * <p>幂等策略接口 —— 所有幂等策略需实现此接口</p>
 *
 * <p>设计说明：</p>
 * <ul>
 *     <li>入参为 {@link StrategyContext}（承载方法签名、实参、注解、HTTP 请求头等）</li>
 *     <li>返回 {@link Object}：策略自行决定是否放行；若判定为重复请求，
 *         实现类可直接抛出 {@link com.example.idempotent.exception.IdempotentException} 中断执行</li>
 *     <li>调用方（Aspect）在 invoke 时会 try-finally 调用 {@link #afterInvoke(StrategyContext, Object, Throwable)}，
 *         用于释放锁 / 清理指纹 key</li>
 * </ul>
 *
 * @author distributed-idempotent-starter
 * @see RedisTokenStrategy
 * @see SpelFingerprintStrategy
 * @see DistributedLockStrategy
 */
public interface IdempotentStrategy {

    /**
     * 获取该策略唯一标识
     */
    IdempotentStrategyEnum getStrategyType();

    /**
     * <p>前置校验 —— 在业务方法执行前调用</p>
     * <p>若判定通过，则返回一个与当前请求绑定的"句柄"（用于 afterInvoke 识别）；
     * 若判定为重复请求，应抛出 IdempotentException</p>
     */
    Object beforeInvoke(StrategyContext ctx);

    /**
     * <p>后置清理 —— 在业务方法执行后（无论成功/异常）调用</p>
     *
     * @param ctx      上下文
     * @param handle   beforeInvoke 返回的句柄
     * @param ex       业务方法抛出的异常（无异常为 null）
     */
    void afterInvoke(StrategyContext ctx, Object handle, Throwable ex);

    // ======================== StrategyContext ========================

    /**
     * <p>幂等上下文 —— 一次方法调用内的共享数据</p>
     * <p>设计约束：Context 只承载纯数据，不依赖 Spring Web / Servlet，方便单元测试</p>
     */
    record StrategyContext(
            /** 当前拦截的方法 */
            Method method,
            /** 目标对象（Spring 代理可能需要拿到 targetClass） */
            Object target,
            /** 实参数组，与 method.getParameterTypes() 顺序一致 */
            Object[] args,
            /** 方法上的 @Idempotent 注解（已从 method / interface 合并） */
            Idempotent annotation,
            /** 合并后的幂等策略枚举（注解 + 全局默认） */
            IdempotentStrategyEnum strategyEnum,
            /** 合并后的 prefix key（全局配置 + 注解） */
            String redisKeyPrefix,
            /**
             * 客户端提交的幂等 Token（来自请求头）
             * 仅 REDIS_TOKEN 策略使用，由 Aspect 上层从 HttpServletRequest 解析好后传入；
             * 非 Web 环境（单元测试 / 定时任务）可手动注入
             */
            String clientToken
    ) {}
}
