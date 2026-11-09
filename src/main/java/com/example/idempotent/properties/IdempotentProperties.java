package com.example.idempotent.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <p>幂等组件全局配置属性</p>
 *
 * <h3>application.yml 示例</h3>
 * <pre>
 * idempotent:
 *   enabled: true
 *   prefix: "idempotent:"
 *   header-token: "X-Idempotent-Token"
 *   token-path: "/idempotent/token"
 *   default-timeout: 5
 *   default-unit: SECONDS
 *   default-release-after-method: false
 *   strategy:
 *     token-gen-length: 32
 *     token-expire-seconds: 300
 *     fingerprint-charset: UTF-8
 *   redisson:
 *     lock-wait-time: 3
 *     lock-time-unit: SECONDS
 * </pre>
 *
 * @author distributed-idempotent-starter
 */
@Data
@ConfigurationProperties(prefix = "idempotent")
public class IdempotentProperties {

    /**
     * 是否启用幂等组件，默认 true
     */
    private boolean enabled = true;

    /**
     * Redis key 前缀，避免与宿主业务冲突
     */
    private String prefix = "idempotent:";

    /**
     * REDIS_TOKEN 策略：从请求头读取 token 时使用的 header name
     */
    private String headerToken = "X-Idempotent-Token";

    /**
     * REDIS_TOKEN 策略：生成 token 的接口路径（可选，由宿主工程自行实现 Controller）
     */
    private String tokenPath = "/idempotent/token";

    /**
     * 默认过期时间（注解未指定时回退到此值）
     */
    private long defaultTimeout = 5;

    /**
     * 默认时间单位
     */
    private String defaultUnit = "SECONDS";

    /**
     * 默认是否在业务方法结束后释放
     */
    private boolean defaultReleaseAfterMethod = false;

    /**
     * Token 生成相关配置
     */
    private TokenStrategy tokenStrategy = new TokenStrategy();

    /**
     * Redisson 分布式锁相关配置
     */
    private RedissonStrategy redissonStrategy = new RedissonStrategy();

    @Data
    public static class TokenStrategy {
        /**
         * token 随机串长度
         */
        private int tokenGenLength = 32;

        /**
         * token 本身的过期时间（秒）—— 客户端申请 token 后多久内必须使用
         */
        private long tokenExpireSeconds = 300;
    }

    @Data
    public static class RedissonStrategy {
        /**
         * 获取分布式锁的最大等待时间
         */
        private long lockWaitTime = 3;

        /**
         * 锁 leaseTime 时间单位
         */
        private String lockTimeUnit = "SECONDS";
    }
}
