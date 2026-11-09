package com.example.idempotent.utils;

import com.example.idempotent.exception.IdempotentException;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * <p>SpEL 解析工具 —— 带反射参数名 + Expression 缓存</p>
 *
 * <p>性能优化点：</p>
 * <ul>
 *     <li>{@link DefaultParameterNameDiscoverer} 解析方法参数名后，按 method 签名缓存，避免重复反射</li>
 *     <li>{@link ExpressionParser#parseExpression} 结果按 SpEL 文本缓存，避免重复解析 AST</li>
 * </ul>
 *
 * <p>注意：编译时需加 {@code -parameters} 或 Spring Boot 已默认开启，否则参数名会是 arg0/arg1，
 * SpEL 中写 #userId 会失败；此时会 fallback 到 arg0/arg1 形式。</p>
 *
 * @author distributed-idempotent-starter
 */
public final class SpELUtils {

    private SpELUtils() {}

    /** 参数名发现器（spring-core 提供，支持反射 + LocalVariableTable） */
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    /** SpEL 解析器（线程安全，单例即可） */
    private static final ExpressionParser EXPRESSION_PARSER = new SpelExpressionParser();

    /**
     * 方法签名 → 参数名数组 的缓存
     * key: ClassName#MethodName(paramType1,paramType2,...)
     */
    private static final ConcurrentMap<String, String[]> PARAM_NAMES_CACHE = new ConcurrentHashMap<>(256);

    /** SpEL 文本 → 编译后的 Expression 的缓存 */
    private static final ConcurrentMap<String, Expression> EXPRESSION_CACHE = new ConcurrentHashMap<>(256);

    // ===================== 对外 API =====================

    /**
     * <p>解析 SpEL 表达式并返回 String 结果</p>
     *
     * @param method   当前方法
     * @param args     实参数组
     * @param spelText SpEL 文本，可为空
     * @return 解析后的字符串（null / 空串会返回 null）
     */
    public static String parseToString(Method method, Object[] args, String spelText) {
        Object value = parse(method, args, spelText);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * <p>解析 SpEL 表达式并返回任意对象结果</p>
     */
    public static Object parse(Method method, Object[] args, String spelText) {
        if (spelText == null || spelText.isBlank()) {
            return null;
        }
        try {
            String[] paramNames = resolveParameterNames(method);
            Expression expression = EXPRESSION_CACHE.computeIfAbsent(spelText, EXPRESSION_PARSER::parseExpression);

            EvaluationContext ctx = new StandardEvaluationContext();
            for (int i = 0; i < paramNames.length; i++) {
                ctx.setVariable(paramNames[i], args == null || i >= args.length ? null : args[i]);
            }
            // 额外暴露 #root.method / #root.target 作为备用变量
            ctx.setVariable("method", method);
            ctx.setVariable("target", method.getDeclaringClass());

            return expression.getValue(ctx);
        } catch (Exception ex) {
            throw new IdempotentException("SpEL 解析失败：expression=" + spelText + ", method=" + method, ex);
        }
    }

    // ===================== 内部方法 =====================

    /**
     * 解析参数名并缓存。
     * 若 DefaultParameterNameDiscoverer 返回 null（如未开启 -parameters 且无 LocalVariableTable），
     * 会 fallback 到 arg0 / arg1 / arg2 ... 形式，保证 SpEL 至少可写 #arg0 形式。
     */
    static String[] resolveParameterNames(Method method) {
        String cacheKey = buildMethodKey(method);
        String[] cached = PARAM_NAMES_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String[] discovered = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
        if (discovered == null || discovered.length == 0) {
            // fallback: arg0, arg1, arg2 ...
            discovered = fallbackArgNames(method.getParameterCount());
        }
        PARAM_NAMES_CACHE.put(cacheKey, discovered);
        return discovered;
    }

    static String[] fallbackArgNames(int count) {
        String[] arr = new String[count];
        for (int i = 0; i < count; i++) {
            arr[i] = "arg" + i;
        }
        return arr;
    }

    static String buildMethodKey(Method method) {
        return method.getDeclaringClass().getName() + "#"
                + method.getName() + "("
                + Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .reduce((a, b) -> a + "," + b)
                .orElse("")
                + ")";
    }

    /** 仅供单元测试使用：清空缓存 */
    public static void clearCache() {
        PARAM_NAMES_CACHE.clear();
        EXPRESSION_CACHE.clear();
    }
}
