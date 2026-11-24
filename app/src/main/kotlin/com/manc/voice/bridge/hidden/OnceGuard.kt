package com.manc.voice.bridge.hidden

/**
 * Runs a block at most once and never re-entrantly: a call made from inside the
 * block itself (directly or through a lazy value the block touches) returns
 * immediately instead of recursing. Pure Kotlin so it is unit-tested on the JVM.
 */
class OnceGuard {
    @Volatile var done = false
        private set
    private var running = false

    @Synchronized
    fun run(block: () -> Unit) {
        if (done || running) return
        running = true
        try {
            block()
        } finally {
            running = false
            done = true
        }
    }
}
