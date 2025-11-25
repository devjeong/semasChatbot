package org.dev.semaschatbot

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 스레드 안전한 LRU (Least Recently Used) 캐시 구현입니다.
 * 자주 사용되는 항목만 메모리에 보관하고, 오래된 항목은 자동으로 제거합니다.
 * 
 * @param maxSize 최대 캐시 크기 (기본값: 5000)
 * @param T 캐시에 저장될 항목의 타입
 */
class LRUCache<K, V>(
    private val maxSize: Int = 5000
) {
    // LinkedHashMap을 활용한 LRU 캐시 (accessOrder = true로 설정하여 최근 사용 순서 유지)
    private val cache = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxSize
        }
    }
    
    // 스레드 안전성을 위한 읽기/쓰기 락
    private val lock = ReentrantReadWriteLock()
    
    /**
     * 캐시에서 값을 가져옵니다.
     * @param key 조회할 키
     * @return 값이 있으면 해당 값, 없으면 null
     */
    fun get(key: K): V? {
        return lock.read {
            cache[key]
        }
    }
    
    /**
     * 캐시에 값을 저장합니다.
     * @param key 저장할 키
     * @param value 저장할 값
     * @return 이전에 저장된 값 (없으면 null)
     */
    fun put(key: K, value: V): V? {
        return lock.write {
            cache.put(key, value)
        }
    }
    
    /**
     * 캐시에서 값을 제거합니다.
     * @param key 제거할 키
     * @return 제거된 값 (없으면 null)
     */
    fun remove(key: K): V? {
        return lock.write {
            cache.remove(key)
        }
    }
    
    /**
     * 캐시에 키가 존재하는지 확인합니다.
     * @param key 확인할 키
     * @return 존재하면 true, 없으면 false
     */
    fun containsKey(key: K): Boolean {
        return lock.read {
            cache.containsKey(key)
        }
    }
    
    /**
     * 캐시의 모든 키를 반환합니다.
     * @return 키 컬렉션 (스냅샷)
     */
    fun keys(): Set<K> {
        return lock.read {
            cache.keys.toSet()
        }
    }
    
    /**
     * 캐시의 모든 값을 반환합니다.
     * @return 값 컬렉션 (스냅샷)
     */
    fun values(): Collection<V> {
        return lock.read {
            cache.values.toList()
        }
    }
    
    /**
     * 캐시의 크기를 반환합니다.
     * @return 현재 캐시에 저장된 항목 수
     */
    fun size(): Int {
        return lock.read {
            cache.size
        }
    }
    
    /**
     * 캐시를 완전히 비웁니다.
     */
    fun clear() {
        lock.write {
            cache.clear()
        }
    }
    
    /**
     * 캐시 통계 정보를 반환합니다.
     * @return 통계 정보 맵
     */
    fun getStats(): Map<String, Any> {
        return lock.read {
            mapOf(
                "size" to cache.size,
                "maxSize" to maxSize,
                "usage" to (cache.size.toDouble() / maxSize * 100).let { String.format("%.1f", it) }
            )
        }
    }
}

