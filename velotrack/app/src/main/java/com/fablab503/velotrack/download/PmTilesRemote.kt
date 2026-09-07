package com.fablab503.velotrack.download

import com.fablab503.velotrack.pmtiles.DirEntry
import com.fablab503.velotrack.pmtiles.Gzip
import com.fablab503.velotrack.pmtiles.Mercator
import com.fablab503.velotrack.pmtiles.PmDirectory
import com.fablab503.velotrack.pmtiles.PmHeader
import com.fablab503.velotrack.pmtiles.RangePlanner
import com.fablab503.velotrack.pmtiles.TileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * One tile to extract. [offset] is the ABSOLUTE archive offset of its (compressed) blob, i.e.
 * `header.tileDataOffset + entry.offset`; several tiles may share one blob.
 */
data class PlannedTile(val z: Int, val x: Int, val y: Int, val offset: Long, val length: Int)

/**
 * Result of [PmTilesRemote.plan]: the tiles to write, the coalesced absolute byte ranges to fetch
 * (sorted, non-overlapping), the exact number of bytes those ranges transfer and the number of
 * distinct blobs.
 */
data class ExtractPlan(
    val tiles: List<PlannedTile>,
    val ranges: List<LongRange>,
    val totalBytes: Long,
    val uniqueBlobs: Int,
)

/**
 * Reads a remote PMTiles v3 archive through HTTP range requests: header + root directory once,
 * leaf directories on demand (cached), then plans and downloads exactly the tiles of a bounding
 * box / zoom range. Mirrors `tools/pmtiles_dryrun.py`.
 */
class PmTilesRemote(private val client: RangeClient, val url: String) {

    private val stateLock = Mutex()
    private var header: PmHeader? = null
    private var rootDir: List<DirEntry>? = null

