package com.azim.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "users", indices = [Index(value = ["email"], unique = true)])
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val email: String,
    val salt: String,
    val hash: String
)

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val title: String,
    val category: String,
    val level: String,
    val dailyMinutes: Int,
    val createdAt: Long,
    val active: Boolean = true
)

@Entity(tableName = "tasks")
data class DailyTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val goalId: Long,
    val date: String,
    val title: String,
    val detail: String,
    val minutes: Int,
    val done: Boolean = false,
    val doneAt: Long = 0,
    val note: String = ""
)

@Entity(tableName = "reports")
data class WeeklyReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val goalId: Long,
    val createdAt: Long,
    val photoPath: String,
    val feedback: String
)

@Entity(tableName = "chat")
data class ChatMsg(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val role: String, // "user" | "model"
    val text: String,
    val ts: Long
)

@Entity(tableName = "trainings")
data class Training(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val goalId: Long,
    val taskId: Long,
    val title: String,
    val plannedMin: Int,
    val startedAt: Long,
    val endedAt: Long = 0,
    val status: String = "active", // active | done | quit | emergency
    val note: String = ""
)

@Dao
interface AppDao {
    // Kullanıcı
    @Insert
    suspend fun insertUser(u: User): Long

    @Query("SELECT * FROM users WHERE email = :email LIMIT 1")
    suspend fun userByEmail(email: String): User?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun userById(id: Long): User?

    // Hedefler
    @Insert
    suspend fun insertGoal(g: Goal): Long

    @Query("SELECT * FROM goals WHERE userId = :uid AND active = 1 ORDER BY id")
    fun goals(uid: Long): Flow<List<Goal>>

    @Query("SELECT * FROM goals WHERE userId = :uid AND active = 1 ORDER BY id")
    suspend fun goalsOnce(uid: Long): List<Goal>

    // Görevler
    @Insert
    suspend fun insertTasks(t: List<DailyTask>)

    @Query("SELECT * FROM tasks WHERE userId = :uid AND date = :date ORDER BY id")
    fun tasksFor(uid: Long, date: String): Flow<List<DailyTask>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun taskById(id: Long): DailyTask?

    @Query("SELECT COUNT(*) FROM tasks WHERE userId = :uid AND date = :date AND done = 0")
    suspend fun pendingCount(uid: Long, date: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE userId = :uid AND date = :date")
    suspend fun totalCount(uid: Long, date: String): Int

    @Query("SELECT COUNT(*) FROM tasks WHERE userId = :uid AND goalId = :goalId AND date = :date")
    suspend fun countForGoal(uid: Long, goalId: Long, date: String): Int

    @Query("UPDATE tasks SET done = 1, doneAt = :ts, note = :note WHERE id = :id")
    suspend fun completeTask(id: Long, ts: Long, note: String)

    @Query("SELECT date FROM tasks WHERE userId = :uid GROUP BY date HAVING SUM(CASE WHEN done = 0 THEN 1 ELSE 0 END) = 0 ORDER BY date DESC")
    suspend fun fullyDoneDates(uid: Long): List<String>

    @Query("SELECT * FROM tasks WHERE userId = :uid AND date >= :from ORDER BY date, id")
    suspend fun tasksSince(uid: Long, from: String): List<DailyTask>

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM tasks WHERE userId = :uid AND done = 1")
    suspend fun totalDoneMinutes(uid: Long): Int

    // Haftalık raporlar
    @Insert
    suspend fun insertReport(r: WeeklyReport): Long

    @Query("SELECT * FROM reports WHERE userId = :uid ORDER BY createdAt DESC")
    fun reports(uid: Long): Flow<List<WeeklyReport>>

    // Koç sohbeti
    @Insert
    suspend fun insertChat(m: ChatMsg): Long

    @Query("SELECT * FROM chat WHERE userId = :uid ORDER BY id")
    fun chat(uid: Long): Flow<List<ChatMsg>>

    @Query("SELECT * FROM chat WHERE userId = :uid ORDER BY id DESC LIMIT :n")
    suspend fun recentChat(uid: Long, n: Int): List<ChatMsg>

    // Eğitim kayıtları
    @Insert
    suspend fun insertTraining(t: Training): Long

    @Query("UPDATE trainings SET status = :status, endedAt = :ts, note = :note WHERE id = :id")
    suspend fun finishTraining(id: Long, status: String, ts: Long, note: String)

    @Query("UPDATE trainings SET status = 'quit', endedAt = :ts WHERE userId = :uid AND status = 'active'")
    suspend fun closeActiveTrainings(uid: Long, ts: Long)

    @Query("SELECT * FROM trainings WHERE userId = :uid AND status != 'active' ORDER BY startedAt DESC LIMIT :n")
    fun recentTrainings(uid: Long, n: Int): Flow<List<Training>>

    @Query("SELECT COALESCE(SUM(plannedMin), 0) FROM trainings WHERE userId = :uid AND status = 'done'")
    suspend fun trainedMinutes(uid: Long): Int
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trainings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`userId` INTEGER NOT NULL, `goalId` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, `plannedMin` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `note` TEXT NOT NULL)"
        )
    }
}

@Database(
    entities = [User::class, Goal::class, DailyTask::class, WeeklyReport::class, ChatMsg::class, Training::class],
    version = 2,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(context: Context): AppDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDb::class.java, "azim.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
