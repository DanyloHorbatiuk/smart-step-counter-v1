package com.example.smartstepcounter.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.example.smartstepcounter.decision.SmartAction
import com.example.smartstepcounter.decision.SmartMetric
import kotlinx.coroutines.flow.Flow

/** Lab 7: a user rule "if [metric] > [threshold] then [action]", table smart_rules. Enums are stored by name. */
@Entity(tableName = "smart_rules")
data class SmartRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val enabled: Boolean = true,
    val metric: SmartMetric,
    val threshold: Float,
    val action: SmartAction,
)

@Dao
interface SmartRuleDao {
    @Insert
    suspend fun insert(rule: SmartRule): Long

    @Update
    suspend fun update(rule: SmartRule)

    @Delete
    suspend fun delete(rule: SmartRule)

    @Query("SELECT * FROM smart_rules ORDER BY id ASC")
    fun observeAll(): Flow<List<SmartRule>>
}
