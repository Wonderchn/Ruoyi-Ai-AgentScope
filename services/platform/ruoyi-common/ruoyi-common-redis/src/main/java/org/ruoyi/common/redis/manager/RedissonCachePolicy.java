package org.ruoyi.common.redis.manager;

/**
 * 单个缓存名的策略：TTL、最大空闲时间与最大容量。
 * <p>
 * Redisson 4 移除了 {@code org.redisson.spring.cache.CacheConfig}（以及整个 Spring Cache 支持），
 * 本类承接原类型的语义，使 {@code CacheNames} 中既有的
 * {@code name#ttl#maxIdle#maxSize#local} 写法保持可用：
 * <ul>
 *   <li>{@code ttl} 为 0 表示不过期；</li>
 *   <li>{@code maxIdleTime} 为 0 表示不按空闲淘汰；</li>
 *   <li>{@code maxSize} 为 0 表示不限制容量；</li>
 *   <li>三者均为 0 时使用普通 {@code RMap}，否则使用带 TTL/容量的 {@code RMapCache}。</li>
 * </ul>
 */
public class RedissonCachePolicy {

    private long ttl;

    private long maxIdleTime;

    private int maxSize;

    public RedissonCachePolicy() {
    }

    public RedissonCachePolicy(long ttl, long maxIdleTime, int maxSize) {
        this.ttl = ttl;
        this.maxIdleTime = maxIdleTime;
        this.maxSize = maxSize;
    }

    public long getTTL() {
        return ttl;
    }

    public void setTTL(long ttl) {
        this.ttl = ttl;
    }

    public long getMaxIdleTime() {
        return maxIdleTime;
    }

    public void setMaxIdleTime(long maxIdleTime) {
        this.maxIdleTime = maxIdleTime;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

}
