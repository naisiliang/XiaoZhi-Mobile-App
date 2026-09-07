package com.lchuang.xiaozhimobile.providers

import com.lchuang.xiaozhimobile.ApiMode
import com.lchuang.xiaozhimobile.providers.health.CapabilityFailureKind
import com.lchuang.xiaozhimobile.providers.health.CapabilityHealthState
import com.lchuang.xiaozhimobile.providers.health.CapabilityHealthMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCapabilityProbeTest {
    @Test
    fun detectsCapabilitiesIndependentlyAndSeparatesLocalSkills() {
        val transport = RecordingTransport { request ->
            if (request.operation == ProviderProbeOperation.NATIVE_SKILLS) {
                ProviderProbeResponse.Supported(nativeSkills = listOf("web-search"))
            } else {
                ProviderProbeResponse.Supported()
            }
        }
        val probe = ProviderCapabilityProbe(transport, clock = { 1234L })

        val profile = probe.detect(
            ProviderConnectionConfig("https://provider.example", "model-a", ApiMode.AUTO),
            localSkills = listOf("local-search"),
        )

        assertEquals(ProviderCapability.entries.toSet(), transport.operations.toSet())
        assertEquals(ProviderCapability.entries.size, transport.operations.size)
        assertTrue(ProviderCapability.entries.all(profile::supports))
        assertEquals(listOf("local-search"), profile.localSkills)
        assertEquals(listOf("web-search"), profile.nativeSkills)
        assertEquals(1234L, profile.checkedAtMs)
    }

    @Test
    fun doesNotInferSupportAndMapsUnsupportedOrFailedProbes() {
        val transport = RecordingTransport { request ->
            when (request.operation) {
                ProviderProbeOperation.TEXT -> ProviderProbeResponse.Unsupported(404)
                ProviderProbeOperation.VISION -> ProviderProbeResponse.Failed(ProbeFailureKind.TIMEOUT)
                else -> ProviderProbeResponse.Unsupported(501)
            }
        }
        val profile = ProviderCapabilityProbe(transport).detect(
            ProviderConnectionConfig("https://provider.example", "OpenAI-compatible", ApiMode.AUTO),
        )

        assertEquals(CapabilitySupport.UNSUPPORTED, profile.status(ProviderCapability.TEXT))
        assertEquals(CapabilitySupport.UNKNOWN, profile.status(ProviderCapability.VISION))
        assertFalse(profile.supports(ProviderCapability.IMAGE_GENERATION))
    }

    @Test
    fun changingBaseModelOrModeInvalidatesCachedProfile() {
        val transport = RecordingTransport { ProviderProbeResponse.Unsupported(404) }
        val probe = ProviderCapabilityProbe(transport, clock = { 1L })
        val base = ProviderConnectionConfig("https://provider.example", "model-a", ApiMode.AUTO)

        probe.detect(base)
        probe.detect(base)
        assertEquals(ProviderCapability.entries.size, transport.operations.size)

        probe.detect(base.copy(model = "model-b"))
        probe.detect(base.copy(apiMode = ApiMode.RESPONSES))
        probe.detect(base.copy(baseUrl = "https://other.example"))
        assertEquals(ProviderCapability.entries.size * 4, transport.operations.size)
    }

    @Test
    fun credentialsAreNotRenderedInConfigurationOrProbeDiagnostics() {
        val config = ProviderConnectionConfig(
            "https://provider.example",
            "model-a",
            ApiMode.AUTO,
            apiKey = "super-secret-token",
        )
        val request = ProviderCapabilityProbe.buildRequestForTest(
            ProviderProbeOperation.TEXT,
            config,
        )

        assertFalse(config.toString().contains("super-secret-token"))
        assertFalse(request.toString().contains("super-secret-token"))
        assertFalse(request.bodyJson.contains("super-secret-token"))
    }

    @Test
    fun apiSpecificProbesUseTheirOwnWireFormatRegardlessOfSelectedMode() {
        val chatConfig = ProviderConnectionConfig("https://provider.example", "model-a", ApiMode.CHAT_COMPLETIONS)
        val responsesRequest = ProviderCapabilityProbe.buildRequestForTest(
            ProviderProbeOperation.RESPONSES_API,
            chatConfig,
        )
        assertEquals(ApiMode.RESPONSES, responsesRequest.apiMode)
        assertTrue(responsesRequest.bodyJson.contains("\"input\""))
        assertFalse(responsesRequest.bodyJson.contains("\"messages\""))

        val responsesConfig = chatConfig.copy(apiMode = ApiMode.RESPONSES)
        val chatRequest = ProviderCapabilityProbe.buildRequestForTest(
            ProviderProbeOperation.CHAT_COMPLETIONS,
            responsesConfig,
        )
        assertEquals(ApiMode.CHAT_COMPLETIONS, chatRequest.apiMode)
        assertTrue(chatRequest.bodyJson.contains("\"messages\""))
        assertFalse(chatRequest.bodyJson.contains("\"input\""))
    }

    @Test
    fun defaultTransportRejectsNonHttpRequestsWithoutThrowing() {
        val result = HttpProviderCapabilityTransport().probe(
            ProviderProbeRequest(
                operation = ProviderProbeOperation.TEXT,
                method = "POST",
                endpoint = "file:///private/provider",
                model = "model-a",
                apiMode = ApiMode.CHAT_COMPLETIONS,
                bodyJson = "{}",
                apiKey = "",
            ),
        )

        assertTrue(result is ProviderProbeResponse.Failed)
    }

    private class RecordingTransport(
        private val response: (ProviderProbeRequest) -> ProviderProbeResponse,
    ) : ProviderCapabilityTransport {
        val operations = mutableListOf<ProviderCapability>()

        override fun probe(request: ProviderProbeRequest): ProviderProbeResponse {
            operations += request.operation.capability
            return response(request)
        }
    }
}

