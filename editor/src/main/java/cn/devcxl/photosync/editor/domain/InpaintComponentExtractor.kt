package cn.devcxl.photosync.editor.domain

import kotlin.math.max
import kotlin.math.min

/**
 * Extracts the connected painted blobs of one mask and merges blobs whose 128 px context rects
 * overlap.
 *
 * Merging keeps a dotted brush stroke from turning into one model run per dab, which would both be
 * slower and risk a different context for every dot. The returned rects are tight mask bounds, in
 * top-left reading order, and are the exact input of [InpaintCropPlanner].
 */
internal class InpaintComponentExtractor(
    private val contextMarginPx: Int = InpaintCropPlanner.CONTEXT_MARGIN_PX,
    private val paintedThreshold: Int = PAINTED_THRESHOLD
) {

    fun extract(mask: InpaintMaskBuffer): List<InpaintPixelRect> {
        val blobs = extractConnected(mask)
        if (blobs.size <= 1) return blobs
        val contexts = blobs.map {
            it.expandedBy(contextMarginPx).clampedTo(mask.width, mask.height)
        }
        val parent = IntArray(blobs.size) { it }
        for (i in blobs.indices) {
            for (j in i + 1 until blobs.size) {
                if (contexts[i].intersects(contexts[j])) union(parent, i, j)
            }
        }
        val groups = LinkedHashMap<Int, MutableList<InpaintPixelRect>>()
        for (index in blobs.indices) {
            groups.getOrPut(find(parent, index)) { mutableListOf() }.add(blobs[index])
        }
        return groups.values
            .map { group -> group.reduce(InpaintPixelRect::union) }
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun extractConnected(mask: InpaintMaskBuffer): List<InpaintPixelRect> {
        val width = mask.width
        val height = mask.height
        val labels = IntArray(width * height) { UNLABELLED }
        val blobs = ArrayList<InpaintPixelRect>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!isPainted(mask, x, y) || labels[y * width + x] != UNLABELLED) continue
                val label = blobs.size
                val bounds = floodFill(mask, labels, x, y, label)
                blobs.add(bounds)
            }
        }
        return blobs
    }

    private fun floodFill(
        mask: InpaintMaskBuffer,
        labels: IntArray,
        seedX: Int,
        seedY: Int,
        label: Int
    ): InpaintPixelRect {
        val width = mask.width
        val height = mask.height
        var left = seedX
        var top = seedY
        var right = seedX + 1
        var bottom = seedY + 1
        val stack = SeedStack()
        val seed = IntArray(2)
        stack.push(seedX, seedY)
        while (!stack.isEmpty) {
            stack.pop(seed)
            val x = seed[0]
            val y = seed[1]
            if (!isPainted(mask, x, y) || labels[y * width + x] != UNLABELLED) continue
            var spanLeft = x
            while (spanLeft > 0 &&
                isPainted(mask, spanLeft - 1, y) &&
                labels[y * width + spanLeft - 1] == UNLABELLED
            ) {
                spanLeft--
            }
            var spanRight = x
            while (spanRight < width - 1 &&
                isPainted(mask, spanRight + 1, y) &&
                labels[y * width + spanRight + 1] == UNLABELLED
            ) {
                spanRight++
            }
            for (spanX in spanLeft..spanRight) {
                labels[y * width + spanX] = label
            }
            left = min(left, spanLeft)
            right = max(right, spanRight + 1)
            top = min(top, y)
            bottom = max(bottom, y + 1)
            val scanFrom = (spanLeft - 1).coerceAtLeast(0)
            val scanTo = (spanRight + 1).coerceAtMost(width - 1)
            scanNeighbourRow(mask, labels, stack, scanFrom, scanTo, y - 1)
            scanNeighbourRow(mask, labels, stack, scanFrom, scanTo, y + 1)
        }
        return InpaintPixelRect(left, top, right, bottom)
    }

    private fun scanNeighbourRow(
        mask: InpaintMaskBuffer,
        labels: IntArray,
        stack: SeedStack,
        fromX: Int,
        toX: Int,
        y: Int
    ) {
        if (y < 0 || y >= mask.height) return
        var inSpan = false
        for (x in fromX..toX) {
            val eligible = isPainted(mask, x, y) && labels[y * mask.width + x] == UNLABELLED
            if (eligible && !inSpan) {
                stack.push(x, y)
                inSpan = true
            } else if (!eligible) {
                inSpan = false
            }
        }
    }

    private fun isPainted(mask: InpaintMaskBuffer, x: Int, y: Int): Boolean =
        mask.coverageAt(x, y) >= paintedThreshold

    private fun find(parent: IntArray, index: Int): Int {
        var current = index
        while (parent[current] != current) {
            parent[current] = parent[parent[current]]
            current = parent[current]
        }
        return current
    }

    private fun union(parent: IntArray, first: Int, second: Int) {
        val rootFirst = find(parent, first)
        val rootSecond = find(parent, second)
        if (rootFirst != rootSecond) parent[rootSecond] = rootFirst
    }

    private class SeedStack {
        private var data = IntArray(INITIAL_SEEDS * 2)
        private var size = 0

        val isEmpty: Boolean
            get() = size == 0

        fun push(x: Int, y: Int) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = x
            data[size++] = y
        }

        fun pop(destination: IntArray) {
            size -= 2
            destination[0] = data[size]
            destination[1] = data[size + 1]
        }
    }

    private companion object {
        const val PAINTED_THRESHOLD = 128
        const val UNLABELLED = -1
        const val INITIAL_SEEDS = 1024
    }
}
