package io.sentry.apollo

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.coroutines.await
import com.apollographql.apollo.exception.ApolloException
import com.nhaarman.mockitokotlin2.anyOrNull
import com.nhaarman.mockitokotlin2.check
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.sentry.Breadcrumb
import io.sentry.IHub
import io.sentry.ITransaction
import io.sentry.SentryOptions
import io.sentry.SentryTraceHeader
import io.sentry.SentryTracer
import io.sentry.SpanStatus
import io.sentry.TraceContext
import io.sentry.TracesSamplingDecision
import io.sentry.TransactionContext
import io.sentry.protocol.SentryTransaction
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SentryApolloInterceptorTest {

    class Fixture {
        val server = MockWebServer()
        val hub = mock<IHub>()
        private var interceptor = SentryApolloInterceptor(hub)

        @SuppressWarnings("LongParameterList")
        fun getSut(
            httpStatusCode: Int = 200,
            responseBody: String = """{
  "data": {
    "launch": {
      "__typename": "Launch",
      "id": "83",
      "site": "CCAFS SLC 40",
      "mission": {
        "__typename": "Mission",
        "name": "Amos-17",
        "missionPatch": "https://images2.imgbox.com/a0/ab/XUoByiuR_o.png"
      }
    }
  }
}""",
            socketPolicy: SocketPolicy = SocketPolicy.KEEP_OPEN,
            beforeSpan: SentryApolloInterceptor.BeforeSpanCallback? = null
        ): ApolloClient {
            whenever(hub.options).thenReturn(
                SentryOptions().apply {
                    dsn = "http://key@localhost/proj"
                }
            )

            server.enqueue(
                MockResponse()
                    .setBody(responseBody)
                    .setSocketPolicy(socketPolicy)
                    .setResponseCode(httpStatusCode)
            )

            if (beforeSpan != null) {
                interceptor = SentryApolloInterceptor(hub, beforeSpan)
            }
            return ApolloClient.builder()
                .serverUrl(server.url("/"))
                .addApplicationInterceptor(interceptor)
                .build()
        }
    }

    private val fixture = Fixture()

    @Test
    fun `creates a span around the successful request`() {
        executeQuery()

        verify(fixture.hub).captureTransaction(
            check {
                assertTransactionDetails(it)
                assertEquals(SpanStatus.OK, it.spans.first().status)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `creates a span around the failed request`() {
        executeQuery(fixture.getSut(httpStatusCode = 403))

        verify(fixture.hub).captureTransaction(
            check {
                assertTransactionDetails(it)
                assertEquals(SpanStatus.PERMISSION_DENIED, it.spans.first().status)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `creates a span around the request failing with network error`() {
        executeQuery(fixture.getSut(socketPolicy = SocketPolicy.DISCONNECT_DURING_REQUEST_BODY))

        verify(fixture.hub).captureTransaction(
            check {
                assertTransactionDetails(it)
                assertEquals(SpanStatus.INTERNAL_ERROR, it.spans.first().status)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `when there is no active span, does not add sentry trace header to the request`() {
        executeQuery(isSpanActive = false)

        val recorderRequest = fixture.server.takeRequest()
        assertNull(recorderRequest.headers[SentryTraceHeader.SENTRY_TRACE_HEADER])
    }

    @Test
    fun `when there is an active span, adds sentry trace headers to the request`() {
        executeQuery()
        val recorderRequest = fixture.server.takeRequest()
        assertNotNull(recorderRequest.headers[SentryTraceHeader.SENTRY_TRACE_HEADER])
    }

    @Test
    fun `customizer modifies span`() {
        executeQuery(
            fixture.getSut { span, _, _ ->
                span.description = "overwritten description"
                span
            }
        )

        verify(fixture.hub).captureTransaction(
            check {
                assertEquals(1, it.spans.size)
                val httpClientSpan = it.spans.first()
                assertEquals("overwritten description", httpClientSpan.description)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `when customizer throws, exception is handled`() {
        executeQuery(
            fixture.getSut { _, _, _ -> throw RuntimeException() }
        )

        verify(fixture.hub).captureTransaction(
            check {
                assertEquals(1, it.spans.size)
            },
            anyOrNull<TraceContext>(),
            anyOrNull()
        )
    }

    @Test
    fun `adds breadcrumb when http calls succeeds`() {
        executeQuery()
        verify(fixture.hub).addBreadcrumb(
            check<Breadcrumb> {
                assertEquals("http", it.type)
                assertEquals(280L, it.data["response_body_size"])
                assertEquals(193L, it.data["request_body_size"])
            },
            anyOrNull()
        )
    }

    private fun assertTransactionDetails(it: SentryTransaction) {
        assertEquals(1, it.spans.size)
        val httpClientSpan = it.spans.first()
        assertEquals("LaunchDetails", httpClientSpan.op)
        assertEquals("query LaunchDetails", httpClientSpan.description)
        assertNotNull(httpClientSpan.data) {
            assertNotNull(it["operationId"])
            assertEquals("{id=83}", it["variables"])
        }
    }

    private fun executeQuery(sut: ApolloClient = fixture.getSut(), isSpanActive: Boolean = true) = runBlocking {
        var tx: ITransaction? = null
        if (isSpanActive) {
            tx = SentryTracer(TransactionContext("op", "desc", TracesSamplingDecision(true)), fixture.hub)
            whenever(fixture.hub.span).thenReturn(tx)
        }

        val coroutine = launch {
            try {
                sut.query(LaunchDetailsQuery.builder().id("83").build()).await()
            } catch (e: ApolloException) {
                return@launch
            }
        }
        coroutine.join()
        tx?.finish()
    }
}
