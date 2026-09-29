package org.chorus.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内限流 — 固定窗口计数(单实例适用;多实例需换 Redis 等共享实现)。
 *
 * <p>带过期清理:每个桶记录窗口时长,累计到一定请求数后主动清除已过期的桶,
 * 避免限流 key 无限增长造成内存泄漏(见 {@link #maybeCleanExpired()})。
 */
@Service
public class RateLimitService {

    private final Map<String, Window> buckets = new ConcurrentHashMap<>();
    private final AtomicLong callCount = new AtomicLong();

    /** 每累计多少次 tryAcquire 触发一次过期桶清理 */
    private static final long CLEAN_INTERVAL = 200;

    /**
     * 尝试获取一次配额。
     *
     * @param key      限流标识(如 "login:"+ip / "msg:"+userId)
     * @param limit    窗口内允许的最大次数
     * @param window   窗口时长
     * @return true=放行; false=超限
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();

        Window w = buckets.compute(key, (k, cur) -> {
            if (cur == null || now - cur.windowStart >= windowMillis) {
                return new Window(now, windowMillis, 1);
            }
            cur.count++;
            return cur;
        });

        if (callCount.incrementAndGet() % CLEAN_INTERVAL == 0) {
            cleanExpired();
        }
        return w.count <= limit;
    }

    /** 清除所有已过期(超过 2 个窗口时长)的桶,防止内存泄漏 */
    void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Window>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Window w = it.next().getValue();
            if (now - w.windowStart >= 2L * w.windowMillis) {
                it.remove();
            }
        }
    }

    /** 供测试/管理使用:当前桶数量 */
    int bucketCount() {
        return buckets.size();
    }

    private static final class Window {
        final long windowStart;
        final long windowMillis;
        int count;

        Window(long windowStart, long windowMillis, int count) {
            this.windowStart = windowStart;
            this.windowMillis = windowMillis;
            this.count = count;
        }
    }
}
