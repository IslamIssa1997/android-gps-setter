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
        // A LocationProviderManager registration keeps its request (which carries the WorkSource of
        // the app it proxies for, e.g. gms->WhatsApp) under these names across versions.
        "mProviderLocationRequest", "mBaseRequest", "mLocationRequest",
    )

    /** No-arg getters that expose a nested request/identity/work source when the field name varies. */
    private val NESTED_METHODS = arrayOf("getRequest", "getWorkSource", "getIdentity")

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
        // A WorkSource names the app(s) a proxied request is really for -- e.g. Play Services'
        // fused provider asking on behalf of Google Maps or WhatsApp. Those names live in an
        // internal array (and optional work chains) that the generic field walk below never opens,
        // so pull them out explicitly. Without this, gms-proxied requests look like they belong to
        // gms and slip past per-app targeting.
        if (cls.name == "android.os.WorkSource") {
            collectWorkSource(value, out)
            return
        }
        for (name in PACKAGE_FIELDS) {
            readField(cls, value, name)?.let {
                if (it is String && looksLikePackage(it)) out.add(it)
            }
        }
        for (name in NESTED_FIELDS) {
            readField(cls, value, name)?.let { collect(it, out, seen, depth + 1) }
        }
        // Field names for the request/work source differ across Android versions; reach them through
        // their public getters too (e.g. Registration.getRequest().getWorkSource()).
        for (name in NESTED_METHODS) {
            runCatching { cls.getMethod(name).invoke(value) }
                .getOrNull()?.let { collect(it, out, seen, depth + 1) }
        }
    }

    /** Pulls every named package out of an android.os.WorkSource, including its work chains. */
    private fun collectWorkSource(ws: Any, out: MutableSet<String>) {
        val cls = ws.javaClass
        // Preferred: size() + getPackageName(i).
        val viaApi = runCatching {
            val size = cls.getMethod("size").invoke(ws) as Int
            val getName = cls.getMethod("getPackageName", Int::class.javaPrimitiveType)
            var added = false
            for (i in 0 until size) {
                (getName.invoke(ws, i) as? String)?.let {
                    if (looksLikePackage(it)) { out.add(it); added = true }
                }
            }
            added
        }.getOrDefault(false)
        // Fallback: read the mNames String[] field directly.
        if (!viaApi) {
            (readField(cls, ws, "mNames") as? Array<*>)?.forEach {
                (it as? String)?.let { n -> if (looksLikePackage(n)) out.add(n) }
            }
        }
        // Work (attribution) chains carry their own package names.
        runCatching {
            val chains = cls.getMethod("getWorkChains").invoke(ws) as? List<*> ?: return@runCatching
            chains.filterNotNull().forEach { chain ->
                (readField(chain.javaClass, chain, "mNames") as? Array<*>)?.forEach {
                    (it as? String)?.let { n -> if (looksLikePackage(n)) out.add(n) }
                }
            }
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
