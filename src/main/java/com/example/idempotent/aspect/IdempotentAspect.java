package com.example.idempotent.aspect;

import com.example.idempotent.annotation.Idempotent;
import com.example.idempotent.exception.IdempotentException;
import com.example.idempotent.properties.IdempotentProperties;
import com.example.idempotent.strategy.IdempotentStrategy;
import com.example.idempotent.strategy.IdempotentStrategyEnum;
import com.example.idempotent.strategy.IdempotentStrategy.StrategyContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * <p>幂等核心切面 —— 拦截 {@code @Idempotent} 注解方法</p>
 *
 * <h3>切面职责</h3>
 * <ol>
 *     <li>解析注解上的策略、超时、SpEL key 等属性</li>
 *     <li>通过 {@code Map<String, IdempotentStrategy>} 按 strategyEnum 路由到具体实现</li>
 *     <li>构造 {@link StrategyContext}（从 HttpServletRequest 读取 token 注入 clientToken）</li>
 *     <li>try{beforeInvoke → proceed} finally{afterInvoke}，保证锁/指纹一定被清理</li>
 * </ol>
 *
 * <p>异常处理策略：</p>
 * <ul>
 *     <li>{@link IdempotentException}（幂等校验失败）→ 直接透传给全局异常处理器返回友好 JSON</li>
 *     <li>业务方法抛出的其他异常 → 透传，afterInvoke 仍会被执行</li>
 * </ul>
 *
 * @author distributed-idempotent-starter
 */
@Aspect
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    /**
     * 策略注册表 —— key 是策略枚举的 name()（如 "REDIS_TOKEN" / "SPEL_FINGERPRINT" / "DISTRIBUTED_LOCK"）
     * <p>由 {@link com.example.idempotent.config.IdempotentAutoConfiguration} 统一注入</p>
     */
    private final Map<String, IdempotentStrategy> strategyRegistry;

    private final IdempotentProperties properties;

    public IdempotentAspect(Map<String, IdempotentStrategy> strategyRegistry,
                            IdempotentProperties properties) {
        this.strategyRegistry = strategyRegistry;
        this.properties = properties;
    }

    /**
     * 拦截所有标注 {@link Idempotent} 的 public 方法
     */
    @Around("@annotation(com.example.idempotent.annotation.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();

        // 拿注解（可能在接口上，所以先 method.getAnnotation 再 fallback targetClass）
        Idempotent annotation = resolveAnnotation(method, pjp.getTarget());

        if (annotation == null) {
            // 兜底：理论上 @annotation 切点已经保证有注解，这里只是防御性
            return pjp.proceed();
        }

        // 解析策略枚举
        IdempotentStrategyEnum strategyEnum = annotation.strategy();

        // 路由到策略实现
        IdempotentStrategy strategy = strategyRegistry.get(strategyEnum.name());
        if (strategy == null) {
            throw new IdempotentException(
                    "未找到幂等策略实现：" + strategyEnum.name() + "，请检查 IdempotentAutoConfiguration 是否装配");
        }

        // 从 Web 请求里拿 clientToken（REDIS_TOKEN 策略必需）
        String clientToken = resolveClientToken();

        // 构造上下文
        StrategyContext ctx = new StrategyContext(
                method,
                pjp.getTarget(),
                pjp.getArgs(),
                annotation,
                strategyEnum,
                properties.getPrefix(),
                clientToken
        );

        Object handle = null;
        Throwable proceedException = null;
        try {
            // 前置校验 —— 重复请求会在此抛出 IdempotentException
            handle = strategy.beforeInvoke(ctx);

            // 执行业务方法
            return pjp.proceed();
        } catch (Throwable ex) {
            proceedException = ex;
            throw ex;
        } finally {
            try {
                strategy.afterInvoke(ctx, handle, proceedException);
            } catch (Exception cleanupEx) {
                // 清理阶段异常不覆盖主异常，只 warn 日志
                log.warn("幂等策略 afterInvoke 异常 strategy={}, method={}", strategyEnum, method.getName(), cleanupEx);
            }
        }
    }

    // ===================== 私有辅助 =====================

    /**
     * 解析方法注解 —— 先方法本体，再 fallback 接口方法 / target class 方法
     */
    private Idempotent resolveAnnotation(Method method, Object target) {
        Idempotent anno = method.getAnnotation(Idempotent.class);
        if (anno != null) {
            return anno;
        }
        Class<?>[] interfaces = target.getClass().getInterfaces();
        for (Class<?> iface : interfaces) {
            try {
                Method ifaceMethod = iface.getMethod(method.getName(), method.getParameterTypes());
                anno = ifaceMethod.getAnnotation(Idempotent.class);
                if (anno != null) {
                    return anno;
                }
            } catch (NoSuchMethodException ignored) {
                // 接口没有这个方法就继续下一个接口
            }
        }
        return null;
    }

    /**
     * 从 HttpServletRequest 读取 clientToken
     * 非 Web 环境（单元测试、定时任务）下 RequestContextHolder 取不到，返回 null 交给策略层处理
     */
    private String resolveClientToken() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest request = attrs.getRequest();
            return request.getHeader(properties.getHeaderToken());
        } catch (Exception ex) {
            log.debug("未找到 HttpServletRequest（可能是非 Web 环境 / 单元测试）", ex);
            return null;
        }
    }
}
