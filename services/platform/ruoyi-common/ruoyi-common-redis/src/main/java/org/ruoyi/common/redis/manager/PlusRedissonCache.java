package org.ruoyi.common.redis.manager;

import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson {@link RMap}/{@link RMapCache} 的 Spring {@link Cache} 实现。
 * <p>
 * Redisson 4 移除了 {@code org.redisson.spring.cache.RedissonCache} 与整个 Spring Cache 模块，
 * 这里按原语义重建：不加 TTL/容量时用 {@link RMap}，否则用 {@link RMapCache}；
 * {@code allowNullValues} 通过显式的 {@link NullValue} 占位对象实现，
 * 使“缓存了 null”与“没有缓存”可区分（原 Redisson 行为一致）。
 * <p>
 * 占位对象使用非 final 类：Redisson 的 {@code TypedJsonJacksonCodec} 以
 * {@code DefaultTyping.NON_FINAL} 写入类型信息，final 类不会带 {@code @class}，
 * 反序列化会退化成 Map 从而丢失占位语义。
 */
public class PlusRedissonCache implements Cache {

    static final String NULL_VALUE_MARKER = NullValue.class.getName();

    private final RMap<Object, Object> map;
    private final RMapCache<Object, Object> mapCache;
    private final String name;
    private final long ttl;
    private final long maxIdleTime;
    private final int maxSize;
    private final boolean allowNullValues;

    public PlusRedissonCache(String name, RMap<Object, Object> map, boolean allowNullValues) {
        this(name, map, null, 0L, 0L, 0, allowNullValues);
    }

    public PlusRedissonCache(String name, RMapCache<Object, Object> mapCache, RedissonCachePolicy policy,
                             boolean allowNullValues) {
        this(name, null, mapCache, policy.getTTL(), policy.getMaxIdleTime(), policy.getMaxSize(), allowNullValues);
    }

    private PlusRedissonCache(String name, RMap<Object, Object> map, RMapCache<Object, Object> mapCache,
                              long ttl, long maxIdleTime, int maxSize, boolean allowNullValues) {
        this.name = name;
        this.map = map != null ? map : mapCache;
        this.mapCache = mapCache;
        this.ttl = ttl;
        this.maxIdleTime = maxIdleTime;
        this.maxSize = maxSize;
        this.allowNullValues = allowNullValues;
    }

    /**
     * 应用容量上限；仅对 {@link RMapCache} 有意义，与迁移前行为一致。
     */
    public void setMaxSize(int maxSize) {
        if (mapCache != null && maxSize > 0) {
            mapCache.setMaxSize(maxSize);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return map;
    }

    /**
     * 读取缓存。
     *
     * <p>WP-039（T4）F-6 修复：原实现写成 {@code toStoreValue(key, false)} —— 把**缓存键**当成了
     * 缓存值返回，于是任何 {@code @Cacheable} 查询都变成"永远命中"，命中的值就是 key 本身：
     * <ul>
     *   <li>key 类型 ≠ 方法返回类型时，CGLIB 代理在返回处插入的 checkcast 直接抛
     *       {@code ClassCastException}（登录即此形态：key 是 clientId 的 {@code String}，
     *       方法声明返回 {@code SysClientVo}）；</li>
     *   <li>key 类型 = 返回类型时更隐蔽：直接返回 key 冒充数据，且因被判定为命中而**从不写入**
     *       真实值（Redis 里也就永远没有该条目）。</li>
     * </ul>
     * 正确语义是"从 map 读"，见下方 {@code map.get(key)}。</p>
     */
    @Override
    public ValueWrapper get(Object key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        return fromStoreValue(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    @Override
    public void put(Object key, Object value) {
        Object storeValue = toStoreValue(value, true);
        if (storeValue == null) {
            // allowNullValues=false 且 value 为 null：与 Spring 语义一致，忽略写入
            return;
        }
        map.put(key, storeValue);
        if (mapCache != null && ttl > 0) {
            mapCache.put(key, storeValue, ttl, TimeUnit.MILLISECONDS, maxIdleTime, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        Object storeValue = toStoreValue(value, true);
        if (storeValue == null) {
            return null;
        }
        Object previous;
        if (mapCache != null && ttl > 0) {
            previous = mapCache.putIfAbsent(key, storeValue, ttl, TimeUnit.MILLISECONDS,
                maxIdleTime, TimeUnit.MILLISECONDS);
        } else {
            previous = map.putIfAbsent(key, storeValue);
        }
        return fromStoreValue(previous);
    }

    @Override
    public void evict(Object key) {
        map.remove(key);
    }

    @Override
    public boolean evictIfPresent(Object key) {
        return map.remove(key) != null;
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public boolean invalidate() {
        boolean wasEmpty = map.isEmpty();
        map.clear();
        return !wasEmpty;
    }

    private Object toStoreValue(Object value, boolean forWrite) {
        if (value == null) {
            return allowNullValues && forWrite ? NullValue.INSTANCE : null;
        }
        return value;
    }

    private ValueWrapper fromStoreValue(Object storeValue) {
        if (storeValue == null) {
            return null;
        }
        if (storeValue instanceof NullValue) {
            return new SimpleValueWrapper(null);
        }
        return new SimpleValueWrapper(storeValue);
    }

    /**
     * 存储 null 的占位对象。非 final 以保证 Jackson 默认类型信息可往返。
     */
    public static class NullValue implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public static final NullValue INSTANCE = new NullValue();

        private String marker = NULL_VALUE_MARKER;

        public String getMarker() {
            return marker;
        }

        public void setMarker(String marker) {
            this.marker = marker;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NullValue;
        }

        @Override
        public int hashCode() {
            return NULL_VALUE_MARKER.hashCode();
        }
    }

}
