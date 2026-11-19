package com.likkho.app

import android.content.ComponentCallbacks2

enum class TrimMemoryAction {
    KEEP,
    TRIM_HALF,
    CLEAR_ALL
}

interface BitmapKeyLifecycle {
    fun acquire(key: String)
    fun release(key: String)
}

object BitmapCachePolicy {
    const val MIN_CACHE_BYTES = 4 * 1024 * 1024 // 4 MB floor for tiny test/low-spec JVMs
    const val CACHE_MEMORY_DIVISOR = 8L

    /**
     * Returns 1/8 of [maxMemoryBytes] clamped cleanly to positive Int range.
     */
    fun computeMaxSizeBytes(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Int {
        val eighth = (maxMemoryBytes / CACHE_MEMORY_DIVISOR)
            .coerceIn(MIN_CACHE_BYTES.toLong(), Int.MAX_VALUE.toLong())
        return eighth.toInt()
    }

    fun classifyTrimMemory(level: Int): TrimMemoryAction {
        return when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> TrimMemoryAction.CLEAR_ALL
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> TrimMemoryAction.TRIM_HALF
            else -> TrimMemoryAction.KEEP
        }
    }
}

/**
 * Tracks reference counts for bitmap keys currently bound to active on-screen Composables
 * so off-screen or disposed bitmaps are evicted immediately rather than lingering in RAM.
 */
class ScreenBitmapTracker {
    private val refCounts = HashMap<String, Int>()

    @Synchronized
    fun acquire(key: String) {
        if (key.isBlank()) return
        refCounts[key] = (refCounts[key] ?: 0) + 1
    }

    /**
     * Decrements reference count for [key] and returns true if no active UI slot still uses it.
     */
    @Synchronized
    fun release(key: String): Boolean {
        if (key.isBlank()) return false
        val next = (refCounts[key] ?: 0) - 1
        return if (next <= 0) {
            refCounts.remove(key)
            true
        } else {
            refCounts[key] = next
            false
        }
    }

    @Synchronized
    fun isActive(key: String): Boolean = (refCounts[key] ?: 0) > 0

    @Synchronized
    fun activeKeys(): Set<String> = refCounts.keys.toSet()

    @Synchronized
    fun clear() {
        refCounts.clear()
    }
}

/**
 * Pure Kotlin byte-size bounded LRU cache matching android.util.LruCache semantics for JVM tests
 * and byte-budget verification without requiring Android framework Bitmap stubs.
 */
internal class ByteBoundedLruCache<V : Any>(
    val maxSizeBytes: Int,
    private val sizeOfValue: (String, V) -> Int
) {
    private val map = LinkedHashMap<String, V>(0, 0.75f, true)
    var sizeBytes: Int = 0
        private set

    init {
        require(maxSizeBytes > 0) { "maxSizeBytes <= 0" }
    }

    @Synchronized
    fun get(key: String): V? = map[key]

    @Synchronized
    fun put(key: String, value: V): V? {
        val addedSize = sizeOfValue(key, value).coerceAtLeast(1)
        val previous = map.put(key, value)
        sizeBytes += addedSize
        if (previous != null) {
            sizeBytes -= sizeOfValue(key, previous).coerceAtLeast(1)
        }
        trimToSize(maxSizeBytes)
        return previous
    }

    @Synchronized
    fun remove(key: String): V? {
        val prev = map.remove(key) ?: return null
        sizeBytes -= sizeOfValue(key, prev).coerceAtLeast(1)
        return prev
    }

    @Synchronized
    fun trimToSize(maxBytes: Int) {
        val iter = map.entries.iterator()
        while (sizeBytes > maxBytes && iter.hasNext()) {
            val entry = iter.next()
            val entrySize = sizeOfValue(entry.key, entry.value).coerceAtLeast(1)
            iter.remove()
            sizeBytes -= entrySize
        }
    }

    @Synchronized
    fun evictAll() {
        map.clear()
        sizeBytes = 0
    }

    @Synchronized
    fun keysSnapshot(): Set<String> = map.keys.toSet()

    @Synchronized
    fun count(): Int = map.size
}
