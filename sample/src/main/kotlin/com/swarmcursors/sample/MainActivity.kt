package com.swarmcursors.sample

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.swarmcursors.SwarmCursors
import com.swarmcursors.core.geometry.RectF
import com.swarmcursors.core.model.ActionTarget
import com.swarmcursors.core.model.ActionType
import com.swarmcursors.core.model.AgentAction
import com.swarmcursors.core.model.NodeSummary
import com.swarmcursors.core.model.ServiceState
import com.swarmcursors.core.model.SwarmConfig
import com.swarmcursors.core.model.MotionStyle
import com.swarmcursors.test.SwarmTestSupport
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private var mockInstalled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)

        findViewById<Button>(R.id.openSettingsBtn).setOnClickListener {
            SwarmCursors.openAccessibilitySettings(this)
        }
        findViewById<Button>(R.id.mockDemoBtn).setOnClickListener { runMockDemo() }
        findViewById<Button>(R.id.injectRealBtn).setOnClickListener { injectRealAction() }
        findViewById<Button>(R.id.snapshotBtn).setOnClickListener { captureSnapshot() }
        findViewById<Button>(R.id.windowsBtn).setOnClickListener { listWindows() }
        findViewById<Button>(R.id.metricsBtn).setOnClickListener { showMetrics() }

        // §1.3: observe state and enable the engine as soon as it is Ready.
        lifecycleScope.launch {
            SwarmCursors.observeServiceState(this@MainActivity).collect { state ->
                statusView.text = "Service: ${state::class.simpleName}"
                appendLine("state → ${state::class.simpleName}")
            }
        }

        // §8.2: audit listener example (text is always redacted).
        SwarmCursors.setAuditListener { entry ->
            appendLine("audit: ${entry.action.type} result=${entry.result} ${entry.durationMs}ms")
        }
    }

    /** Demo without accessibility permission using the mock service (§9.1). */
    private fun runMockDemo() {
        if (!mockInstalled) {
            SwarmTestSupport.installMockService(
                listOf(
                    NodeSummary(
                        nodeId = "submit",
                        text = "Submit",
                        bounds = RectF(120f, 1400f, 960f, 1500f),
                        isClickable = true,
                    ),
                    NodeSummary(
                        nodeId = "cancel",
                        text = "Cancel",
                        bounds = RectF(120f, 1600f, 960f, 1700f),
                        isClickable = true,
                    ),
                )
            )
            mockInstalled = true
            appendLine("mock service installed (2 nodes)")
        }

        lifecycleScope.launch {
            SwarmCursors.injectActions(
                listOf(
                    AgentAction(type = ActionType.CLICK, target = ActionTarget.node("submit")),
                    AgentAction(type = ActionType.CLICK, target = ActionTarget.node("cancel")),
                )
            ).collect { event -> appendLine("→ $event") }
            appendLine(
                "executed: ${SwarmTestSupport.getExecutedActions().map { it.type }}"
            )
        }
    }

    /** Real injection: taps the screen center once the service is Ready. */
    private fun injectRealAction() {
        lifecycleScope.launch {
            if (SwarmCursors.getServiceState() !is ServiceState.Ready && !mockInstalled) {
                appendLine("not ready — enable the service first (or run the mock demo)")
                return@launch
            }
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f
            val y = dm.heightPixels / 2f
            SwarmCursors.injectActions(
                listOf(
                    AgentAction(
                        type = ActionType.CLICK,
                        target = ActionTarget.coordinate(x, y),
                        durationHintMs = 700,
                    )
                )
            ).collect { event -> appendLine("→ $event") }
        }
    }

    private fun captureSnapshot() {
        lifecycleScope.launch {
            val snapshot = SwarmCursors.captureSnapshot()
            if (snapshot == null) {
                appendLine("snapshot unavailable — service not ready (try mock demo)")
            } else {
                appendLine(
                    "snapshot: ${snapshot.nodes.size} nodes, " +
                        "window=${snapshot.windowTitle}, stable=${snapshot.isStable}, " +
                        "diff(+${snapshot.diff?.added?.size ?: 0} " +
                        "-${snapshot.diff?.removed?.size ?: 0} " +
                        "~${snapshot.diff?.changed?.size ?: 0})"
                )
            }
        }
    }

    private fun listWindows() {
        val windows = SwarmCursors.listWindows()
        if (windows.isEmpty()) {
            appendLine("no windows (service not ready?)")
            return
        }
        windows.forEach { window ->
            appendLine(
                "window #${window.windowId} active=${window.isActive} " +
                    "title=${window.title} bounds=${window.bounds}"
            )
        }
    }

    private fun showMetrics() {
        val metrics = SwarmCursors.getMetrics()
        appendLine(
            "metrics: actions=${metrics.totalActions} ok=${metrics.succeeded} " +
                "fail=${metrics.failed} motionAvg=${metrics.avgMotionDurationMs}ms " +
                "execAvg=${metrics.avgExecutionDurationMs}ms fps=${metrics.currentFps}"
        )
    }

    private fun appendLine(line: String) {
        logView.append(line + "\n")
    }
}
