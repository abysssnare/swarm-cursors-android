package com.swarmcursors.core.snapshot

import com.swarmcursors.core.model.ChangedNode
import com.swarmcursors.core.model.NodeSummary
import com.swarmcursors.core.model.SnapshotDiff
import kotlin.math.abs

/**
 * Computes structural diffs between two flat node lists keyed by [NodeSummary.nodeId]
 * (spec §5.1).
 */
object SnapshotDiffEngine {

    /** Position deltas (px) below this value are ignored for "changed" detection. */
    const val BOUNDS_EPSILON_PX = 1.0f

    fun compute(previous: List<NodeSummary>, current: List<NodeSummary>): SnapshotDiff {
        val previousById = HashMap<String, NodeSummary>(previous.size * 2)
        for (node in previous) previousById[node.nodeId] = node

        val currentIds = HashSet<String>(current.size * 2)
        val added = ArrayList<NodeSummary>()
        val changed = ArrayList<ChangedNode>()

        for (node in current) {
            currentIds.add(node.nodeId)
            val before = previousById[node.nodeId]
            when {
                before == null -> added.add(node)
                hasMeaningfulChange(before, node) -> changed.add(
                    ChangedNode(nodeId = node.nodeId, previous = before, current = node)
                )
            }
        }

        val removed = ArrayList<String>()
        for (id in previousById.keys) {
            if (id !in currentIds) removed.add(id)
        }

        return SnapshotDiff(
            added = added,
            removed = removed,
            changed = changed,
        )
    }

    /**
     * Two versions of the same node id count as "changed" when bounds moved
     * beyond the epsilon, or any user-relevant flag/text changed.
     */
    fun hasMeaningfulChange(before: NodeSummary, after: NodeSummary): Boolean {
        if (abs(before.bounds.left - after.bounds.left) > BOUNDS_EPSILON_PX ||
            abs(before.bounds.top - after.bounds.top) > BOUNDS_EPSILON_PX ||
            abs(before.bounds.right - after.bounds.right) > BOUNDS_EPSILON_PX ||
            abs(before.bounds.bottom - after.bounds.bottom) > BOUNDS_EPSILON_PX
        ) {
            return true
        }
        if (before.text != after.text) return true
        if (before.contentDescription != after.contentDescription) return true
        if (before.isChecked != after.isChecked) return true
        if (before.isEnabled != after.isEnabled) return true
        if (before.isVisibleToUser != after.isVisibleToUser) return true
        if (before.isScrollable != after.isScrollable) return true
        return false
    }
}
