@file:OptIn(ExperimentalContracts::class, ObsoleteWorkersApi::class)
@file:Suppress("LEAKED_IN_PLACE_LAMBDA", "WRONG_INVOCATION_KIND")

package kotlinx.coroutines

import kotlinx.cinterop.*
import kotlin.contracts.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

/**
 * Runs a new coroutine and **blocks** the current thread _interruptibly_ until its completion.
 *
 * It is designed to bridge regular blocking code to libraries that are written in suspending style, to be used in
 * `main` functions and in tests.
 *
 * Calling [runBlocking] from a suspend function is redundant.
 * For example, the following code is incorrect:
 * ```
 * suspend fun loadConfiguration() {
 *     // DO NOT DO THIS:
 *     val data = runBlocking { // <- redundant and blocks the thread, do not do that
 *         fetchConfigurationData() // suspending function
 *     }
 * ```
 *
 * Here, instead of releasing the thread on which `loadConfiguration` runs if `fetchConfigurationData` suspends, it will
 * block, potentially leading to thread starvation issues.
 *
 * The default [CoroutineDispatcher] for this builder is an internal implementation of event loop that processes continuations
 * in this blocked thread until the completion of this coroutine.
 * See [CoroutineDispatcher] for the other implementations that are provided by `kotlinx.coroutines`.
 *
 * When [CoroutineDispatcher] is explicitly specified in the [context], then the new coroutine runs in the context of
 * the specified dispatcher while the current thread is blocked. If the specified dispatcher is an event loop of another `runBlocking`,
 * then this invocation uses the outer event loop.
 *
 * If this blocked thread is interrupted (see [Thread.interrupt]), then the coroutine job is cancelled and
 * this `runBlocking` invocation throws [InterruptedException].
 *
 * See [newCoroutineContext][CoroutineScope.newCoroutineContext] for a description of debugging facilities that are available
 * for a newly created coroutine.
 *
 * @param context the context of the coroutine. The default value is an event loop on the current thread.
 * @param block the coroutine code.
 */
public actual fun <T> runBlocking(context: CoroutineContext, block: suspend CoroutineScope.() -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val contextInterceptor = context[ContinuationInterceptor]
    val eventLoop: EventLoop?
    val newContext: CoroutineContext
    if (contextInterceptor == null) {
        // create or use private event loop if no dispatcher is specified
        eventLoop = ThreadLocalEventLoop.eventLoop
        newContext = GlobalScope.newCoroutineContext(context + eventLoop)
    } else {
        // See if context's interceptor is an event loop that we shall use (to support TestContext)
        // or take an existing thread-local event loop if present to avoid blocking it (but don't create one)
        eventLoop = (contextInterceptor as? EventLoop)?.takeIf { it.shouldBeProcessedFromContext() }
            ?: ThreadLocalEventLoop.currentOrNull()
        newContext = GlobalScope.newCoroutineContext(context)
    }
    val coroutine = BlockingCoroutine<T>(newContext, eventLoop)
    var completed = false
    ThreadLocalKeepAlive.addCheck { !completed }
    try {
        coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
        return coroutine.joinBlocking()
    } finally {
        completed = true
    }
}

@ThreadLocal
private object ThreadLocalKeepAlive {
    /** If any of these checks passes, this means this [Worker] is still used. */
    private var checks = mutableListOf<() -> Boolean>()

    /** Whether the worker currently tries to keep itself alive. */
    private var keepAliveLoopActive = false

    /** Adds another stopgap that must be passed before the [Worker] can be terminated. */
    fun addCheck(terminationForbidden: () -> Boolean) {
        checks.add(terminationForbidden)
        if (!keepAliveLoopActive) keepAlive()
    }

    /**
     * Send a ping to the worker to prevent it from terminating while this coroutine is running,
     * ensuring that continuations don't get dropped and forgotten.
     */
    private fun keepAlive() {
        // only keep the checks that still forbid the termination
        checks = checks.filter { it() }.toMutableList()
        // if there are no checks left, we no longer keep the worker alive, it can be terminated
        keepAliveLoopActive = checks.isNotEmpty()
        if (keepAliveLoopActive) {
            Worker.current.executeAfter(afterMicroseconds = 100_000) {
                keepAlive()
            }
        }
    }
}

private class BlockingCoroutine<T>(
    parentContext: CoroutineContext,
    private val eventLoop: EventLoop?
) : AbstractCoroutine<T>(parentContext, true, true) {
    private val joinWorker = Worker.current

    override val isScopedCoroutine: Boolean get() = true

    override fun afterCompletion(state: Any?) {
        // wake up blocked thread
        if (joinWorker != Worker.current) {
            // Unpark waiting worker
            joinWorker.executeAfter(0L, {}) // send an empty task to unpark the waiting event loop
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun joinBlocking(): T {
        try {
            eventLoop?.incrementUseCount()
            while (true) {
                var parkNanos: Long
                // Workaround for bug in BE optimizer that cannot eliminate boxing here
                if (eventLoop != null) {
                    parkNanos = eventLoop.processNextEvent()
                } else {
                    parkNanos = Long.MAX_VALUE
                }
                // note: processNextEvent may lose unpark flag, so check if completed before parking
                if (isCompleted) break
                joinWorker.park(parkNanos / 1000L, true)
            }
        } finally { // paranoia
            eventLoop?.decrementUseCount()
        }
        // now return result
        val state = state.unboxState()
        (state as? CompletedExceptionally)?.let { throw it.cause }
        return state as T
    }
}
