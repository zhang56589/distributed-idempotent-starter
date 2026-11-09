package com.example.idempotent.config;

import com.example.idempotent.exception.IdempotentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>全局幂等异常处理器 —— 独立的自动配置类</p>
 *
 * <p>之所以独立出来：</p>
 * <ul>
 *     <li>宿主工程没有引入 {@code spring-boot-starter-web} 时，DispatcherServlet / RestControllerAdvice 类不存在，
 *         本类通过 {@code @ConditionalOnClass} 自动跳过，不会在非 Web 环境造成启动错误</li>
 *     <li>与 {@link IdempotentAutoConfiguration} 解耦，职责单一</li>
 * </ul>
 *
 * @author distributed-idempotent-starter
 */
@AutoConfiguration(after = IdempotentAutoConfiguration.class)
@ConditionalOnClass({DispatcherServlet.class, RestControllerAdvice.class})
public class GlobalIdempotentExceptionHandlerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GlobalIdempotentExceptionHandler globalIdempotentExceptionHandler() {
        return new GlobalIdempotentExceptionHandler();
    }

    /**
     * 捕获 {@link IdempotentException} → 统一返回友好 JSON
     */
    @RestControllerAdvice
    public static class GlobalIdempotentExceptionHandler {

        private static final Logger log = LoggerFactory.getLogger(GlobalIdempotentExceptionHandler.class);

        /**
         * 幂等异常 —— 409 Conflict 语义（请求冲突/重复）
         */
        @ExceptionHandler(IdempotentException.class)
        public Map<String, Object> handleIdempotentException(IdempotentException ex) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("code", ex.getCode());
            resp.put("message", ex.getMessage());
            resp.put("success", false);
            resp.put("timestamp", System.currentTimeMillis());
            log.warn("[Idempotent] 幂等拦截 -> code={}, msg={}", ex.getCode(), ex.getMessage());
            return resp;
        }
    }
}
