package app.revive.tosave.mesh

import androidx.room.*
import androidx.room.Database
import kotlinx.coroutines.flow.Flow

/**
 * DAO for mesh users
 */
@Dao
interface MeshUserDao {
    @Query("SELECT * FROM mesh_users ORDER BY lastSeen DESC")
    suspend fun getAllUsers(): List<MeshUser>
    
    @Query("SELECT * FROM mesh_users WHERE isOnline = 1 ORDER BY hops ASC")
    suspend fun getOnlineUsers(): List<MeshUser>
    
    @Query("SELECT * FROM mesh_users ORDER BY lastSeen DESC")
    fun getAllUsersFlow(): Flow<List<MeshUser>>
    
    @Query("SELECT * FROM mesh_users WHERE id = :userId")
    suspend fun getUserById(userId: String): MeshUser?
    
    @Query("SELECT * FROM mesh_users WHERE username = :username")
    suspend fun getUserByUsername(username: String): MeshUser?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: MeshUser)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<MeshUser>)
    
    @Update
    suspend fun updateUser(user: MeshUser)
    
    @Query("UPDATE mesh_users SET lastSeen = :timestamp WHERE id = :userId")
    suspend fun updateLastSeen(userId: String, timestamp: Long)
    
    @Query("UPDATE mesh_users SET isOnline = :isOnline WHERE id = :userId")
    suspend fun updateOnlineStatus(userId: String, isOnline: Boolean)
    
    @Query("UPDATE mesh_users SET hops = :hops WHERE id = :userId")
    suspend fun updateHops(userId: String, hops: Int)
    
    @Delete
    suspend fun deleteUser(user: MeshUser)
    
    @Query("DELETE FROM mesh_users WHERE lastSeen < :threshold")
    suspend fun deleteInactiveUsers(threshold: Long)
    
    @Query("DELETE FROM mesh_users")
    suspend fun deleteAllUsers()
}

/**
 * DAO for mesh messages
 */
@Dao
interface MeshMessageDao {
    @Query("SELECT * FROM mesh_messages ORDER BY timestamp DESC")
    suspend fun getAllMessages(): List<MeshMessage>
    
    @Query("SELECT * FROM mesh_messages WHERE senderId = :userId OR receiverId = :userId ORDER BY timestamp DESC")
    suspend fun getMessagesForUser(userId: String): List<MeshMessage>
    
    @Query("SELECT * FROM mesh_messages WHERE (senderId = :user1 AND receiverId = :user2) OR (senderId = :user2 AND receiverId = :user1) ORDER BY timestamp ASC")
    suspend fun getConversation(user1: String, user2: String): List<MeshMessage>
    
    @Query("SELECT * FROM mesh_messages WHERE (senderId = :user1 AND receiverId = :user2) OR (senderId = :user2 AND receiverId = :user1) ORDER BY timestamp ASC")
    fun getConversationFlow(user1: String, user2: String): Flow<List<MeshMessage>>
    
    @Query("SELECT * FROM mesh_messages WHERE messageType = :type ORDER BY timestamp DESC")
    suspend fun getMessagesByType(type: MessageType): List<MeshMessage>
    
    @Query("SELECT * FROM mesh_messages WHERE isDelivered = 0 ORDER BY timestamp ASC")
    suspend fun getPendingMessages(): List<MeshMessage>
    
    @Query("SELECT * FROM mesh_messages WHERE hopsRemaining > 0 ORDER BY timestamp ASC")
    suspend fun getMessagesToForward(): List<MeshMessage>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MeshMessage)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MeshMessage>)
    
    @Update
    suspend fun updateMessage(message: MeshMessage)
    
    @Query("UPDATE mesh_messages SET isDelivered = 1, deliveredAt = :deliveredAt WHERE id = :messageId")
    suspend fun markAsDelivered(messageId: String, deliveredAt: Long)
    
    @Query("UPDATE mesh_messages SET hopsRemaining = :hops WHERE id = :messageId")
    suspend fun updateHopsRemaining(messageId: String, hops: Int)
    
    @Delete
    suspend fun deleteMessage(message: MeshMessage)
    
    @Query("DELETE FROM mesh_messages WHERE timestamp < :threshold")
    suspend fun deleteOldMessages(threshold: Long)
    
    @Query("DELETE FROM mesh_messages")
    suspend fun deleteAllMessages()
    
    // Conversation management
    @Query("SELECT DISTINCT CASE WHEN senderId = :currentUserId THEN receiverId ELSE senderId END as contactId FROM mesh_messages WHERE senderId = :currentUserId OR receiverId = :currentUserId")
    suspend fun getConversationContacts(currentUserId: String): List<String>
    
    @Query("SELECT * FROM mesh_messages WHERE (senderId = :currentUserId OR receiverId = :currentUserId) AND id IN (SELECT id FROM mesh_messages WHERE (senderId = :currentUserId OR receiverId = :currentUserId) GROUP BY CASE WHEN senderId = :currentUserId THEN receiverId ELSE senderId END ORDER BY timestamp DESC)")
    suspend fun getLatestConversations(currentUserId: String): List<MeshMessage>
}

