package com.lchuang.xiaozhimobile.adapters

import com.lchuang.xiaozhimobile.ToolExecutionResult
import com.lchuang.xiaozhimobile.accessibility.AccessibilityActionDriver
import com.lchuang.xiaozhimobile.accessibility.AccessibilityNodeFinder
import com.lchuang.xiaozhimobile.accessibility.CurrentAccessibilityNode
import com.lchuang.xiaozhimobile.accessibility.GenericAccessibilityExecutor
import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.accessibility.UiActionExecutor
import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.safety.PermissionBroker
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.ContextTargetKind
import com.lchuang.xiaozhimobile.screen.ScreenContext
import com.lchuang.xiaozhimobile.screen.ScreenContextStore
import com.lchuang.xiaozhimobile.screen.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAdapterRegistryTest {
    @Test
    fun dedicatedAdapterIsPreferredForSemanticResolution() {
        val fixture = Fixture()
        val adapter = RecordingAdapter("com.autonavi.minimap")
        val genericCalls = mutableListOf<UiActionProposal>()
        val registry = AppAdapterRegistry(
            adapters = listOf(adapter),
            genericExecutor = UiActionExecutor { proposal ->
                genericCalls += proposal
                ToolExecutionResult(true, "已执行", "GENERIC_OK")
            },
        )

        val resolution = registry.resolveAction(fixture.proposal)
        val result = registry.execute(fixture.proposal)

        assertEquals("recording", resolution.adapter?.id)
        assertFalse(resolution.usedGenericFallback)
        assertEquals("适配目标", resolution.proposal.target?.label)
        assertEquals("GENERIC_OK", result.debugCode)
        assertEquals(2, adapter.resolveCalls)
        assertEquals(1, adapter.verifyCalls)
        assertEquals("适配目标", genericCalls.single().target?.label)
    }

    @Test
    fun unsupportedAppFallsBackToGenericExecutor() {
        val fixture = Fixture(packageName = "com.example.unsupported")
        val genericCalls = mutableListOf<UiActionProposal>()
        val registry = AppAdapterRegistry(
            adapters = listOf(RecordingAdapter("com.autonavi.minimap")),
            genericExecutor = UiActionExecutor { proposal ->
                genericCalls += proposal
                ToolExecutionResult(true, "通用执行", "GENERIC_OK")
            },
        )

        val resolution = registry.resolveAction(fixture.proposal)
        val result = registry.execute(fixture.proposal)

        assertTrue(resolution.usedGenericFallback)
        assertEquals(null, resolution.adapter)
        assertEquals(fixture.proposal, resolution.proposal)
        assertEquals("GENERIC_OK", result.debugCode)
        assertEquals(listOf(fixture.proposal), genericCalls)
    }

    @Test
    fun adapterCannotBypassGenericSafetyOrExecuteSensitiveScreen() {
        val fixture = Fixture(rootText = "确认支付")
        val adapter = RecordingAdapter(fixture.context.packageName)
        var driverCalls = 0
        val nodeFinder = AccessibilityNodeFinder { context, target ->
            listOf(
                CurrentAccessibilityNode(
                    id = target.id,
                    semanticLabel = target.label,
                    generationId = context.generationId,
                    packageName = context.packageName,
                    windowFingerprint = context.windowFingerprint,
                ),
            )
        }
        val driver = AccessibilityActionDriver { _, _ ->
            driverCalls += 1
            true
        }
        val generic = GenericAccessibilityExecutor(
            contextStore = fixture.store,
            nodeFinder = nodeFinder,
            actionDriver = driver,
            permissionBroker = PermissionBroker { true },
        )
        val registry = AppAdapterRegistry(
            adapters = listOf(adapter),
            genericExecutor = generic,
        )

        val result = registry.execute(fixture.proposal)

        assertFalse(result.success)
        assertEquals("SAFETY_BLOCKED", result.debugCode)
        assertEquals(0, driverCalls)
        assertEquals(0, adapter.verifyCalls)
    }

    @Test
    fun adapterVerificationFailureFailsClosedAfterGenericExecution() {
        val fixture = Fixture()
        val adapter = RecordingAdapter(fixture.context.packageName, verifyResult = false)
        val registry = AppAdapterRegistry(
            adapters = listOf(adapter),
            genericExecutor = UiActionExecutor {
                ToolExecutionResult(true, "已执行", "GENERIC_OK")
            },
        )

        val result = registry.execute(fixture.proposal)

        assertFalse(result.success)
        assertEquals("ADAPTER_RESULT_UNVERIFIED", result.debugCode)
    }

    @Test
    fun adapterCannotSwitchTheContextBeingExecuted() {
        val fixture = Fixture()
        val replacement = Fixture(packageName = "com.example.other").context
        val adapter = RecordingAdapter(
            packageName = fixture.context.packageName,
            replacementContext = replacement,
        )
        val genericCalls = mutableListOf<UiActionProposal>()
        val registry = AppAdapterRegistry(
            adapters = listOf(adapter),
            genericExecutor = UiActionExecutor { proposal ->
                genericCalls += proposal
                ToolExecutionResult(true, "通用执行", "GENERIC_OK")
            },
        )

        val resolution = registry.resolveAction(fixture.proposal)
        val result = registry.execute(fixture.proposal)

        assertTrue(resolution.usedGenericFallback)
        assertEquals(null, resolution.adapter)
        assertEquals(fixture.proposal, resolution.proposal)
        assertEquals("GENERIC_OK", result.debugCode)
        assertEquals(listOf(fixture.proposal), genericCalls)
    }

    @Test
    fun mapAdapterEnrichesSemanticObjectsWithoutChangingMapController() {
        val fixture = Fixture(
            packageName = "com.autonavi.minimap",
            root = ScreenNode(
                id = "map-root",
                text = "地图",
                children = listOf(
                    ScreenNode(
                        id = "search-button",
                        text = "搜索附近",
                        contentDescription = "搜索附近地点",
                        clickable = true,
                    ),
                ),
            ),
        )
        val adapter = MapAppAdapter()

        assertTrue(adapter.canHandle(fixture.context.packageName))
        assertEquals(AppPageKind.MAP_SEARCH, adapter.classifyPage(fixture.context))
        val objects = adapter.extractSemanticObjects(fixture.context)
        assertNotNull(objects.singleOrNull { it.id == "search-button" })
        assertEquals(fixture.context.generationId, objects.first().generationId)
    }

    private class Fixture(
        packageName: String = "com.autonavi.minimap",
        rootText: String = "普通列表",
        root: ScreenNode? = null,
    ) {
        val store = ScreenContextStore(ttlMs = 5_000L, clockMs = { 1_000L })
        val context = store.publish(
            packageName = packageName,
            windowFingerprint = "$packageName:1",
            root = root ?: ScreenNode(id = "root", text = rootText),
        )
        val target = ContextCandidate(
            id = "node-1",
            label = "目标",
            kind = ContextTargetKind.GENERIC,
            generationId = context.generationId,
            packageName = context.packageName,
            windowFingerprint = context.windowFingerprint,
        )
        val proposal = UiActionProposal(UiActionType.CLICK, context, target)
    }

    private class RecordingAdapter(
        private val packageName: String,
        private val verifyResult: Boolean = true,
        private val replacementContext: ScreenContext? = null,
    ) : AppAdapter {
        override val id: String = "recording"
        var resolveCalls = 0
        var verifyCalls = 0

        override fun canHandle(packageName: String): Boolean = this.packageName == packageName

        override fun classifyPage(context: ScreenContext): AppPageKind = AppPageKind.OTHER

        override fun extractSemanticObjects(context: ScreenContext): List<ContextCandidate> = emptyList()

        override fun resolveAction(proposal: UiActionProposal): UiActionProposal {
            resolveCalls += 1
            val target = proposal.target ?: return proposal
            val adaptedContext = replacementContext ?: proposal.context
            val adaptedTarget = target.copy(
                label = "适配目标",
                generationId = adaptedContext.generationId,
                packageName = adaptedContext.packageName,
                windowFingerprint = adaptedContext.windowFingerprint,
            )
            return proposal.copy(context = adaptedContext, target = adaptedTarget)
        }

        override fun verifyResult(proposal: UiActionProposal, result: ToolExecutionResult): Boolean {
            verifyCalls += 1
            return verifyResult
        }
    }
}
