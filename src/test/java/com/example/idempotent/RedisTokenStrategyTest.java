package com.example.idempotent;

import com.example.idempotent.exception.IdempotentException;
import com.example.idempotent.properties.IdempotentProperties;
import com.example.idempotent.strategy.IdempotentStrategyEnum;
import com.example.idempotent.strategy.IdempotentStrategy.StrategyContext;
import com.example.idempotent.strategy.RedisTokenStrategy;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <p>RedisTokenStrategy 单元测试 —— Mockito mock StringRedisTemplate + DefaultRedisScript</p>
 *
 * <p>覆盖场景：</p>
 * <ul>
 *     <li>✅ generateToken 正常写入 Redis</li>
 *     <li>✅ beforeInvoke —— Lua 返回 1 放行成功</li>
 *     <li>✅ beforeInvoke —— Lua 返回 0（token 不存在）抛 IdempotentException</li>
 *     <li>✅ beforeInvoke —— Lua 返回 -1（token 不匹配）抛 IdempotentException</li>
 *     <li>✅ beforeInvoke —— clientToken 为空抛异常</li>
 * </ul>
 */
class RedisTokenStrategyTest {

    private StringRedisTemplate redisTemplate;
    private DefaultRedisScript<Long> luaScript;
    private IdempotentProperties properties;
    private RedisTokenStrategy strategy;

    @BeforeEach
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        luaScript = new DefaultRedisScript<>();
        luaScript.setScriptText("return redis.call('GET', KEYS[1])");
        luaScript.setResultType(Long.class);

        properties = new IdempotentProperties();
        properties.setPrefix("idempotent:");
        properties.setTokenPath("/idempotent/token");

        strategy = new RedisTokenStrategy(redisTemplate, luaScript, properties);
    }

    // ===================== generateToken =====================

    @Test
    @DisplayName("generateToken —— 正常写入 Redis 返回非空 UUID")
    void generateTokenOk() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);

        String token = strategy.generateToken(null);
        assertNotNull(token);
        assertEquals(32, token.length()); // UUID 去横线后 32 位
        verify(ops).setIfAbsent(startsWith("idempotent:token:"), eq(token), eq(300L), any());
    }

    @Test
    @DisplayName("generateToken —— 带 businessKey 作为 value")
    void generateTokenWithBusinessKey() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);

        String token = strategy.generateToken("user123_order456");
        assertNotNull(token);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(ops).setIfAbsent(anyString(), valueCaptor.capture(), anyLong(), any());
        assertEquals("user123_order456", valueCaptor.getValue());
    }

    // ===================== beforeInvoke =====================

    @Test
    @DisplayName("beforeInvoke —— Lua 返回 1 放行成功，返回 handle = clientToken")
    void beforeInvokeAllowed() {
        String clientToken = "abc123";
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(1L);

        StrategyContext ctx = buildCtx(clientToken);
        Object handle = strategy.beforeInvoke(ctx);

        assertEquals(clientToken, handle);

        // 验证 Lua 入参正确：KEYS=[idempotent:token:abc123], ARGV=[abc123]
        ArgumentCaptor<String> keysCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<String>> listCaptor = ArgumentCaptor.forClass(java.util.List.class);
        verify(redisTemplate).execute(eq(luaScript), listCaptor.capture(), eq(clientToken));
        assertEquals(Collections.singletonList("idempotent:token:" + clientToken), listCaptor.getValue());
    }

    @Test
    @DisplayName("beforeInvoke —— Lua 返回 0（token 不存在 / 已消费）抛异常")
    void beforeInvokeTokenMissing() {
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(0L);

        StrategyContext ctx = buildCtx("expired-token");
        IdempotentException ex = assertThrows(IdempotentException.class, () -> strategy.beforeInvoke(ctx));
        assertTrue(ex.getMessage().contains("不存在或已被消费"));
    }

    @Test
    @DisplayName("beforeInvoke —— Lua 返回 -1（token 不匹配 / 伪造）抛异常")
    void beforeInvokeTokenMismatch() {
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(-1L);

        StrategyContext ctx = buildCtx("fake-token");
        IdempotentException ex = assertThrows(IdempotentException.class, () -> strategy.beforeInvoke(ctx));
        assertTrue(ex.getMessage().contains("不匹配"));
    }

    @Test
    @DisplayName("beforeInvoke —— clientToken 为 null 抛异常")
    void beforeInvokeNoToken() {
        StrategyContext ctx = buildCtx(null);
        IdempotentException ex = assertThrows(IdempotentException.class, () -> strategy.beforeInvoke(ctx));
        assertTrue(ex.getMessage().contains("缺少请求头"));
    }

    @Test
    @DisplayName("beforeInvoke —— clientToken 为空串抛异常")
    void beforeInvokeBlankToken() {
        StrategyContext ctx = buildCtx("  ");
        IdempotentException ex = assertThrows(IdempotentException.class, () -> strategy.beforeInvoke(ctx));
        assertTrue(ex.getMessage().contains("缺少请求头"));
    }

    @Test
    @DisplayName("afterInvoke —— 无操作（token 已由 Lua 在 beforeInvoke 中原子删除）")
    void afterInvokeIsNoOp() {
        strategy.afterInvoke(buildCtx("any"), "handle", null);
        verifyNoInteractions(redisTemplate); // 不应该有额外调用
    }

    // ===================== 辅助 =====================

    private StrategyContext buildCtx(String clientToken) {
        java.lang.reflect.Method m = null;
        try {
            m = this.getClass().getMethod("setup");
        } catch (NoSuchMethodException ignored) {}
        return new StrategyContext(
                m != null ? m : RedisTokenStrategyTest.class.getDeclaredMethods()[0],
                this,
                new Object[0],
                buildAnno(IdempotentStrategyEnum.REDIS_TOKEN),
                IdempotentStrategyEnum.REDIS_TOKEN,
                properties.getPrefix(),
                clientToken
        );
    }

    private com.example.idempotent.annotation.Idempotent buildAnno(IdempotentStrategyEnum strategy) {
        return new com.example.idempotent.annotation.Idempotent() {
            @Override public long timeout() { return 5; }
            @Override public java.util.concurrent.TimeUnit unit() { return java.util.concurrent.TimeUnit.SECONDS; }
            @Override public IdempotentStrategyEnum strategy() { return strategy; }
            @Override public String key() { return ""; }
            @Override public boolean releaseAfterMethod() { return false; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return com.example.idempotent.annotation.Idempotent.class;
            }
        };
    }
}
