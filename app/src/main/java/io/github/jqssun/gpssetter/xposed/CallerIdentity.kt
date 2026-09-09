package io.github.jqssun.gpssetter.xposed

/**
 * Works out which app a system-server call is being made on behalf of.
 *
 * When an app asks the system for a location, the system method receives the caller's package name
 * as one of its arguments -- sometimes directly as a string, sometimes buried inside a request or
 * identity object. Reading it lets the system-side hooks fake the answer for chosen apps only,
 * without the module ever being loaded into those apps. That is what keeps a target app from
 * being able to detect the module in its own process.
 */
object CallerIdentity {

    /** How deep to look inside request and identity objects before giving up. */
    private const val MAX_DEPTH = 4

    /** Fields that commonly carry the calling package across Android versions and vendor forks. */
    private val PACKAGE_FIELDS = arrayOf(
        "mPackageName", "packageName", "mCallingPackage", "callingPackage",
        "mCallerPackageName", "callerPackageName", "mOpPackageName", "opPackageName",
    )

    /** Fields that wrap another object which in turn holds the calling package. */
    private val NESTED_FIELDS = arrayOf(
        "mIdentity", "identity", "mCallerIdentity", "callerIdentity", "mCallingIdentity",
        "mAttributionSource", "attributionSource", "mRequest", "request", "mWorkSource",
    )

    /** Every package name that can be found among a call's arguments. */
    fun packagesFrom(args: List<Any?>): Set<String> {
        val found = linkedSetOf<String>()
        args.forEach { collect(it, found, mutableSetOf(), 0) }
        return found
    }

    private fun collect(value: Any?, out: MutableSet<String>, seen: MutableSet<Int>, depth: Int) {
        if (value == null || depth > MAX_DEPTH) return

        if (value is String) {
            if (looksLikePackage(value)) out.add(value)
            return
        }
        // Primitives and framework value types cannot contain a package name; skipping them keeps
        // this cheap enough to run on every location call.
        if (value is Number || value is Boolean || value is Char) return

        val id = System.identityHashCode(value)
        if (!seen.add(id)) return

        val cls = value.javaClass
        for (name in PACKAGE_FIELDS) {
            readField(cls, value, name)?.let {
                if (it is String && looksLikePackage(it)) out.add(it)
            }
        }
        for (name in NESTED_FIELDS) {
            readField(cls, value, name)?.let { collect(it, out, seen, depth + 1) }
        }
    }

    private fun readField(cls: Class<*>, target: Any, name: String): Any? {
        var current: Class<*>? = cls
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }.get(target)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    /**
     * A loose check: at least one dot, no spaces or path separators, and plausible characters.
     * It only needs to be good enough to avoid comparing obvious non-packages against the target
     * list -- a false positive there simply fails to match anything.
     */
    private fun looksLikePackage(value: String): Boolean =
        value.length in 3..255 &&
            value.contains('.') &&
            !value.contains(' ') &&
            !value.contains('/') &&
            value.first().isLetter() &&
            value.all { it.isLetterOrDigit() || it == '.' || it == '_' }
}
