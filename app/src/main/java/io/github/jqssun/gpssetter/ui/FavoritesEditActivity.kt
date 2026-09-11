package io.github.jqssun.gpssetter.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivityFavoritesEditBinding
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.room.FavoriteDao
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import javax.inject.Inject

/**
 * Standalone editor for the saved-locations list, reached from Settings › Favourites › Edit list.
 *
 * The favourites dialog on the map screen only picks a location; deleting and reordering live here
 * so that screen stays as it was. Drag a row (by its handle or a long press) to change the order,
 * which is persisted as [Favorite.position]; tap the bin to delete a single location.
 */
@AndroidEntryPoint
class FavoritesEditActivity : AppCompatActivity() {

    @Inject
    lateinit var favoriteDao: FavoriteDao

    private lateinit var binding: ActivityFavoritesEditBinding
    private lateinit var adapter: FavAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private val items = mutableListOf<Favorite>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FavAdapter()
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(binding.list)

        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { favoriteDao.getAllFavoritesList() }
            items.clear()
            items.addAll(loaded)
            adapter.notifyDataSetChanged()
            updateEmpty()
        }
    }

    private fun updateEmpty() {
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Writes each row's new index as its position so the manual order survives a restart. */
    private fun persistOrder() {
        val snapshot = items.mapIndexedNotNull { index, fav -> fav.id?.let { it to index } }
        lifecycleScope.launch(Dispatchers.IO) {
            snapshot.forEach { (id, position) -> favoriteDao.updatePosition(id, position) }
        }
    }

    private fun delete(position: Int) {
        if (position !in items.indices) return
        val removed = items.removeAt(position)
        adapter.notifyItemRemoved(position)
        updateEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            favoriteDao.deleteSingleFavorite(removed)
            // Renumber what's left so positions stay contiguous.
            items.mapIndexedNotNull { index, fav -> fav.id?.let { it to index } }
                .forEach { (id, pos) -> favoriteDao.updatePosition(id, pos) }
        }
        showToast(getString(R.string.favorite_deleted, removed.address.orEmpty()))
    }

    /** Tapping a row opens the same name + note fields used when adding, prefilled, to rename it. */
    private fun showRenameDialog(position: Int) {
        if (position !in items.indices) return
        val fav = items[position]
        val view = layoutInflater.inflate(R.layout.dialog, null)
        val nameField = view.findViewById<EditText>(R.id.search_edittxt)
        val noteField = view.findViewById<EditText>(R.id.description_edittxt)
        nameField.setText(fav.address.orEmpty())
        noteField.setText(fav.description.orEmpty())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_edit_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val newName = nameField.text.toString().trim().ifEmpty { fav.address }
                val newNote = noteField.text.toString().trim().ifEmpty { null }
                val updated = fav.copy(address = newName, description = newNote)
                if (position in items.indices) {
                    items[position] = updated
                    adapter.notifyItemChanged(position)
                }
                lifecycleScope.launch(Dispatchers.IO) { favoriteDao.updateUserDetails(updated) }
            }
            .show()
    }

    private inner class FavAdapter : RecyclerView.Adapter<FavAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.fav_name)
            val note: TextView = view.findViewById(R.id.fav_note)
            val handle: ImageView = view.findViewById(R.id.drag_handle)
            val delete: ImageButton = view.findViewById(R.id.delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_favorite_edit, parent, false)
        )

        override fun getItemCount() = items.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val fav = items[position]
            holder.name.text = fav.address
            holder.note.text = fav.description.orEmpty()
            holder.note.visibility = if (fav.description.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.delete.setOnClickListener { delete(holder.adapterPosition) }
            holder.itemView.setOnClickListener { showRenameDialog(holder.adapterPosition) }
            // Start a drag as soon as the handle is touched, on top of long-press-anywhere.
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                }
                false
            }
        }
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun isLongPressDragEnabled() = true

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            Collections.swap(items, from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        // Persist once, when the drag finishes, rather than on every intermediate swap.
        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            persistOrder()
        }
    }
}
