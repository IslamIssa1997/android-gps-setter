package io.github.jqssun.gpssetter.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Favorite(
    @PrimaryKey(autoGenerate = false)
    val id: Long? = null,
    val address: String?,
    val lat: Double?,
    val lng: Double?,
    /** Optional free-text note. Added in schema version 2. */
    val description: String? = null,
    /** Manual sort order for the list (drag-to-reorder in Settings). Added in schema version 3. */
    val position: Int = 0,
)
