package com.example.idempotent.config;

import com.example.idempotent.aspect.IdempotentAspect;
import com.example.idempotent.properties.IdempotentProperties;
import com.example.idempotent.strategy.DistributedLockStrategy;
import com.example.idempotent.strategy.IdempotentStrategy;
import com.example.idempotent.strategy.IdempotentStrategyEnum;
import com.example.idempotent.strategy.RedisTokenStrategy;
import com.example.idempotent.strategy.SpelFingerprintStrategy;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * <p>幂等组件自动装配类 —— Spring Boot 3.x 通过
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 触发</p>
 *
 * <h3>装配矩阵</h3>
 * <table>
 * <tr><th>条件</th><th>装配内容</th></tr>
 * <tr><td>{@code idempotent.enabled=true}（默认）+ 类路径有 StringRedisTemplate</td><td>Properties + Lua 脚本 + REDIS_TOKEN / SPEL_FINGERPRINT 策略 + Aspect</td></tr>
 * <tr><td>宿主工程未引入 redisson-spring-boot-starter</td><td>DISTRIBUTED_LOCK 策略不注册（避免启动报错）</td></tr>
 * </table>
 *
 * <p>全局异常处理器 {@code GlobalIdempotentExceptionHandler} 见独立类，
 * 使用 {@code spring-boot-starter-web} 的 RestControllerAdvice（已 optional 引入）。</p>
 *
 * @author distributed-idempotent-starter
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "idempotent", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IdempotentProperties.class)
public class IdempotentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAutoConfiguration.class);

    // ===================== Lua 脚本 Bean =====================

    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    public DefaultRedisScript<Long> verifyTokenScript() {
        String lua = loadLuaScript("lua/verify_token.lua");
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }

    // ===================== 策略 Bean =====================

    /**
     * REDIS_TOKEN 策略 —— 宿主必须有 StringRedisTemplate
     */
    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public RedisTokenStrategy redisTokenStrategy(StringRedisTemplate redisTemplate,
                                                  DefaultRedisScript<Long> script,
                                                  IdempotentProperties properties) {
        log.info("[Idempotent] 注册 REDIS_TOKEN 策略");
        return new RedisTokenStrategy(redisTemplate, script, properties);
    }

    /**
     * SPEL_FINGERPRINT 策略 —— 同样依赖 StringRedisTemplate
     */
    @Bean
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public SpelFingerprintStrategy spelFingerprintStrategy(StringRedisTemplate redisTemplate,
                                                            IdempotentProperties properties) {
        log.info("[Idempotent] 注册 SPEL_FINGERPRINT 策略");
        return new SpelFingerprintStrategy(redisTemplate, properties);
    }

    /**
     * DISTRIBUTED_LOCK 策略 —— 宿主必须有 RedissonClient
     * redisson-spring-boot-starter 是 optional 依赖，这里必须加 ConditionalOnBean 才能安全注册
     */
    @Bean
    @ConditionalOnBean(RedissonClient.class)
    @ConditionalOnMissingBean
    public DistributedLockStrategy distributedLockStrategy(RedissonClient redissonClient,
                                                            IdempotentProperties properties) {
        log.info("[Idempotent] 注册 DISTRIBUTED_LOCK 策略（Redisson 可用）");
        return new DistributedLockStrategy(redissonClient, properties);
    }

    // ===================== 策略注册表 =====================

    /**
     * 把所有 IdempotentStrategy Bean 收集到 Map 里，key = strategyEnum.name()
     */
    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    public Map<String, IdempotentStrategy> idempotentStrategyRegistry(List<IdempotentStrategy> strategies) {
        Map<String, IdempotentStrategy> registry = new HashMap<>();
        for (IdempotentStrategy s : strategies) {
            registry.put(s.getStrategyType().name(), s);
        }
        log.info("[Idempotent] 策略注册表已构建，可用策略：{}", registry.keySet());
        return registry;
    }

    // ===================== 切面 Bean =====================

    /**
     * 幂等切面 —— 需要策略注册表（Spring Web 已在 pom 作为 required 依赖引入）
     */
    @Bean
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnMissingBean
    public IdempotentAspect idempotentAspect(
            Map<String, IdempotentStrategy> strategyRegistry,
            IdempotentProperties properties) {
        return new IdempotentAspect(strategyRegistry, properties);
    }

    // ===================== Lua 脚本加载辅助 =====================

    private String loadLuaScript(String classpath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpath)) {
            if (is == null) {
                throw new IllegalStateException("Lua 脚本不存在: " + classpath);
            }
            try (Scanner s = new Scanner(is).useDelimiter("\\A")) {
                return s.hasNext() ? s.next() : "";
            }
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Lua 脚本失败: " + classpath, ex);
        }
    }
}
