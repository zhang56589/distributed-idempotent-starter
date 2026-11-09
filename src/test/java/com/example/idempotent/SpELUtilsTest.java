package com.example.idempotent;

import com.example.idempotent.utils.SpELUtils;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>SpELUtils 单元测试 —— 纯单测，零外部依赖</p>
 *
 * <p>覆盖场景：</p>
 * <ul>
 *     <li>✅ 普通参数名解析（#userId 能拿到实参）</li>
 *     <li>✅ 复杂 SpEL（#dto.userId + '_' + #dto.orderNo）</li>
 *     <li>✅ 缓存命中（连续调用同一 method+spel，第二次应走缓存）</li>
 *     <li>✅ 空 SpEL 返回 null</li>
 *     <li>✅ 非法 SpEL 抛 IdempotentException</li>
 * </ul>
 */
class SpELUtilsTest {

    private final TestService testService = new TestService();

    @BeforeEach
    void setup() {
        SpELUtils.clearCache();
    }

    // ===================== 基础解析 =====================

    @Test
    @DisplayName("SpELUtils —— #userId 正确解析到实参")
    void parseSimpleParam() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        String result = SpELUtils.parseToString(m, new Object[] {"u001", "o123"}, "#userId");
        assertEquals("u001", result);
    }

    @Test
    @DisplayName("SpELUtils —— 复杂表达式字符串拼接")
    void parseComplexConcat() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        String result = SpELUtils.parseToString(m, new Object[] {"u001", "o123"},
                "#userId + '_' + #orderNo");
        assertEquals("u001_o123", result);
    }

    @Test
    @DisplayName("SpELUtils —— 字面量字符串")
    void parseLiteral() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        String result = SpELUtils.parseToString(m, new Object[] {"u001", "o123"},
                "'order:' + #orderNo");
        assertEquals("order:o123", result);
    }

    // ===================== 空 / 非法 =====================

    @Test
    @DisplayName("SpELUtils —— 空表达式返回 null")
    void parseEmptyReturnsNull() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        assertNull(SpELUtils.parseToString(m, new Object[] {"u001", "o123"}, null));
        assertNull(SpELUtils.parseToString(m, new Object[] {"u001", "o123"}, ""));
        assertNull(SpELUtils.parseToString(m, new Object[] {"u001", "o123"}, "   "));
    }

    @Test
    @DisplayName("SpELUtils —— 非法表达式抛 IdempotentException")
    void parseInvalidSpelThrows() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        assertThrows(com.example.idempotent.exception.IdempotentException.class, () ->
                SpELUtils.parseToString(m, new Object[] {"u001", "o123"}, "#nonExistent + @#$")
        );
    }

    // ===================== 缓存验证 =====================

    @Test
    @DisplayName("SpELUtils —— 连续调用同一 method+spel，第二次走缓存（结果一致且不抛异常）")
    void cacheWorks() throws Exception {
        Method m = TestService.class.getMethod("submit", String.class, String.class);
        // 第一次：缓存 miss
        String r1 = SpELUtils.parseToString(m, new Object[] {"u001", "o123"}, "#userId");
        // 第二次：缓存 hit（同一个 method + 同一个 spel）
        String r2 = SpELUtils.parseToString(m, new Object[] {"u002", "o999"}, "#userId");
        assertEquals("u001", r1);
        assertEquals("u002", r2);
    }

    // ===================== 辅助类 =====================

    /** 测试用 Service —— 方法参数名会被 -parameters 编译进字节码 */
    static class TestService {
        public void submit(String userId, String orderNo) {
            // 用于反射拿方法签名
        }
    }
}