    /** Leaf directories by offset within the leaf section; small LRU (the planet has ~2 900 leaves). */
    private val leafCache = object : LinkedHashMap<Long, List<DirEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<DirEntry>>?): Boolean =
            size > MAX_CACHED_LEAVES
    }

    /**
     * Fetches bytes 0–16383 (header + root directory per spec), parses the header, decodes the root
     * directory. Idempotent; later calls return the cached header.
     */
    suspend fun open(): PmHeader {
        header?.let { return it }
        return stateLock.withLock {
            header?.let { return@withLock it }
            val head = client.fetch(url, 0L, HEAD_BYTES - 1)
            if (head.size < HEADER_BYTES) throw IOException("Archive too short (${head.size} bytes)")
            val h = PmHeader.parse(head.copyOfRange(0, HEADER_BYTES))
            validate(h)
            val rootStart = h.rootDirOffset
            val rootEnd = rootStart + h.rootDirLength // exclusive
            val rootRaw = if (rootStart >= 0 && rootEnd <= head.size) {
                head.copyOfRange(rootStart.toInt(), rootEnd.toInt())
            } else {
                client.fetch(url, rootStart, rootEnd - 1)
            }
            rootDir = PmDirectory.decode(decompress(rootRaw, h.internalCompression))
            header = h
            h
        }
    }

    /** The archive's JSON metadata (decompressed). */
    suspend fun metadataJson(): String {
        val h = open()
        if (h.metadataLength <= 0L) return "{}"
        val raw = client.fetch(url, h.metadataOffset, h.metadataOffset + h.metadataLength - 1)
        return String(decompress(raw, h.internalCompression), Charsets.UTF_8)
    }

    /**
     * For every zoom in `[zMin, zMax]` (clamped to the archive's zoom range) and every tile of
     * [bbox]: compute the Hilbert tile id, look it up in the root directory, descend into the leaf
     * directory when needed (fetched once, cached), dedupe blobs by offset and coalesce the sorted
     * absolute byte ranges with [RangePlanner.coalesce]. CPU work runs on [Dispatchers.Default].
     */
    suspend fun plan(bbox: Mercator.BBox, zMin: Int, zMax: Int): ExtractPlan = withContext(Dispatchers.Default) {
        val h = open()
        val root = rootDir ?: throw IllegalStateException("Root directory not loaded")
        val lo = maxOf(zMin, h.minZoom)
        val hi = minOf(zMax, h.maxZoom)
        val tiles = ArrayList<PlannedTile>()
        val blobs = HashMap<Long, Int>() // absolute offset -> length
        var visited = 0
        if (lo <= hi) {
            for (z in lo..hi) {
                val (xRanges, yRange) = Mercator.tileRanges(bbox, z)
                for (xr in xRanges) {
                    for (x in xr) {
                        for (y in yRange) {
                            if (++visited % CANCEL_CHECK_INTERVAL == 0) currentCoroutineContext().ensureActive()
                            val id = TileId.fromZxy(z, x, y)
                            val entry = lookup(h, root, id) ?: continue
                            if (entry.length <= 0) continue
                            val abs = h.tileDataOffset + entry.offset
                            tiles.add(PlannedTile(z, x, y, abs, entry.length))
                            val known = blobs[abs]
                            if (known == null || known < entry.length) blobs[abs] = entry.length
                        }
                    }
                }
            }
        }
        val sorted = normalizeRanges(blobs)
        val ranges = RangePlanner.coalesce(sorted)
        val total = ranges.sumOf { it.last - it.first + 1 }
        ExtractPlan(tiles, ranges, total, blobs.size)
    }

    /**
     * Fetches every coalesced range with at most [parallelism] concurrent requests, slices each
     * planned tile's blob out of its chunk and hands `(z, x, y, bytes)` to [sink]. [sink] is always
     * invoked from ONE writer coroutine (fed through a channel) so SQLite writes stay serialized;
     * [onProgress] `(bytesDone, bytesTotal)` may be called from any worker thread.
     */
    suspend fun download(
        plan: ExtractPlan,
        parallelism: Int = 6,
        onProgress: (Long, Long) -> Unit,
        sink: suspend (Int, Int, Int, ByteArray) -> Unit,
    ) {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        val total = plan.totalBytes
        if (plan.ranges.isEmpty()) {
            onProgress(0L, total)
            return
        }
        val groups = groupTilesByRange(plan)
        coroutineScope {
            val channel = Channel<TileBytes>(CHANNEL_CAPACITY)
            val writer = launch {
                for (t in channel) sink(t.z, t.x, t.y, t.bytes)
            }
            val gate = Semaphore(parallelism)
            val done = AtomicLong(0L)
            try {
                coroutineScope {
                    for ((index, range) in plan.ranges.withIndex()) {
                        launch {
                            gate.withPermit {
                                val chunk = client.fetch(url, range.first, range.last)
                                for (t in groups[index]) {
                                    val start = (t.offset - range.first).toInt()
                                    channel.send(TileBytes(t.z, t.x, t.y, chunk.copyOfRange(start, start + t.length)))
                                }
                                onProgress(done.addAndGet(range.last - range.first + 1), total)
                            }
                        }
                    }
                }
            } finally {
                channel.close()
            }
            writer.join()
        }
    }

    // ---- directory lookup -----------------------------------------------------------------------

    private suspend fun lookup(h: PmHeader, root: List<DirEntry>, tileId: Long): DirEntry? {
        var entry = PmDirectory.find(root, tileId) ?: return null
        var depth = 0
        while (entry.runLength == 0) {
            if (++depth > MAX_LEAF_DEPTH) return null
            val leaf = leafDirectory(h, entry)
            entry = PmDirectory.find(leaf, tileId) ?: return null
        }
        return entry
    }

    private suspend fun leafDirectory(h: PmHeader, pointer: DirEntry): List<DirEntry> {
        stateLock.withLock { leafCache[pointer.offset] }?.let { return it }
        val start = h.leafDirsOffset + pointer.offset
        val raw = client.fetch(url, start, start + pointer.length - 1)
        val decoded = PmDirectory.decode(decompress(raw, h.internalCompression))
        stateLock.withLock { leafCache[pointer.offset] = decoded }
        return decoded
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun validate(h: PmHeader) {
        if (h.tileType != TILE_TYPE_MVT) throw IOException("Unsupported tile type ${h.tileType} (expected MVT)")
        if (h.tileCompression != COMPRESSION_GZIP && h.tileCompression != COMPRESSION_NONE &&
            h.tileCompression != COMPRESSION_UNKNOWN
        ) {
            throw IOException("Unsupported tile compression ${h.tileCompression}")
        }
        if (h.internalCompression != COMPRESSION_GZIP && h.internalCompression != COMPRESSION_NONE &&
            h.internalCompression != COMPRESSION_UNKNOWN
        ) {
            throw IOException("Unsupported directory compression ${h.internalCompression}")
        }
    }

    private fun decompress(data: ByteArray, compression: Int): ByteArray =
        if (compression == COMPRESSION_GZIP) Gzip.gunzip(data) else data

    /**
     * Sorted, non-overlapping absolute ranges from the blob map. Only true overlaps are merged
     * here; ADJACENT blobs stay separate so that [RangePlanner.coalesce] (which never splits an
     * input range) can merge them subject to its `maxRange` cap. Blobs of one area are stored
     * contiguously in the clustered archive, so merging adjacency here would produce one huge
     * range per band (no parallelism, one giant allocation, and > [RangeClient.MAX_RANGE_BYTES]
     * fails outright).
     */
    private fun normalizeRanges(blobs: Map<Long, Int>): List<LongRange> {
        if (blobs.isEmpty()) return emptyList()
        val offsets = blobs.keys.toLongArray()
        offsets.sort()
        val out = ArrayList<LongRange>(offsets.size)
        var curFirst = offsets[0]
        var curLast = curFirst + (blobs[curFirst] ?: 0) - 1
        for (i in 1 until offsets.size) {
            val first = offsets[i]
            val last = first + (blobs[first] ?: 0) - 1
            if (first <= curLast) {
                if (last > curLast) curLast = last
            } else {
                out.add(LongRange(curFirst, curLast))
                curFirst = first
                curLast = last
            }
        }
        out.add(LongRange(curFirst, curLast))
        return out
    }

    /** Assigns every planned tile to the (single) range containing its blob. */
    private fun groupTilesByRange(plan: ExtractPlan): List<List<PlannedTile>> {
        val groups = List(plan.ranges.size) { ArrayList<PlannedTile>() }
        val sortedTiles = plan.tiles.sortedBy { it.offset }
        var ri = 0
        for (t in sortedTiles) {
            while (ri < plan.ranges.size && plan.ranges[ri].last < t.offset) ri++
            if (ri >= plan.ranges.size) throw IllegalArgumentException("Tile z${t.z}/${t.x}/${t.y} outside planned ranges")
            val r = plan.ranges[ri]
            if (t.offset < r.first || t.offset + t.length - 1 > r.last) {
                throw IllegalArgumentException("Tile z${t.z}/${t.x}/${t.y} not fully inside range ${r.first}-${r.last}")
            }
            groups[ri].add(t)
        }
        return groups
    }

    private class TileBytes(val z: Int, val x: Int, val y: Int, val bytes: ByteArray)

    companion object {
        /** Spec: header + compressed root directory never exceed 16 384 bytes. */
        const val HEAD_BYTES = 16_384L
        const val HEADER_BYTES = 127

        const val COMPRESSION_UNKNOWN = 0
        const val COMPRESSION_NONE = 1
        const val COMPRESSION_GZIP = 2
        const val TILE_TYPE_MVT = 1

        private const val MAX_LEAF_DEPTH = 4
        private const val MAX_CACHED_LEAVES = 48
        private const val CANCEL_CHECK_INTERVAL = 256
        private const val CHANNEL_CAPACITY = 64
    }
}