class CapabilityHealthMonitorTest {
    @Test
    fun repeatedFailuresProgressToDegradedUnhealthyAndSuspended() {
        val monitor = CapabilityHealthMonitor()
        val capability = ProviderCapability.TEXT

        assertEquals(CapabilityHealthState.HEALTHY, monitor.stateOf(capability))
        assertEquals(CapabilityHealthState.DEGRADED, monitor.recordFailure(capability).state)
        assertEquals(CapabilityHealthState.DEGRADED, monitor.recordFailure(capability).state)
        assertEquals(CapabilityHealthState.UNHEALTHY, monitor.recordFailure(capability).state)
        monitor.recordFailure(capability)
        assertEquals(CapabilityHealthState.SUSPENDED, monitor.recordFailure(capability).state)
        assertEquals(CapabilityHealthState.SUSPENDED, monitor.recordSuccess(capability).state)
    }

    @Test
    fun securityViolationSuspendsImmediatelyAndExplicitRecheckRestoresIt() {
        val monitor = CapabilityHealthMonitor()
        val capability = ProviderCapability.MCP

        val suspended = monitor.recordFailure(
            capability,
            reason = "remote permission escalation",
            kind = CapabilityFailureKind.SECURITY_VIOLATION,
        )

        assertEquals(CapabilityHealthState.SUSPENDED, suspended.state)
        assertEquals("remote permission escalation", suspended.lastFailureReason)
        assertEquals(CapabilityHealthState.SUSPENDED, monitor.recordSuccess(capability).state)
        assertEquals(CapabilityHealthState.HEALTHY, monitor.recheck(capability).state)
    }

    @Test
    fun successfulProbeResetsTransientFailureCount() {
        val monitor = CapabilityHealthMonitor()
        val capability = ProviderCapability.CHAT_COMPLETIONS

        monitor.recordFailure(capability)
        assertEquals(CapabilityHealthState.HEALTHY, monitor.recordSuccess(capability).state)
        assertEquals(0, monitor.status(capability).consecutiveFailures)
    }
}
