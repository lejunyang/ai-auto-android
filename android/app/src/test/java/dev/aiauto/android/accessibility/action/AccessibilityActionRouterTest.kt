package dev.aiauto.android.accessibility.action

import dev.aiauto.android.accessibility.model.AccessibilityCommand
import dev.aiauto.android.accessibility.model.AccessibilityErrorCode
import dev.aiauto.android.accessibility.model.AccessibilityResult
import dev.aiauto.android.accessibility.model.ActionExecution
import dev.aiauto.android.accessibility.model.ActionRoute
import dev.aiauto.android.accessibility.model.GlobalAction
import dev.aiauto.android.accessibility.model.Gesture
import dev.aiauto.android.accessibility.model.NodeAction
import dev.aiauto.android.accessibility.model.NodePath
import dev.aiauto.android.accessibility.model.NodeTarget
import dev.aiauto.android.accessibility.model.NormalizedPoint
import dev.aiauto.android.accessibility.model.ScreenBounds
import dev.aiauto.android.accessibility.model.ScreenPoint
import dev.aiauto.android.accessibility.model.ScrollDirection
import dev.aiauto.android.accessibility.model.SelectorCandidate
import dev.aiauto.android.accessibility.model.SelectorStrategy
import dev.aiauto.android.accessibility.model.UiBounds
import dev.aiauto.android.accessibility.model.UiNodeSnapshot
import dev.aiauto.android.accessibility.model.UiNodeState
import dev.aiauto.android.accessibility.selector.SelectorMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityActionRouterTest {
    @Test
    fun `routes click to a fresh node action before gestures`() {
        val backend = FakeActionBackend()
        backend.nodeActionResult = true
        val router = router(backend)

        val first = router.execute(AccessibilityCommand.Click(selectorTarget()))
        val second = router.execute(AccessibilityCommand.Click(selectorTarget()))

        assertEquals(ActionRoute.NODE_ACTION, first.execution().route)
        assertEquals(ActionRoute.NODE_ACTION, second.execution().route)
        assertEquals(2, backend.openSessionCount)
        assertEquals(listOf(NodeAction.CLICK, NodeAction.CLICK), backend.performedNodeActions)
        assertTrue(backend.dispatchedGestures.isEmpty())
    }

    @Test
    fun `falls back from failed node click to the matched node center`() {
        val backend = FakeActionBackend()
        backend.nodeActionResult = false
        backend.gestureResults.add(true)

        val result = router(backend).execute(
            AccessibilityCommand.Click(selectorTarget()),
        )

        assertEquals(ActionRoute.NODE_GESTURE, result.execution().route)
        assertEquals(
            Gesture.Tap(point = ScreenPoint(150, 225), durationMs = 100),
            backend.dispatchedGestures.single(),
        )
    }

    @Test
    fun `uses normalized fallback only after a matched node gesture is rejected`() {
        val backend = FakeActionBackend()
        backend.nodeActionResult = false
        backend.gestureResults.add(false)
        backend.gestureResults.add(true)
        val target = selectorTarget().copy(
            normalizedScreenPoint = NormalizedPoint(x = 0.75, y = 0.25),
        )

        val result = router(backend).execute(AccessibilityCommand.Click(target))

        assertEquals(ActionRoute.COORDINATE_GESTURE, result.execution().route)
        assertEquals(2, backend.dispatchedGestures.size)
        assertEquals(
            ScreenPoint(x = 750, y = 500),
            (backend.dispatchedGestures.last() as Gesture.Tap).point,
        )
    }

    @Test
    fun `does not use coordinate fallback for an ambiguous selector`() {
        val duplicate = backendRoot().children.single()
        val backend = FakeActionBackend(
            root = backendRoot().copy(children = listOf(duplicate, duplicate)),
        )
        val target = selectorTarget().copy(
            normalizedScreenPoint = NormalizedPoint(x = 0.5, y = 0.5),
        )

        val result = router(backend).execute(AccessibilityCommand.Click(target))

        assertTrue(result is AccessibilityResult.Failure)
        assertEquals(
            AccessibilityErrorCode.SELECTOR_AMBIGUOUS,
            (result as AccessibilityResult.Failure).error.code,
        )
        assertTrue(backend.dispatchedGestures.isEmpty())
    }

    @Test
    fun `routes set text only through the node action`() {
        val backend = FakeActionBackend()
        backend.nodeActionResult = true

        val result = router(backend).execute(
            AccessibilityCommand.SetText(selectorTarget(), "hello"),
        )

        assertEquals(ActionRoute.NODE_ACTION, result.execution().route)
        assertEquals("hello", backend.lastText)
        assertEquals(listOf(NodeAction.SET_TEXT), backend.performedNodeActions)
    }

    @Test
    fun `uses forward node action before a fallback gesture for scroll down`() {
        val backend = FakeActionBackend()
        backend.nodeActionResult = true

        val result = router(backend).execute(
            AccessibilityCommand.Scroll(
                direction = ScrollDirection.DOWN,
                target = selectorTarget(),
            ),
        )

        assertEquals(ActionRoute.NODE_ACTION, result.execution().route)
        assertEquals(listOf(NodeAction.SCROLL_FORWARD), backend.performedNodeActions)
        assertTrue(backend.dispatchedGestures.isEmpty())
    }

    @Test
    fun `routes tap swipe and global navigation through their dedicated backends`() {
        val backend = FakeActionBackend()
        backend.gestureResults.add(true)
        backend.gestureResults.add(true)
        backend.globalActionResult = true
        val router = router(backend)

        val tap = router.execute(AccessibilityCommand.Tap(ScreenPoint(20, 30)))
        val swipe = router.execute(
            AccessibilityCommand.Swipe(
                start = ScreenPoint(20, 30),
                end = ScreenPoint(80, 90),
                durationMs = 500,
            ),
        )
        val home = router.execute(AccessibilityCommand.Navigate(GlobalAction.HOME))

        assertEquals(ActionRoute.SCREEN_GESTURE, tap.execution().route)
        assertEquals(ActionRoute.SCREEN_GESTURE, swipe.execution().route)
        assertEquals(ActionRoute.GLOBAL_ACTION, home.execution().route)
        assertEquals(listOf(GlobalAction.HOME), backend.performedGlobalActions)
    }

    @Test
    fun `rejects out of bounds gestures before dispatch`() {
        val backend = FakeActionBackend()

        val result = router(backend).execute(
            AccessibilityCommand.Tap(ScreenPoint(x = 1000, y = 20)),
        )

        assertTrue(result is AccessibilityResult.Failure)
        assertEquals(
            AccessibilityErrorCode.INVALID_COORDINATE,
            (result as AccessibilityResult.Failure).error.code,
        )
        assertTrue(backend.dispatchedGestures.isEmpty())
    }

    private fun router(backend: FakeActionBackend) = AccessibilityActionRouter(
        backend = backend,
        selectorMatcher = SelectorMatcher(minimumScore = 0.70, uniquenessMargin = 0.15),
    )

    private fun selectorTarget() = NodeTarget(
        packageName = "com.example",
        selectorCandidates = listOf(
            SelectorCandidate(
                strategy = SelectorStrategy.RESOURCE_ID,
                value = "com.example:id/continue",
                weight = 1.0,
                required = true,
            ),
        ),
    )

    private fun backendRoot() = UiNodeSnapshot(
        packageName = "com.example",
        className = "android.widget.FrameLayout",
        resourceId = null,
        text = null,
        contentDescription = null,
        bounds = UiBounds(left = 0, top = 0, right = 1000, bottom = 2000),
        actions = emptySet(),
        state = UiNodeState(enabled = true, visibleToUser = true),
        children = listOf(
            UiNodeSnapshot(
                packageName = "com.example",
                className = "android.widget.Button",
                resourceId = "com.example:id/continue",
                text = "Continue",
                contentDescription = null,
                bounds = UiBounds(left = 100, top = 200, right = 200, bottom = 250),
                actions = setOf(
                    NodeAction.CLICK,
                    NodeAction.SET_TEXT,
                    NodeAction.SCROLL_FORWARD,
                ),
                state = UiNodeState(
                    enabled = true,
                    clickable = true,
                    editable = true,
                    visibleToUser = true,
                ),
                children = emptyList(),
            ),
        ),
    )

    private inner class FakeActionBackend(
        private val root: UiNodeSnapshot = backendRoot(),
    ) : AccessibilityActionBackend {
        var openSessionCount = 0
        var nodeActionResult = false
        var globalActionResult = false
        var lastText: String? = null
        val gestureResults = ArrayDeque<Boolean>()
        val performedNodeActions = mutableListOf<NodeAction>()
        val performedGlobalActions = mutableListOf<GlobalAction>()
        val dispatchedGestures = mutableListOf<Gesture>()

        override fun validateTarget(expectedPackage: String?): AccessibilityResult<Unit> =
            if (expectedPackage == null || expectedPackage == root.packageName) {
                AccessibilityResult.Success(Unit)
            } else {
                AccessibilityResult.Failure(
                    code = AccessibilityErrorCode.PACKAGE_NOT_ALLOWED,
                    message = "Package is not allowed",
                )
            }

        override fun openNodeSession(
            expectedPackage: String?,
        ): AccessibilityResult<AccessibilityNodeSession> {
            openSessionCount += 1
            return AccessibilityResult.Success(
                object : AccessibilityNodeSession {
                    override val rootSnapshot: UiNodeSnapshot = root

                    override fun perform(
                        path: NodePath,
                        action: NodeAction,
                        text: String?,
                    ): Boolean {
                        performedNodeActions += action
                        lastText = text
                        return nodeActionResult
                    }

                    override fun close() = Unit
                },
            )
        }

        override fun dispatch(gesture: Gesture): Boolean {
            dispatchedGestures += gesture
            return gestureResults.removeFirstOrNull() ?: false
        }

        override fun performGlobal(action: GlobalAction): Boolean {
            performedGlobalActions += action
            return globalActionResult
        }

        override fun screenBounds(): ScreenBounds =
            ScreenBounds(left = 0, top = 0, right = 1000, bottom = 2000)
    }

    private fun AccessibilityResult<ActionExecution>.execution(): ActionExecution {
        assertTrue(this is AccessibilityResult.Success)
        return (this as AccessibilityResult.Success).value
    }
}
