package io.github.jqssun.gpssetter.xposed

import io.github.libxposed.api.XposedInterface.ExceptionMode

/**
 * How aggressively hook errors are surfaced, tied to the Detailed Logging setting so it is a live
 * switch the user controls -- no rebuild needed.
 *
 * - Detailed Logging ON  -> [ExceptionMode.PASSTHROUGH]: a hook that throws propagates the exception
 *   to the caller, so the bug is impossible to miss (it crashes the hooked app, or soft-reboots
 *   system_server). This is the framework's documented debugging mode. The framework's own safe mode
 *   (auto-disable after repeated soft reboots, or manual disable from recovery) is the backstop.
 * - Detailed Logging OFF -> [ExceptionMode.PROTECTIVE]: a hook that throws is caught and logged and
 *   the call proceeds as if unhooked, so the module keeps working for everyday use.
 */
internal fun hookMode(verbose: Boolean): ExceptionMode =
    if (verbose) ExceptionMode.PASSTHROUGH else ExceptionMode.PROTECTIVE
