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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * <p>高并发幂等测试 —— 100 个线程同时发起相同的幂等请求，只有 1 个成功</p>
 *
 * <h3>测试设计</h3>
 * <pre>
 *   ┌─────────────────────────────────────────────┐
 *   │  100 个线程 ──► CountDownLatch(100) 同时启动  │
 *   │       │                                       │
 *   │       ▼                                       │
 *   │  调用 SpelFingerprintStrategy.beforeInvoke     │
 *   │       │                                       │
 *   │       ▼                                       │
 *   │  ConcurrentMockRedis.setIfAbsent              │
 *   │  ├── compareAndSet(false → true) → 返回 true │ ← 只有 1 个线程成功
 *   │  └── 已被占用 → 返回 false                    │ ← 其余 99 个被拦截
 *   └─────────────────────────────────────────────┘
 * </pre>
 *
 * <p>关键约束：</p>
 * <ul>
 *     <li>使用 ConcurrentHashMap 模拟 Redis 的 setIfAbsent（compareAndSet 原子语义）</li>
 *     <li>CountDownLatch 作为"同时开始"栅栏，避免线程逐个排队</li>
 *     <li>共享计数器用 AtomicInteger，保证测试自身线程安全</li>
 *     <li>业务方法模拟 100ms 耗时，让"执行业务"和"被拦截"时间重叠</li>
 * </ul>
 */
class ConcurrentIdempotentTest {

    // ===================== 并发参数 =====================

    private static final int THREAD_COUNT = 100;           // 并发线程数
    private static final int BUSINESS_SLEEP_MS = 100;      // 业务方法模拟耗时
    private static final long TIMEOUT_SECONDS = 60;        // 测试超时

    // ===================== 真实并发语义的 Mock Redis =====================

    /**
     * <p>并发安全的 Mock StringRedisTemplate —— 用 ConcurrentHashMap + compareAndSet 原子语义</p>
     * <p>关键：setIfAbsent 的实现是 compareAndSet(false → true)，天然原子</p>
     */
    static class ConcurrentMockRedis {
        // key → true（已被占用），CAS 保证只有第一个线程能把 false → true
        private final ConcurrentHashMap<String, Boolean> store = new ConcurrentHashMap<>();

        /**
         * 模拟 Redis SETNX —— 只有首次写入返回 true
         */
        public boolean setIfAbsent(String key) {
            // putIfAbsent 返回旧值；首次为 null → 新写入成功
            return store.putIfAbsent(key, Boolean.TRUE) == null;
        }

        public boolean exists(String key) {
            return store.containsKey(key);
        }

        public void delete(String key) {
            store.remove(key);
        }

        public void clear() {
            store.clear();
        }

        public int size() {
            return store.size();
        }
    }

    // ===================== 测试实例 =====================

    private ConcurrentMockRedis mockRedis;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private SpelFingerprintStrategy strategy;
    private IdempotentProperties properties;

