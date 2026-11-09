package com.example.idempotent;

import com.example.idempotent.exception.IdempotentException;
import com.example.idempotent.properties.IdempotentProperties;
import com.example.idempotent.strategy.IdempotentStrategyEnum;
import com.example.idempotent.strategy.IdempotentStrategy.StrategyContext;
import com.example.idempotent.strategy.SpelFingerprintStrategy;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <p>SpelFingerprintStrategy 单元测试 —— Mockito mock StringRedisTemplate</p>
 *
 * <p>覆盖场景：</p>
 * <ul>
 *     <li>✅ setIfAbsent 返回 true → 首次请求放行</li>
 *     <li>✅ setIfAbsent 返回 false → 重复请求抛 IdempotentException</li>
 *     <li>✅ releaseAfterMethod=false → afterInvoke 不 DEL</li>
 *     <li>✅ releaseAfterMethod=true → afterInvoke 主动 DEL</li>
 *     <li>✅ SpEL 为空时抛异常</li>
 * </ul>
 */
class SpelFingerprintStrategyTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private IdempotentProperties properties;
    private SpelFingerprintStrategy strategy;

    @BeforeEach
    void setup() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        properties = new IdempotentProperties();
        properties.setPrefix("idempotent:");
        strategy = new SpelFingerprintStrategy(redisTemplate, properties);
    }

    // ===================== 首次请求（放行） =====================

    @Test
    @DisplayName("首次请求 setIfAbsent=true → 放行")
    void firstRequestAllowed() throws Exception {
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);

        Method m = TestService.class.getMethod("submit", String.class, String.class);
        StrategyContext ctx = buildCtx(m, new Object[] {"u001", "o123"}, "#userId + '_' + #orderNo", false);

        Object handle = strategy.beforeInvoke(ctx);

        assertNotNull(handle);
        assertTrue(((String) handle).startsWith("idempotent:fp:"));

        // 验证 setIfAbsent 入参
        verify(valueOps).setIfAbsent(startsWith("idempotent:fp:"), eq("1"), eq(10L), eq(TimeUnit.SECONDS));
    }

    // ===================== 重复请求（拦截） =====================

    @Test
    @DisplayName("重复请求 setIfAbsent=false → 抛 IdempotentException")
    void duplicateRequestBlocked() throws Exception {
        when(valueOps.setIfAbsent(anyString(), eq("1"), anyLong(), any())).thenReturn(false);

        Method m = TestService.class.getMethod("submit", String.class, String.class);
        StrategyContext ctx = buildCtx(m, new Object[] {"u001", "o123"}, "#userId", false);

        IdempotentException ex = assertThrows(IdempotentException.class, () -> strategy.beforeInvoke(ctx));
        assertTrue(ex.getMessage().contains("重复请求"));
    }

    @Test
    @DisplayName("重复请求 setIfAbsent=null → 抛 IdempotentException")
    void duplicateWhenReturnsNull() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(null);

        Method m = TestService.class.getMethod("submit", String.class, String.class);
        StrategyContext ctx = buildCtx(m, new Object[] {"u001", "o123"}, "#userId", false);

        assertThrows(IdempotentException.class, () -> strategy.beforeInvoke(ctx));
    }

    // ===================== afterInvoke 行为 =====================

    @Test
    @DisplayName("releaseAfterMethod=false → afterInvoke 不调 redisTemplate.delete")
    void afterInvokeDefaultNoDelete() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        StrategyContext ctx = buildCtx(m, new Object[] {"u001", "o123"}, "#userId", false);

        strategy.afterInvoke(ctx, "idempotent:fp:abc", null);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("releaseAfterMethod=true → afterInvoke 主动 DEL")
    void afterInvokeReleaseDelete() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        StrategyContext ctx = buildCtx(m, new Object[] {"u001", "o123"}, "#userId", true);

        strategy.afterInvoke(ctx, "idempotent:fp:abc", null);
        verify(redisTemplate).delete("idempotent:fp:abc");
    }

    @Test
    @DisplayName("afterInvoke handle=null → 不做任何事")
    void afterInvokeNullHandle() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        StrategyContext ctx = buildCtx(m, new Object[] {"u001", "o123"}, "#userId", true);

        strategy.afterInvoke(ctx, null, null);
        verifyNoInteractions(redisTemplate);
    }

    // ===================== SpEL 异常 =====================

    @Test
    @DisplayName("SpEL 为空 → 抛异常")
    void emptySpELThrows() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        StrategyContext ctx = buildCtx(m, new Object[] {"u001", "o123"}, "", false);

        IdempotentException ex = assertThrows(IdempotentException.class, () -> strategy.beforeInvoke(ctx));
        assertTrue(ex.getMessage().contains("SpEL key 解析结果为空"));
    }

    // ===================== buildFingerprint / md5Hex 可测 =====================

    @Test
    @DisplayName("buildFingerprint —— 相同输入相同指纹，不同输入不同指纹")
    void fingerprintDeterministic() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        String fp1 = SpelFingerprintStrategy.buildFingerprint(m, "u001");
        String fp2 = SpelFingerprintStrategy.buildFingerprint(m, "u001");
        String fp3 = SpelFingerprintStrategy.buildFingerprint(m, "u002");
        assertEquals(fp1, fp2);
        assertNotEquals(fp1, fp3);
    }

    @Test
    @DisplayName("md5Hex —— 已知输入输出")
    void md5Known() {
        String md5 = SpelFingerprintStrategy.md5Hex("hello");
        // echo -n "hello" | md5 => 5d41402abc4b2a76b9719d911017c592
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5);
    }

    // ===================== 辅助 =====================

    private StrategyContext buildCtx(Method method, Object[] args, String spel, boolean release) {
        return new StrategyContext(
                method,
                this,
                args,
                buildAnno(spel, release),
                IdempotentStrategyEnum.SPEL_FINGERPRINT,
                properties.getPrefix(),
                null
        );
    }

    private com.example.idempotent.annotation.Idempotent buildAnno(String spel, boolean release) {
        return new com.example.idempotent.annotation.Idempotent() {
            @Override public long timeout() { return 10; }
            @Override public java.util.concurrent.TimeUnit unit() { return TimeUnit.SECONDS; }
            @Override public IdempotentStrategyEnum strategy() { return IdempotentStrategyEnum.SPEL_FINGERPRINT; }
            @Override public String key() { return spel; }
            @Override public boolean releaseAfterMethod() { return release; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return com.example.idempotent.annotation.Idempotent.class;
            }
        };
    }

    static class TestService {
        public void submit(String userId, String orderNo) {}
    }
}
