package io.github.jqssun.gpssetter.room
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

        //insert data to room database
        @Insert(onConflict = OnConflictStrategy.IGNORE)
        suspend fun insertToRoomDatabase(favorite: Favorite) : Long

        // for update single favorite
        @Update
        suspend fun updateUserDetails(favorite: Favorite)

        //delete single favorite
        @Delete
        suspend fun deleteSingleFavorite(favorite: Favorite)

       //get all Favorite inserted to room database...normally this is supposed to be a list of Favorites
        // Manual sort order first (drag-to-reorder); id DESC keeps the previous order for rows never
        // reordered (they all share position 0).
        @Transaction
        @Query("SELECT * FROM favorite ORDER BY position ASC, id DESC")
        fun getAllFavorites() : Flow<List<Favorite>>

        /** One-shot snapshot for the edit screen (no Flow). */
        @Transaction
        @Query("SELECT * FROM favorite ORDER BY position ASC, id DESC")
        suspend fun getAllFavoritesList() : List<Favorite>

        //get single favorite inserted to room database
        @Transaction
        @Query("SELECT * FROM favorite WHERE id = :id ORDER BY id DESC")
        fun getSingleFavorite(id: Long) : Favorite?

        /** Persist a new manual order after a drag. */
        @Query("UPDATE favorite SET position = :position WHERE id = :id")
        suspend fun updatePosition(id: Long, position: Int)

}