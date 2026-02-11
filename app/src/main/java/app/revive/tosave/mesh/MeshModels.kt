package app.revive.tosave.mesh

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.*

/**
 * Represents a mesh network user with unique ID
 */
@Entity(tableName = "mesh_users")
@Serializable
data class MeshUser(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val username: String,
    val displayName: String,
    val bluetoothAddress: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val publicKey: String? = null, // For encryption
    val isOnline: Boolean = false,
    val hops: Int = 0, // Distance from current user
    val discoveredThrough: String? = null // ID of user who relayed this
)

/**
 * Represents a mesh message with routing information
 */
@Entity(tableName = "mesh_messages")
@Serializable
data class MeshMessage(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val receiverId: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val messageType: MessageType = MessageType.TEXT,
    val isEncrypted: Boolean = false,
    val route: List<String> = emptyList(), // Path the message took
    val hopsRemaining: Int = 5, // TTL for message forwarding
    val isDelivered: Boolean = false,
    val deliveredAt: Long? = null,
    val signature: String? = null // Message integrity verification
)

/**
 * Represents an active route in the mesh network
 */
@Entity(tableName = "mesh_routes")
@Serializable
data class MeshRoute(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val targetUserId: String,
    val nextHopUserId: String,
    val hops: Int,
    val lastUpdated: Long = System.currentTimeMillis(),
    val quality: Float = 1.0f, // Route quality score (0.0 - 1.0)
    val isActive: Boolean = true
)

/**
 * Types of mesh messages
 */
enum class MessageType {
    TEXT,
    ROUTE_DISCOVERY,
    ROUTE_REPLY,
    HEARTBEAT,
    USER_DISCOVERY,
    SOS_EMERGENCY,
    SYSTEM
}

/**
 * Network packet for mesh communication
 */
@Serializable
data class MeshPacket(
    val id: String = UUID.randomUUID().toString(),
    val type: PacketType,
    val sourceId: String,
    val targetId: String? = null, // null for broadcast
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val route: MutableList<String> = mutableListOf(),
    val ttl: Int = 5,
    val signature: String? = null
)

enum class PacketType {
    MESSAGE,
    ROUTE_REQUEST,
    ROUTE_REPLY,
    USER_ANNOUNCEMENT,
    HEARTBEAT,
    SOS
}

/**
 * Connection status for mesh peers
 */
data class MeshConnection(
    val userId: String,
    val bluetoothAddress: String,
    val connectionStrength: Float, // 0.0 - 1.0
    val lastContact: Long,
    val isDirectConnection: Boolean
)

/**
 * Mesh network status information
 */
data class MeshNetworkStatus(
    val isActive: Boolean = false,
    val connectedPeers: Int = 0,
    val totalKnownUsers: Int = 0,
    val messagesSent: Int = 0,
    val messagesReceived: Int = 0,
    val routingTableSize: Int = 0,
    val networkDiameter: Int = 0, // Max hops to any known user
    val lastActivity: Long = 0L
)