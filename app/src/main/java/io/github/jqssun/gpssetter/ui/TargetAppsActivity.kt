package io.github.jqssun.gpssetter.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.jqssun.gpssetter.App
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivityTargetAppsBinding
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.libxposed.service.XposedService
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Lets the user choose which apps receive the fake location.
 *
 * Ticking an app does two things: it asks the framework to add the app to the module's scope (so
 * the module is loaded into it at all), and it adds the package to the spoof allowlist. Apps that
 * are in scope but not ticked keep receiving their real location, which is what makes it possible
 * to flip spoofing per app without re-scoping and restarting them.
 */
class TargetAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTargetAppsBinding
    private val adapter = AppsAdapter(::onToggle, ::onMenu)

    private var allApps: List<AppItem> = emptyList()
    private var selected: MutableSet<String> = mutableSetOf()

    private var scoped: MutableSet<String> = mutableSetOf()

    private data class AppItem(
        val packageName: String,
        val label: String,
        val info: ApplicationInfo,
        val isSystem: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTargetAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Bulk select/unselect -- act ONLY on the current search results, and only when searching.
        binding.toolbar.inflateMenu(R.menu.target_apps_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_select_all -> { bulkSelect(true); true }
                R.id.action_unselect_all -> { bulkSelect(false); true }
                else -> false
            }
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        selected = PrefManager.targetApps.toMutableSet()

        binding.search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        binding.chipUser.setOnCheckedChangeListener { _, _ -> applyFilter() }
        binding.chipSystem.setOnCheckedChangeListener { _, _ -> applyFilter() }
        binding.chipSelected.setOnCheckedChangeListener { _, _ -> applyFilter() }

        refreshScope()
        loadApps()
    }

    private fun loadApps() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                // Query apps that declare a launcher activity rather than calling
                // getInstalledApplications: MATCH_ALL bypasses the default package-visibility
                // filtering that otherwise trims most of the list, and launchable apps are the
                // only ones worth targeting anyway.
                val launcherIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = runCatching {
                    pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                }.getOrElse {
                    Timber.e(it, "queryIntentActivities failed")
                    emptyList()
                }
                Timber.i("launcher query returned %d activities", resolved.size)
                resolved.asSequence()
                    .map { it.activityInfo.applicationInfo }
                    .filter { it.packageName != packageName }
                    .distinctBy { it.packageName }
                    .map { info ->
                        AppItem(
                            packageName = info.packageName,
                            // Labels are cheap; icons are not, so those load lazily on bind.
                            label = runCatching { pm.getApplicationLabel(info).toString() }
                                .getOrDefault(info.packageName),
                            info = info,
                            isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                    .toList()
            }
            allApps = apps
            Timber.i("target apps loaded: %d", apps.size)
            binding.progress.visibility = View.GONE
            applyFilter()
        }
    }

    /** The apps currently shown, given the search text and the type/selected chips. */
    private fun currentFiltered(): List<AppItem> {
        val query = binding.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val onlySelected = binding.chipSelected.isChecked
        val showUser = binding.chipUser.isChecked
        val showSystem = binding.chipSystem.isChecked

        // Targeted apps are always pinned at the top -- in every tab and while searching -- even
        // when they do not match the tab's type or the search query.
        val selectedApps = allApps.filter { it.packageName in selected }

        val others = allApps.filter { app ->
            if (app.packageName in selected) return@filter false // already pinned above
            if (query.isNotEmpty()) {
                // Search is GLOBAL: it matches across every app regardless of which tab is active.
                app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query)
            } else {
                // No search: apply the tab filter. The Selected tab shows only the pinned apps.
                if (onlySelected) false
                else (app.isSystem && showSystem) || (!app.isSystem && showUser)
            }
        }
        return selectedApps + others
    }

    /** True when the user has typed a search -- the bulk actions only apply to a real search. */
    private fun hasSearchQuery(): Boolean =
        !binding.search.text?.toString()?.trim().isNullOrEmpty()

    private fun applyFilter() {
        // currentFiltered() already returns the pinned selected apps first, then the rest.
        adapter.submit(currentFiltered(), selected, scoped)
        binding.toolbar.subtitle = getString(R.string.target_apps_selected, selected.size)
    }

    /** Reads the current LSPosed scope so rows can show which apps are upgraded to the hook method. */
    private fun refreshScope() {
        val service = App.xposedService.value ?: return
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) {
                runCatching { service.scope?.toSet() }.getOrNull()
            }
            if (current != null) {
                scoped = current.toMutableSet()
                applyFilter()
            }
        }
    }

    /** Selects or unselects every app in the current SEARCH results. No-op without a search. */
    private fun bulkSelect(select: Boolean) {
        if (!hasSearchQuery()) {
            showToast(getString(R.string.bulk_needs_search))
            return
        }
        // Act on the actual search MATCHES, not the pinned selected apps (which are always shown at
        // the top even when they do not match) -- so "unselect all" never clears an unrelated
        // targeted app just because it was pinned.
        val query = binding.search.text?.toString()?.trim()?.lowercase().orEmpty()
        val matches = allApps.filter {
            it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query)
        }.map { it.packageName }
        if (matches.isEmpty()) return
        if (select) selected.addAll(matches) else selected.removeAll(matches.toSet())
        PrefManager.targetApps = selected
        PrefManager.spoofAllScoped = false
        applyFilter()
        showToast(
            getString(if (select) R.string.bulk_selected else R.string.bulk_unselected, matches.size)
        )
    }

    private fun onToggle(app: AppItem, nowChecked: Boolean) {
        // Ticking only adds the app to the target list -- the stealth (system-side) method. It does
        // NOT scope the app. Upgrading to the hook method is a separate, explicit action from the
        // three-dot menu, so ticking never loads the module into an app behind the user's back.
        if (nowChecked) {
            selected.add(app.packageName)
            PrefManager.spoofAllScoped = false
        } else {
            selected.remove(app.packageName)
        }
        PrefManager.targetApps = selected
        applyFilter()
    }

    /** The per-row three-dot menu: upgrade/downgrade the method, or open the app's info page. */
    private fun onMenu(app: AppItem, anchor: android.view.View) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        val isScoped = app.packageName in scoped
        menu.menu.add(
            0, 1, 0,
            if (isScoped) R.string.menu_remove_scope else R.string.menu_add_scope
        )
        menu.menu.add(0, 2, 1, R.string.menu_app_info)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> if (isScoped) removeScope(app.packageName) else addScope(app.packageName)
                2 -> openAppInfo(app.packageName)
            }
            true
        }
        menu.show()
    }

    private fun openAppInfo(pkg: String) {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", pkg, null))
            )
        }.onFailure { Timber.w(it, "could not open app info for %s", pkg) }
    }

    private fun addScope(pkg: String) {
        val service = App.xposedService.value
        if (service == null) {
            showToast(getString(R.string.target_apps_no_service))
            return
        }
        requestScope(service, pkg)
    }

    private fun removeScope(pkg: String) {
        val service = App.xposedService.value ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { service.removeScope(listOf(pkg)) }
                .onSuccess {
                    scoped.remove(pkg)
                    withContext(Dispatchers.Main) {
                        applyFilter()
                        showToast(getString(R.string.scope_removed, pkg))
                    }
                }
                .onFailure { Timber.w(it, "removeScope failed for %s", pkg) }
        }
    }

    private fun requestScope(service: XposedService, pkg: String) {
        val callback = object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: MutableList<String>) {
                Timber.i("scope approved: %s", approved)
                scoped.add(pkg)
                // The module only loads on next launch, so force-stop the app to reload it.
                runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg")) }
                runOnUiThread {
                    applyFilter()
                    showToast(getString(R.string.scope_added, pkg))
                }
            }

            override fun onScopeRequestFailed(message: String) {
                Timber.w("scope request failed for %s: %s", pkg, message)
                runOnUiThread { showToast(getString(R.string.target_apps_scope_failed, message)) }
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { service.requestScope(listOf(pkg), callback) }
                .onFailure { e ->
                    Timber.w(e, "requestScope threw for %s", pkg)
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.target_apps_scope_failed, e.message.orEmpty()))
                    }
                }
        }
    }

    private class AppsAdapter(
        private val onToggle: (AppItem, Boolean) -> Unit,
        private val onMenu: (AppItem, View) -> Unit,
    ) : RecyclerView.Adapter<AppsAdapter.Holder>() {

        private var items: List<AppItem> = emptyList()
        private var selected: Set<String> = emptySet()
        private var scoped: Set<String> = emptySet()

        fun submit(items: List<AppItem>, selected: Set<String>, scoped: Set<String>) {
            this.items = items
            this.selected = selected.toSet()
            this.scoped = scoped.toSet()
            notifyDataSetChanged()
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val pkg: TextView = view.findViewById(R.id.pkg)
            val toggle: MaterialSwitch = view.findViewById(R.id.toggle)
            val badge: TextView = view.findViewById(R.id.scoped_badge)
            val menu: android.widget.ImageButton = view.findViewById(R.id.menu)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_target_app, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = items[position]
            holder.icon.setImageDrawable(
                runCatching {
                    holder.itemView.context.packageManager.getApplicationIcon(app.info)
                }.getOrNull()
            )
            holder.name.text = app.label
            holder.pkg.text = app.packageName
            holder.toggle.isChecked = app.packageName in selected
            // "Hooked" badge marks apps upgraded to the in-app method via the three-dot menu.
            holder.badge.visibility = if (app.packageName in scoped) View.VISIBLE else View.GONE
            holder.itemView.setOnClickListener {
                onToggle(app, !holder.toggle.isChecked)
            }
            holder.menu.setOnClickListener { onMenu(app, it) }
        }
    }
}