/**
 * DAO for mesh routes
 */
@Dao
interface MeshRouteDao {
    @Query("SELECT * FROM mesh_routes WHERE isActive = 1 ORDER BY quality DESC")
    suspend fun getAllRoutes(): List<MeshRoute>
    
    @Query("SELECT * FROM mesh_routes WHERE targetUserId = :targetUserId AND isActive = 1 ORDER BY quality DESC")
    suspend fun getRoutesToTarget(targetUserId: String): List<MeshRoute>
    
    @Query("SELECT * FROM mesh_routes WHERE targetUserId = :targetUserId AND isActive = 1 ORDER BY quality DESC LIMIT 1")
    suspend fun getBestRouteToTarget(targetUserId: String): MeshRoute?
    
    @Query("SELECT * FROM mesh_routes WHERE nextHopUserId = :nextHopUserId AND isActive = 1")
    suspend fun getRoutesThroughHop(nextHopUserId: String): List<MeshRoute>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: MeshRoute)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutes(routes: List<MeshRoute>)
    
    @Update
    suspend fun updateRoute(route: MeshRoute)
    
    @Query("UPDATE mesh_routes SET quality = :quality, lastUpdated = :timestamp WHERE targetUserId = :targetUserId")
    suspend fun updateRouteQuality(targetUserId: String, quality: Float, timestamp: Long)
    
    @Query("UPDATE mesh_routes SET isActive = 0 WHERE targetUserId = :targetUserId")
    suspend fun deactivateRoutesToTarget(targetUserId: String)
    
    @Query("UPDATE mesh_routes SET isActive = 0 WHERE nextHopUserId = :nextHopUserId")
    suspend fun deactivateRoutesThroughHop(nextHopUserId: String)
    
    @Delete
    suspend fun deleteRoute(route: MeshRoute)
    
    @Query("DELETE FROM mesh_routes WHERE targetUserId = :targetUserId")
    suspend fun deleteRoute(targetUserId: String)
    
    @Query("DELETE FROM mesh_routes WHERE lastUpdated < :threshold")
    suspend fun deleteExpiredRoutes(threshold: Long)
    
    @Query("DELETE FROM mesh_routes")
    suspend fun deleteAllRoutes()
    
    // Route analysis
    @Query("SELECT COUNT(*) FROM mesh_routes WHERE isActive = 1")
    suspend fun getActiveRouteCount(): Int
    
    @Query("SELECT AVG(hops) FROM mesh_routes WHERE isActive = 1")
    suspend fun getAverageHops(): Double
    
    @Query("SELECT MAX(hops) FROM mesh_routes WHERE isActive = 1")
    suspend fun getMaxHops(): Int
}

/**
 * Room database for mesh networking
 */
@Database(
    entities = [MeshUser::class, MeshMessage::class, MeshRoute::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(MeshTypeConverters::class)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun userDao(): MeshUserDao
    abstract fun messageDao(): MeshMessageDao
    abstract fun routeDao(): MeshRouteDao
    
    companion object {
        const val DATABASE_NAME = "mesh_database"
    }
}

/**
 * Type converters for Room database
 */
class MeshTypeConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return if (value.isEmpty()) emptyList() else value.split(",")
    }

    @TypeConverter
    fun fromMessageType(type: MessageType): String {
        return type.name
    }

    @TypeConverter
    fun toMessageType(type: String): MessageType {
        return MessageType.valueOf(type)
    }
}