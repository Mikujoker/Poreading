package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.OpdsSource
import kotlinx.coroutines.flow.Flow

@Dao
interface OpdsSourceDao {

    @get:Query("select * from opdsSources order by sortNumber")
    val all: List<OpdsSource>

    @Query("select * from opdsSources order by sortNumber")
    fun flowAll(): Flow<List<OpdsSource>>

    @Query("select * from opdsSources where url = :url")
    fun getByUrl(url: String): OpdsSource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg opdsSource: OpdsSource)

    @Update
    fun update(vararg opdsSource: OpdsSource)

    @Delete
    fun delete(vararg opdsSource: OpdsSource)
}
