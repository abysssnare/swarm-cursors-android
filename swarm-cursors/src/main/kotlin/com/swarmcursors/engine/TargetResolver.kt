package com.swarmcursors.engine

import com.swarmcursors.core.geometry.PointF
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.model.ActionTarget
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.FailureReason
import com.swarmcursors.host.AccessibilityHost
import com.swarmcursors.host.NodeHandle

/**
 * Result of resolving an [ActionTarget] against the current UI.
 */
internal sealed class ResolvedTarget {

    /** A usable node (and the point to travel to). */
    data class NodeTarget(
        val handle: NodeHandle,
        val point: PointF,
        val widthPx: Float,
        val bounds: RectF,
    ) : ResolvedTarget()

    /** Only coordinates are available (node missing or coordinate-only action). */
    data class PointTarget(
        val point: PointF,
        val widthPx: Float,
        val bounds: RectF,
    ) : ResolvedTarget()

    data class NotFound(val reason: FailureReason, val message: String?) : ResolvedTarget()
}

/**
 * Resolves action targets (spec §4.3): nodes are re-fetched by id and
 * validated for `isVisibleToUser && isEnabled`; stale nodes surface as
 * [FailureReason.NODE_STALE] so the engine can retry with a fresh resolve.
 */
internal class TargetResolver {

    fun resolve(host: AccessibilityHost, action: AgentAction, windowId: Int?): ResolvedTarget {
        val target = action.target
        if (target.isNode) {
            val handle = host.findNodeById(target.nodeId!!, windowId)
            if (handle == null) {
                return if (target.hasCoordinates) {
                    pointTarget(target)
                } else {
                    ResolvedTarget.NotFound(
                        FailureReason.TARGET_NOT_FOUND,
                        "Node ${target.nodeId} not found",
                    )
                }
            }
            val usable = runCatching { handle.refresh() }.getOrDefault(false) &&
                runCatching { handle.isVisibleToUser() }.getOrDefault(false) &&
                runCatching { handle.isEnabled() }.getOrDefault(false)
            if (!usable) {
                return ResolvedTarget.NotFound(
                    FailureReason.NODE_STALE,
                    "Node ${target.nodeId} is stale (invisible or disabled)",
                )
            }
            val bounds = runCatching { handle.boundsInScreen() }.getOrDefault(RectF.EMPTY)
            if (bounds.isEmpty) {
                return if (target.hasCoordinates) pointTarget(target) else ResolvedTarget.NotFound(
                    FailureReason.NODE_STALE,
                    "Node ${target.nodeId} has empty bounds",
                )
            }
            return ResolvedTarget.NodeTarget(
                handle = handle,
                point = bounds.center(),
                widthPx = bounds.width.coerceAtLeast(MIN_TARGET_WIDTH_PX),
                bounds = bounds,
            )
        }
        if (target.hasCoordinates) return pointTarget(target)
        return ResolvedTarget.NotFound(
            FailureReason.TARGET_NOT_FOUND,
            "Action has neither node id nor coordinates",
        )
    }

    private fun pointTarget(target: ActionTarget): ResolvedTarget.PointTarget {
        val point = target.coordinatePoint() ?: PointF(0f, 0f)
        return ResolvedTarget.PointTarget(
            point = point,
            widthPx = FALLBACK_TARGET_WIDTH_PX,
            bounds = RectF.fromCenter(point, FALLBACK_TARGET_WIDTH_PX, FALLBACK_TARGET_WIDTH_PX),
        )
    }

    companion object {
        const val MIN_TARGET_WIDTH_PX = 24f
        const val FALLBACK_TARGET_WIDTH_PX = 48f
    }
}
