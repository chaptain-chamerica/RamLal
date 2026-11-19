package com.likkho.app

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapMemoryCacheTest {

    private data class FakeBitmap(val id: String, val byteCount: Int)

    @Test
    fun computeMaxSizeBytes_isOneEighthOfRuntimeMaxMemory() {
        val heap256Mb = 256L * 1024L * 1024L
        assertEquals(32 * 1024 * 1024, BitmapCachePolicy.computeMaxSizeBytes(heap256Mb))

        val heap512Mb = 512L * 1024L * 1024L
        assertEquals(64 * 1024 * 1024, BitmapCachePolicy.computeMaxSizeBytes(heap512Mb))
    }

    @Test
    fun byteBoundedCache_24LargeImages_neverExceedsOneEighthHeapBudget() {
        val maxCacheBytes = BitmapCachePolicy.computeMaxSizeBytes(256L * 1024L * 1024L) // 32 MB
        val tenMb = 10 * 1024 * 1024
        val cache = ByteBoundedLruCache<FakeBitmap>(maxCacheBytes) { _, v -> v.byteCount }

        for (i in 1..24) {
            cache.put("img_$i", FakeBitmap("img_$i", tenMb))
        }

        // Old LruCache(24) would hold all 24 images (~240 MB); byte-bounded holds at most 3 (30 MB <= 32 MB)
        assertTrue("Cache sizeBytes (${cache.sizeBytes}) must stay <= $maxCacheBytes", cache.sizeBytes <= maxCacheBytes)
        assertEquals(3, cache.count())
        assertNull("Oldest image img_1 must be evicted", cache.get("img_1"))
        assertEquals("img_24", cache.get("img_24")?.id)
    }

    @Test
    fun screenBitmapTracker_retainsOnlyOnScreenKeys_andEvictsOnRelease() {
        val tracker = ScreenBitmapTracker()
        val cache = ByteBoundedLruCache<FakeBitmap>(32 * 1024 * 1024) { _, v -> v.byteCount }

        // Workspace displays img_1 (and studio also references img_1 concurrently -> refCount 2)
        tracker.acquire("img_1")
        tracker.acquire("img_1")
        tracker.acquire("img_2")
        cache.put("img_1", FakeBitmap("img_1", 4 * 1024 * 1024))
        cache.put("img_2", FakeBitmap("img_2", 4 * 1024 * 1024))

        // Studio closes -> first release keeps img_1 active because Canvas still shows it
        assertFalse(tracker.release("img_1"))
        assertTrue(tracker.isActive("img_1"))

        // Navigating off screen disposes img_1 and img_2 -> both released & evicted immediately
        if (tracker.release("img_1")) cache.remove("img_1")
        if (tracker.release("img_2")) cache.remove("img_2")

        assertEquals(0, cache.count())
        assertEquals(0, cache.sizeBytes)
        assertTrue(tracker.activeKeys().isEmpty())
    }

    @Test
    fun trimMemory_lowMemoryClearsAll_moderateTrimsHalf() {
        val cache = ByteBoundedLruCache<FakeBitmap>(10 * 1024 * 1024) { _, v -> v.byteCount }
        for (i in 1..4) {
            cache.put("k$i", FakeBitmap("k$i", 2 * 1024 * 1024))
        }
        assertEquals(8 * 1024 * 1024, cache.sizeBytes)

        assertEquals(TrimMemoryAction.TRIM_HALF, BitmapCachePolicy.classifyTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        cache.trimToSize(cache.maxSizeBytes / 2)
        assertTrue(cache.sizeBytes <= 5 * 1024 * 1024)

        assertEquals(TrimMemoryAction.CLEAR_ALL, BitmapCachePolicy.classifyTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertEquals(TrimMemoryAction.CLEAR_ALL, BitmapCachePolicy.classifyTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertEquals(TrimMemoryAction.CLEAR_ALL, BitmapCachePolicy.classifyTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertEquals(TrimMemoryAction.CLEAR_ALL, BitmapCachePolicy.classifyTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))

        cache.evictAll()
        assertEquals(0, cache.sizeBytes)
        assertEquals(0, cache.count())
    }
}
