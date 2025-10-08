package com.example.withcrossdemo.core.util

import timber.log.Timber

/**
 * Forward Timber logs that start with [prefix] to [onLine].
 * - Non-invasive: does not change existing logging; only mirrors to UI.
 */
class UiLogBridge(
    private val prefix: String,
    private val onLine: (String) -> Unit
) : Timber.DebugTree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (message.startsWith(prefix)) {
            onLine(message)
        }
        // keep normal log behavior
        super.log(priority, tag, message, t)
    }
}
