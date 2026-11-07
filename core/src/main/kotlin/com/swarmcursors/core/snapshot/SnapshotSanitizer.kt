package com.swarmcursors.core.snapshot

import com.swarmcursors.core.model.NodeSummary
import kotlin.math.abs
import kotlin.math.max

/**
 * Redacts sensitive content from snapshots (spec §10.1):
 *
 *  * nodes whose `inputType` is a password variant get `text = "***"`
 *  * nodes flagged `isPassword` are captured without any text (`text = null`)
 *
 * Behaviour is controlled by `SnapshotConfig.includeSensitiveText` (default
 * `false` = redact).
 */
object SnapshotSanitizer {

    const val MASK = "***"

    fun sanitize(nodes: List<NodeSummary>, includeSensitiveText: Boolean): List<NodeSummary> {
        if (includeSensitiveText) return nodes
        var mutated = false
        val output = ArrayList<NodeSummary>(nodes.size)
        for (node in nodes) {
            val replacement = sanitizeNode(node, includeSensitiveText)
            if (replacement !== node) mutated = true
            output.add(replacement)
        }
        return if (mutated) output else nodes
    }

    fun sanitizeNode(node: NodeSummary, includeSensitiveText: Boolean): NodeSummary {
        if (includeSensitiveText) return node
        if (!node.isSensitive) return node
        if (node.text == null) return node
        return when {
            node.isPassword -> node.copy(text = null)
            else -> node.copy(text = MASK)
        }
    }
}

/**
 * Detects whether the interface is still animating by comparing the bounds of
 * two captures taken [com.swarmcursors.core.model.SnapshotConfig.stabilityIntervalMs]
 * apart (spec §5.2).
 */
object SnapshotStability {

    fun isStable(
        first: List<NodeSummary>,
        second: List<NodeSummary>,
        thresholdPx: Float,
    ): Boolean {
        if (first.size != second.size) return false
        val firstById = HashMap<String, NodeSummary>(first.size * 2)
        for (node in first) firstById[node.nodeId] = node
        for (node in second) {
            val before = firstById[node.nodeId] ?: return false
            if (maxBoundsDelta(before, node) > thresholdPx) return false
        }
        return true
    }

    fun maxBoundsDelta(a: NodeSummary, b: NodeSummary): Float = max(
        max(abs(a.bounds.left - b.bounds.left), abs(a.bounds.top - b.bounds.top)),
        max(abs(a.bounds.right - b.bounds.right), abs(a.bounds.bottom - b.bounds.bottom)),
    )
}