    @BeforeEach
    void setup() {
        mockRedis = new ConcurrentMockRedis();
        properties = new IdempotentProperties();
        properties.setPrefix("idempotent:");

        // Mock StringRedisTemplate —— 把 setIfAbsent 委托给 ConcurrentMockRedis
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), eq("1"), anyLong(), any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return mockRedis.setIfAbsent(key);
        });

        strategy = new SpelFingerprintStrategy(redisTemplate, properties);
    }

    // ===================== 核心测试 =====================

    @Test
    @DisplayName("100 线程并发同一幂等 key —— 只有 1 个通过，99 个被拦截")
    void concurrentOnlyOnePasses() throws Exception {
        // 统计器
        AtomicInteger successCount = new AtomicInteger(0);   // 成功执行业务的线程数
        AtomicInteger blockedCount = new AtomicInteger(0);   // 被幂等拦截的线程数
        AtomicLong businessTotalNs = new AtomicLong(0);      // 业务执行总耗时
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>(null);

        // 栅栏：100 个线程都准备好了再同时启动
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);     // 主线程发令枪
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        // 业务方法 Method 对象
        Method submitMethod = TestService.class.getMethod("submit", String.class, String.class);

        // 固定的 SpEL key —— 所有线程传入完全相同的参数，指纹相同
        final String FIXED_USER_ID = "sameUser";
        final String FIXED_ORDER_NO = "sameOrder";

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                pool.submit(() -> {
                    try {
                        // 1) 报告"我准备好了"
                        readyLatch.countDown();
                        // 2) 等待所有线程就绪后同时开跑
                        startLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                        // 3) 构造上下文 —— 关键：所有线程传完全相同的参数！
                        StrategyContext ctx = new StrategyContext(
                                submitMethod,
                                TestService.class,
                                new Object[] {FIXED_USER_ID, FIXED_ORDER_NO},
                                buildAnno("#userId + '_' + #orderNo", 5),
                                IdempotentStrategyEnum.SPEL_FINGERPRINT,
                                properties.getPrefix(),
                                null
                        );

                        long t0 = System.nanoTime();
                        Object handle = strategy.beforeInvoke(ctx);

                        // 4) 通过了幂等校验 → 执行业务（模拟耗时）
                        Thread.sleep(BUSINESS_SLEEP_MS);
                        long elapsedNs = System.nanoTime() - t0;
                        businessTotalNs.addAndGet(elapsedNs);
                        successCount.incrementAndGet();

                        // 5) afterInvoke（releaseAfterMethod=false，不主动删）
                        strategy.afterInvoke(ctx, handle, null);

                    } catch (IdempotentException ex) {
                        // 幂等拦截 —— 正确路径
                        blockedCount.incrementAndGet();
                    } catch (Throwable ex) {
                        // 非预期异常 —— 记录
                        unexpectedError.compareAndSet(null, ex);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // 主线程等待所有线程就绪后一起发令
            assertTrue(readyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "线程未在超时内全部就绪");

            // 发令：同时开始
            long startWallTime = System.currentTimeMillis();
            startLatch.countDown();

            // 等待所有线程完成
            assertTrue(doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "线程未在超时内全部完成");
            long totalWallMs = System.currentTimeMillis() - startWallTime;

            // ===================== 断言 =====================

            // 1. 总异常数必须为 0
            assertNull(unexpectedError.get(), "出现非预期异常: " + unexpectedError.get());

            // 2. 核心断言 —— 必须恰好 1 个成功，99 个被拦截
            assertEquals(1, successCount.get(),
                    "成功数应该恰好为 1，但实际 = " + successCount.get());
            assertEquals(THREAD_COUNT - 1, blockedCount.get(),
                    "被拦截数应该为 " + (THREAD_COUNT - 1) + "，但实际 = " + blockedCount.get());

            // 3. Redis 中应该只写入了 1 个指纹 key
            assertEquals(1, mockRedis.size(),
                    "Redis 中应该只有 1 个指纹 key，但实际有 " + mockRedis.size());

            // 4. 墙钟时间合理性 —— 1 个线程 sleep 100ms + 线程调度开销，总耗时应 < 300ms
            assertTrue(totalWallMs < 300,
                    "墙钟时间不合理：" + totalWallMs + "ms（业务线程 100ms + 调度开销应 < 300ms）");

            // 5. 业务执行只发生了 1 次 —— businessTotalNs 应约等于 100ms
            long avgBusinessMs = businessTotalNs.get() / 1_000_000;
            assertTrue(avgBusinessMs >= BUSINESS_SLEEP_MS && avgBusinessMs < 300,
                    "业务执行次数非 1 次，平均耗时 = " + avgBusinessMs + "ms");

            // ===================== 输出验证日志 =====================
            System.out.println("┌──────────────────────────────────────────────┐");
            System.out.println("│         Concurrent Idempotent Test            │");
            System.out.println("├──────────────────────────────────────────────┤");
            System.out.printf("│ 并发线程数:      %d%n", THREAD_COUNT);
            System.out.printf("│ 成功数:          %d (✅ 期望=1)%n", successCount.get());
            System.out.printf("│ 被拦截数:        %d (✅ 期望=%d)%n", blockedCount.get(), THREAD_COUNT - 1);
            System.out.printf("│ Redis 指纹数:    %d (✅ 期望=1)%n", mockRedis.size());
            System.out.printf("│ 总墙钟耗时:      %d ms%n", totalWallMs);
            System.out.printf("│ 业务平均耗时:    %d ms%n", avgBusinessMs);
            System.out.println("│ 非预期异常:      无 ✅");
            System.out.println("│ 结论:            幂等语义正确 ✅");
            System.out.println("└──────────────────────────────────────────────┘");

        } finally {
            pool.shutdownNow();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("线程池未正常终止");
            }
        }
    }

    // ===================== 辅助 =====================

    private com.example.idempotent.annotation.Idempotent buildAnno(String spel, long timeout) {
        return new com.example.idempotent.annotation.Idempotent() {
            @Override public long timeout() { return timeout; }
            @Override public java.util.concurrent.TimeUnit unit() { return java.util.concurrent.TimeUnit.SECONDS; }
            @Override public IdempotentStrategyEnum strategy() { return IdempotentStrategyEnum.SPEL_FINGERPRINT; }
            @Override public String key() { return spel; }
            @Override public boolean releaseAfterMethod() { return false; }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return com.example.idempotent.annotation.Idempotent.class;
            }
        };
    }

    static class TestService {
        public void submit(String userId, String orderNo) {}
    }
}
