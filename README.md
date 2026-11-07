# Swarm Cursors (Android)

**Multi-cursor agent automation for Android.** Swarm Cursors lets an AI agent
visually and physically operate an Android device through the
AccessibilityService: animated cursors travel to targets (Fitts' law motion
with spring landing), then execute clicks, typing, scrolling and gestures —
with a queueable/cancellable ticket model, snapshot diffing, strict input
redaction and first-class observability.

![CI](https://github.com/abysssnare/swarm-cursors-android/actions/workflows/ci.yml/badge.svg)

## Modules

| Module | Type | Purpose |
|---|---|---|
| `:core` | Pure Kotlin/JVM | Models, motion planning (Fitts + Bézier + spring), snapshot diff/sanitizer, logging, metrics |
| `:swarm-cursors` | Android library | AccessibilityService, overlay rendering, execution engine (queue/cancel/retry), themes, test support |
| `:sample` | Demo app | Mock demo, real injection, snapshots, windows, metrics |

## Requirements

- minSdk **26**, compileSdk **34**, Kotlin **2.0**, JDK **17**
- The host app must obtain `SYSTEM_ALERT_WINDOW` (declared by the library) and
  the user must enable the accessibility service.

## Integration

```kotlin
dependencies {
    implementation("com.swarmcursors:swarm-cursors:1.0.0") // or include(":swarm-cursors")
}
```

The library's manifest already declares `SwarmAccessibilityService`, so it
appears in the system accessibility settings right away.

### 1. Guide the user to permissions (§1)

```kotlin
if (!SwarmCursors.isAccessibilityServiceEnabled(context)) {
    SwarmCursors.openAccessibilitySettings(context)
}

lifecycleScope.launch {
    SwarmCursors.observeServiceState(context).collect { state ->
        // NotEnabled → Connecting → Ready (or Error(reason))
    }
}
```

`injectActions` on a non-`Ready` service returns a flow that immediately emits
`Failed(SERVICE_NOT_READY)` — it never throws.

### 2. Inject actions (§3)

```kotlin
SwarmCursors.injectActions(
    listOf(
        AgentAction(type = ActionType.CLICK, target = ActionTarget.node("r/0/3")),
        AgentAction(type = ActionType.TYPE, target = ActionTarget.node("r/1/0"), text = "hello"),
        AgentAction(type = ActionType.SCROLL, target = ActionTarget.node("r/2"), direction = ScrollDirection.DOWN),
    )
).collect { event ->
    when (event) {
        is ActionEvent.Moving -> renderProgress(event.progress)
        is ActionEvent.Executed -> handleSuccess(event)
        is ActionEvent.Failed -> handleFailure(event.reason, event.attempts)
        is ActionEvent.Cancelled -> handleCancel(event.stage)
        is ActionEvent.TicketCompleted -> /* terminal */ Unit
        else -> Unit
    }
}
```

- Tickets run **in submission order**; `SwarmConfig(parallelMode = true, maxParallel = n)`
  runs up to `min(maxParallel, maxCursors)` tickets concurrently, each with
  its own cursor.
- `cancelTicket(id)` / `cancelAll()` — cancelling queued actions emits
  `Cancelled(QUEUED)`, cancelling mid-flight interrupts the motion
  (`Cancelled(MOVING)`, cursor springs back and fades), executed actions
  cannot be undone (ignored).
- Per-action timeout (`SwarmConfig.actionTimeoutMs = 5000`) surfaces
  `MOTION_TIMEOUT` / `EXECUTION_TIMEOUT`.

### 3. Snapshots & diffs (§5)

```kotlin
val snapshot = SwarmCursors.captureSnapshot()          // suspend
snapshot.nodes.forEach { println("${it.nodeId} ${it.className} ${it.text}") }
snapshot.diff?.added   // nodes added since the previous snapshot
snapshot.diff?.removed // node ids that disappeared
snapshot.diff?.changed // nodes whose bounds/text/checked changed
```

Capture applies a 200 ms settle delay and double-probes at 80 ms to detect
UI animation jitter (threshold 5 px, ≤ 3 retries — result flagged
`isStable = false` when it never settles). `SnapshotConfig` tunes all of it
and `maxNodes` (500) truncates by visibility then area.

### 4. Security & audit (§10)

- Password nodes (`isPassword` or password `inputType`) never expose text in
  snapshots: `isPassword → null`, password `inputType → "***"` (controlled by
  `SnapshotConfig.includeSensitiveText`, default `false`).
- `SwarmCursors.setAuditListener { entry -> … }` receives every performed
  action **with `action.text` always stripped**.

### 5. Observability (§8)

```kotlin
SwarmLog.setLevel(LogLevel.DEBUG)              // default WARN, tag "SwarmCursors"
SwarmLog.setLogger { level, tag, msg, err -> /* route to your logger */ }

val metrics = SwarmCursors.getMetrics()        // actions, durations, FPS…
SwarmCursors.resetMetrics()
SwarmCursors.setDebugOverlayEnabled(true)      // debug builds only:
                                               // cursor ids, target bounds,
                                               // FPS, current phase
```

### 6. Windows, themes, config (§6/§7)

```kotlin
SwarmCursors.listWindows()                // PiP filtered out
SwarmCursors.setTargetWindow(windowId)    // default: active window

SwarmCursors.updateConfig(
    SwarmConfig(
        motionStyle = MotionStyle.SPRING,   // hot
        cursorScale = 1.2f,                 // hot
        enableTrail = true,                 // hot
        theme = "my-theme",                 // hot (registered below)
        parallelMode = true,                // cold → overlay rebuilt
        appearance = CursorAppearance(arrowColor = 0xFFFFFFFF.toInt()),
    )
)

CursorThemes.register("my-theme") { MyCursorTheme() }  // implement CursorThemeRenderer
```

### 7. Testing without a device permission (§9)

```kotlin
SwarmTestSupport.installMockService(
    nodes = listOf(NodeSummary(nodeId = "submit", bounds = RectF(120f, 1400f, 960f, 1500f), isClickable = true)),
    actionResults = mapOf(ActionType.CLICK to false),  // optional scripted results
)
// … injectActions walks Moving → Executing under the mock …
SwarmTestSupport.simulateInterfaceChange(newNodes)     // navigation/stale-node tests
SwarmTestSupport.getExecutedActions()
SwarmTestSupport.reset()
```

The `:core` module ships `MotionPlannerTestHarness` for asserting
interpolation continuity, Fitts duration monotonicity and spring overshoot
(< 10%).

## Feature checklist (spec)

- [x] §1 permission guidance, state machine (`ServiceState`), settings observer
- [x] §2 weak-reference service instance, overlay bound to connect/unbind, pause on interrupt, keep-alive notification
- [x] §3 ticket queue, `cancelTicket`/`cancelAll`/`getPendingTickets`, `CancelStage` semantics, per-action timeouts
- [x] §4 `FailureReason` taxonomy, `RetryPolicy` (NODE_STALE/EXECUTION_REJECTED × 1 by default, re-resolve on retry), §4.3 freshness validation
- [x] §5 incremental snapshots with diff, stability detection, `SnapshotConfig` truncation
- [x] §6 multi-window targeting, PiP filtering, display-change remap mid-motion
- [x] §7 runtime config (hot fields + overlay rebuild for parallelism), theme registry, `CursorAppearance`
- [x] §8 leveled logging with custom sink, metrics (actions/durations/FPS), debug overlay (debug builds only)
- [x] §9 mock accessibility service + `MotionPlannerTestHarness`
- [x] §10 password redaction, audit log without input text

## CI

Verification runs entirely on GitHub Actions (no local builds):
`assembleDebug` + unit tests (`:core` JVM tests and Robolectric engine tests)
on every push and pull request. See
[`.github/workflows/ci.yml`](.github/workflows/ci.yml).

## License

MIT — see [LICENSE](LICENSE).
