package kotlinx.coroutines.flow

import kotlinx.coroutines.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlin.coroutines.*
import kotlin.test.*

/**
 * It is mostly covered by [ShareInTest], this just add state-specific checks.
 */
class StateInTest : TestBase() {
    @Test
    fun testOperatorFusion() = runTest {
        val state = flowOf("OK").stateIn(this)
        assertTrue(state !is MutableStateFlow<*>) // cannot be cast to mutable state flow
        assertSame(state, (state as Flow<*>).cancellable())
        assertSame(state, (state as Flow<*>).distinctUntilChanged())
        assertSame(state, (state as Flow<*>).flowOn(Dispatchers.Default))
        assertSame(state, (state as Flow<*>).conflate())
        assertSame(state, state.buffer(Channel.CONFLATED))
        assertSame(state, state.buffer(Channel.RENDEZVOUS))
        assertSame(state, state.buffer(onBufferOverflow = BufferOverflow.DROP_OLDEST))
        assertSame(state, state.buffer(0, onBufferOverflow = BufferOverflow.DROP_OLDEST))
        assertSame(state, state.buffer(1, onBufferOverflow = BufferOverflow.DROP_OLDEST))
        coroutineContext.cancelChildren()
    }

    @Test
    fun testUpstreamCompletedNoInitialValue() =
        testUpstreamCompletedOrFailedReset(failed = false, withInitialValue = false)

    @Test
    fun testUpstreamFailedNoInitialValue() =
        testUpstreamCompletedOrFailedReset(failed = true, withInitialValue = false)

    @Test
    fun testUpstreamCompletedWithInitialValue() =
        testUpstreamCompletedOrFailedReset(failed = false, withInitialValue = true)

    @Test
    fun testUpstreamFailedWithInitialValue() =
        testUpstreamCompletedOrFailedReset(failed = true, withInitialValue = true)

    private fun testUpstreamCompletedOrFailedReset(failed: Boolean, withInitialValue: Boolean) = runTest {
        val emitted = Job()
        val terminate = Job()
        val sharingJob = CompletableDeferred<Unit>()
        val upstream = flow {
            emit("OK")
            emitted.complete()
            terminate.join()
            if (failed) throw TestException()
        }
        val scope = this + sharingJob
        val shared: StateFlow<String?>
        if (withInitialValue) {
            shared = upstream.stateIn(scope, SharingStarted.Eagerly, null)
            assertEquals(null, shared.value)
        } else {
            shared = upstream.stateIn(scope)
            assertEquals("OK", shared.value) // waited until upstream emitted
        }
        emitted.join() // should start sharing, emit & cache
        assertEquals("OK", shared.value)
        terminate.complete()
        sharingJob.complete(Unit)
        sharingJob.join() // should complete sharing
        assertEquals("OK", shared.value) // value is still there
        if (failed) {
            assertIs<TestException>(sharingJob.getCompletionExceptionOrNull())
        } else {
            assertNull(sharingJob.getCompletionExceptionOrNull())
        }
    }

    @Test
    fun testUpstreamFailedImmediatelyWithInitialValue() = runTest {
        val ceh = CoroutineExceptionHandler { _, _ -> expect(2) }
        val flow = flow<Int> {
            expect(1)
            throw TestException()
        }
        assertFailsWith<TestException> { flow.stateIn(CoroutineScope(currentCoroutineContext() + Job() + ceh)) }
        finish(3)
    }

    @Test
    fun testSubscriptionByFirstSuspensionInStateFlow() = runTest {
        testSubscriptionByFirstSuspensionInCollect(flowOf(1).stateIn(this@runTest)) { }
    }

    @Test
    fun testRethrowsCEOnCancelledScope() = runTest {
        val cancelledScope = CoroutineScope(EmptyCoroutineContext).apply { cancel("CancelMessageToken") }
        val flow = flowOf(1, 2, 3)
        assertFailsWith<CancellationException>("CancelMessageToken") {
            flow.stateIn(cancelledScope)
        }
    }

    @Test
    fun testThrowsNoSuchElementExceptionOnEmptyFlow() = runTest {
        val flow = emptyFlow<Any>()
        assertFailsWith<NoSuchElementException> {
            flow.stateIn(this)
        }
        // Ensure that the collecting scope is not cancelled by the NoSuchElementException
        assertEquals(true, coroutineContext[Job]?.isActive)
    }
}
