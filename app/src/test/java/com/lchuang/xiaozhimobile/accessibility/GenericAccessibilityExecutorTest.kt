package com.lchuang.xiaozhimobile.accessibility

import com.lchuang.xiaozhimobile.safety.CentralSafetyPolicyEngine
import com.lchuang.xiaozhimobile.safety.PermissionBroker
import com.lchuang.xiaozhimobile.safety.ToolBlockReason
import com.lchuang.xiaozhimobile.safety.ToolDecision
import com.lchuang.xiaozhimobile.safety.ToolInvocation
import com.lchuang.xiaozhimobile.safety.ToolPolicyResult
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenBounds
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericAccessibilityExecutorTest {
    @Test
    fun `stale generation blocks before re-finding or executing`() {
        val fixture = Fixture()
        val target = fixture.target()
        val finder = RecordingNodeFinder(listOf(fixture.node(target)))
        val driver = RecordingActionDriver()

        fixture.store.publish(
            packageName = fixture.context.packageName,
            windowFingerprint = fixture.context.windowFingerprint,
            root = ScreenNode(id = "new-root"),
        )

        val result = fixture.executor(finder, driver).execute(
            UiActionProposal(UiActionType.CLICK, fixture.context, target),
        )

        assertFalse(result.success)
        assertEquals("SCREEN_CONTEXT_STALE", result.debugCode)
        assertEquals(0, finder.calls)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `missing node blocks without falling back to saved bounds`() {
        val fixture = Fixture(withVisibleBounds = true)
        val finder = RecordingNodeFinder(emptyList())
        val driver = RecordingActionDriver()

        val result = fixture.executor(finder, driver).execute(
            UiActionProposal(UiActionType.CLICK, fixture.context, fixture.target()),
        )

        assertFalse(result.success)
        assertEquals("ACCESSIBILITY_NODE_MISSING", result.debugCode)
        assertEquals(1, finder.calls)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `ambiguous current nodes block instead of choosing the first`() {
        val fixture = Fixture()
        val target = fixture.target()
        val finder = RecordingNodeFinder(listOf(fixture.node(target), fixture.node(target)))
        val driver = RecordingActionDriver()

        val result = fixture.executor(finder, driver).execute(
            UiActionProposal(UiActionType.SELECT, fixture.context, target),
        )

        assertFalse(result.success)
        assertEquals("SCREEN_AMBIGUOUS", result.debugCode)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `unique current semantic node allows click select back and next`() {
        val fixture = Fixture()
        val target = fixture.target()
        val finder = RecordingNodeFinder(listOf(fixture.node(target)))
        val driver = RecordingActionDriver()
        val executor = fixture.executor(finder, driver)

        val proposals = listOf(
            UiActionProposal(UiActionType.CLICK, fixture.context, target),
            UiActionProposal(UiActionType.SELECT, fixture.context, target),
            UiActionProposal(UiActionType.BACK, fixture.context),
            UiActionProposal(UiActionType.NEXT, fixture.context, target),
        )
        val results = proposals.map(executor::execute)

        assertTrue(results.all { it.success })
        assertEquals(listOf("CLICK", "SELECT", "BACK", "NEXT"), driver.actions)
        assertEquals(3, finder.calls)
    }

    @Test
    fun `context change after semantic re-find blocks the action`() {
        val fixture = Fixture()
        val target = fixture.target()
        val finder = RecordingNodeFinder(listOf(fixture.node(target))) {
            fixture.store.publish(
                packageName = fixture.context.packageName,
                windowFingerprint = fixture.context.windowFingerprint,
                root = ScreenNode(id = "changed-during-action"),
            )
        }
        val driver = RecordingActionDriver()

        val result = fixture.executor(finder, driver).execute(
            UiActionProposal(UiActionType.CLICK, fixture.context, target),
        )

        assertFalse(result.success)
        assertEquals("SCREEN_CONTEXT_STALE", result.debugCode)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `permission denial blocks before node re-find`() {
        val fixture = Fixture()
        val finder = RecordingNodeFinder(listOf(fixture.node(fixture.target())))
        val driver = RecordingActionDriver()

        val result = fixture.executor(finder, driver, permissionAllowed = false).execute(
            UiActionProposal(UiActionType.CLICK, fixture.context, fixture.target()),
        )

        assertFalse(result.success)
        assertEquals("PERMISSION_DENIED", result.debugCode)
        assertEquals(0, finder.calls)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `central safety block prevents node re-find`() {
        val fixture = Fixture()
        val finder = RecordingNodeFinder(listOf(fixture.node(fixture.target())))
        val driver = RecordingActionDriver()

        val result = fixture.executor(
            finder,
            driver,
            policy = {
                ToolPolicyResult(ToolDecision.BLOCK, ToolBlockReason.RESTRICTED_TOOL)
            },
        ).execute(UiActionProposal(UiActionType.CLICK, fixture.context, fixture.target()))

        assertFalse(result.success)
        assertEquals("RESTRICTED_TOOL", result.debugCode)
        assertEquals(0, finder.calls)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `sensitive screen blocks accessibility action`() {
        val fixture = Fixture(rootText = "确认支付")
        val target = fixture.target()
        val finder = RecordingNodeFinder(listOf(fixture.node(target)))
        val driver = RecordingActionDriver()

        val result = fixture.executor(finder, driver).execute(
            UiActionProposal(UiActionType.CLICK, fixture.context, target),
        )

        assertFalse(result.success)
        assertEquals("SAFETY_BLOCKED", result.debugCode)
        assertEquals(0, driver.calls)
    }

    @Test
    fun `semantic mismatch is not executed even when the id remains`() {
        val fixture = Fixture()
        val target = fixture.target()
        val changedNode = fixture.node(target).copy(semanticLabel = "页面已变化")
        val finder = RecordingNodeFinder(listOf(changedNode))
        val driver = RecordingActionDriver()

        val result = fixture.executor(finder, driver).execute(
            UiActionProposal(UiActionType.CLICK, fixture.context, target),
        )

        assertFalse(result.success)
        assertEquals("ACCESSIBILITY_NODE_MISSING", result.debugCode)
        assertEquals(0, driver.calls)
    }

    private class Fixture(
        rootText: String? = "普通列表",
        withVisibleBounds: Boolean = false,
    ) {
        val store = ScreenContextStore(ttlMs = 5_000L, clockMs = { 1_000L })
        val context = store.publish(
            packageName = "com.example.app",
            windowFingerprint = "com.example.app:1",
            root = ScreenNode(
                id = "root",
                text = rootText,
                visibleBounds = if (withVisibleBounds) ScreenBounds(10, 20, 110, 120) else null,
                children = listOf(
                    ScreenNode(
                        id = "old-coordinate-target",
                        text = "目标",
                        visibleBounds = if (withVisibleBounds) ScreenBounds(10, 20, 110, 120) else null,
                    ),
                ),
            ),
        )

        fun target(
            id: String = "node-1",
            label: String = "目标",
            generationId: GenerationId = context.generationId,
        ) = ContextCandidate(
            id = id,
            label = label,
            kind = ContextTargetKind.GENERIC,
            generationId = generationId,
            packageName = context.packageName,
            windowFingerprint = context.windowFingerprint,
        )

        fun node(target: ContextCandidate) = CurrentAccessibilityNode(
            id = target.id,
            semanticLabel = target.label,
            generationId = context.generationId,
            packageName = context.packageName,
            windowFingerprint = context.windowFingerprint,
        )

        fun executor(
            finder: AccessibilityNodeFinder,
            driver: AccessibilityActionDriver,
            permissionAllowed: Boolean = true,
            policy: (ToolInvocation) -> ToolPolicyResult = CentralSafetyPolicyEngine()::evaluate,
        ) = GenericAccessibilityExecutor(
            contextStore = store,
            nodeFinder = finder,
            actionDriver = driver,
            permissionBroker = PermissionBroker { permissionAllowed },
            policyEvaluator = policy,
        )
    }

    private class RecordingNodeFinder(
        private val nodes: List<CurrentAccessibilityNode>,
        private val onFind: (() -> Unit)? = null,
    ) : AccessibilityNodeFinder {
        var calls = 0

        override fun findCurrentNodes(
            context: ScreenContext,
            target: ContextCandidate,
        ): List<CurrentAccessibilityNode> {
            calls += 1
            onFind?.invoke()
            return nodes
        }
    }

    private class RecordingActionDriver : AccessibilityActionDriver {
        var calls = 0
        val actions = mutableListOf<String>()

        override fun perform(action: UiActionType, node: CurrentAccessibilityNode?): Boolean {
            calls += 1
            actions += action.name
            return true
        }
    }
}
