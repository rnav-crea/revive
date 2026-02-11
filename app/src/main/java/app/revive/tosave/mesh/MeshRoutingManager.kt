package app.revive.tosave.mesh

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.mutableMapOf

/**
 * Advanced mesh routing algorithm with route discovery, optimization, and multi-hop forwarding
 */
class MeshRoutingManager(
    private val meshDatabase: MeshDatabase,
    private val currentUserId: String
) {
    companion object {
        private const val TAG = "MeshRoutingManager"
        private const val MAX_HOPS = 7
        private const val ROUTE_TIMEOUT = 300000L // 5 minutes
        private const val ROUTE_DISCOVERY_TIMEOUT = 30000L // 30 seconds
        private const val ROUTE_QUALITY_THRESHOLD = 0.3f
    }

    // Active routing table: targetUserId -> MeshRoute
    private val routingTable = ConcurrentHashMap<String, MeshRoute>()
    
    // Alternative routes for redundancy: targetUserId -> List<MeshRoute>
    private val alternativeRoutes = ConcurrentHashMap<String, MutableList<MeshRoute>>()
    
    // Pending route discoveries: targetUserId -> RouteDiscoverySession
    private val pendingDiscoveries = ConcurrentHashMap<String, RouteDiscoverySession>()
    
    // Network topology: userId -> Set<directNeighbors>
    private val networkTopology = ConcurrentHashMap<String, MutableSet<String>>()
    
    // Route quality metrics
    private val routeMetrics = ConcurrentHashMap<String, RouteMetrics>()

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Route discovery session tracking
     */
    private data class RouteDiscoverySession(
        val targetUserId: String,
        val requestId: String,
        val startTime: Long,
        val foundRoutes: MutableList<List<String>> = mutableListOf(),
        val isCompleted: Boolean = false
    )

    /**
     * Route quality metrics
     */
    private data class RouteMetrics(
        val latency: Long = 0L,
        val reliability: Float = 1.0f,
        val bandwidth: Float = 1.0f,
        val lastUsed: Long = 0L,
        val successRate: Float = 1.0f,
        val failures: Int = 0
    )

    /**
     * Initialize routing manager
     */
    suspend fun initialize() {
        Log.d(TAG, "Initializing mesh routing manager")
        
        // Load existing routes from database
        loadStoredRoutes()
        
        // Start periodic maintenance
        startPeriodicMaintenance()
        
        Log.d(TAG, "Routing manager initialized")
    }

    /**
     * Find best route to target user
     */
    suspend fun findRoute(targetUserId: String): MeshRoute? {
        Log.d(TAG, "Finding route to $targetUserId")
        
        // Check if target is direct neighbor
        if (networkTopology[currentUserId]?.contains(targetUserId) == true) {
            Log.d(TAG, "Direct route to $targetUserId")
            return createDirectRoute(targetUserId)
        }
        
        // Check existing routes in routing table
        val existingRoute = routingTable[targetUserId]
        if (existingRoute != null && isRouteValid(existingRoute)) {
            Log.d(TAG, "Using existing route to $targetUserId via ${existingRoute.nextHopUserId}")
            return existingRoute
        }
        
        // Check alternative routes
        val alternatives = alternativeRoutes[targetUserId]
        alternatives?.forEach { route ->
            if (isRouteValid(route)) {
                Log.d(TAG, "Using alternative route to $targetUserId via ${route.nextHopUserId}")
                routingTable[targetUserId] = route
                return route
            }
        }
        
        Log.d(TAG, "No valid route found to $targetUserId")
        return null
    }

    /**
     * Initiate route discovery for target user
     */
    suspend fun discoverRoute(targetUserId: String): Boolean {
        Log.d(TAG, "Starting route discovery for $targetUserId")
        
        // Check if discovery is already in progress
        if (pendingDiscoveries.containsKey(targetUserId)) {
            Log.d(TAG, "Route discovery already in progress for $targetUserId")
            return false
        }
        
        val requestId = UUID.randomUUID().toString()
        val session = RouteDiscoverySession(
            targetUserId = targetUserId,
            requestId = requestId,
            startTime = System.currentTimeMillis()
        )
        
        pendingDiscoveries[targetUserId] = session
        
        // Create route request packet
        val routeRequest = RouteRequest(
            requestId = requestId,
            sourceUserId = currentUserId,
            targetUserId = targetUserId,
            path = listOf(currentUserId),
            hops = 0,
            timestamp = System.currentTimeMillis()
        )
        
        // Broadcast to all direct neighbors
        val neighbors = networkTopology[currentUserId] ?: emptySet()
        neighbors.forEach { neighborId ->
            broadcastRouteRequest(routeRequest, neighborId)
        }
        
        // Set timeout for discovery
        coroutineScope.launch {
            delay(ROUTE_DISCOVERY_TIMEOUT)
            completeRouteDiscovery(targetUserId)
        }
        
        return true
    }

    /**
     * Handle incoming route request
     */
    suspend fun handleRouteRequest(request: RouteRequest, fromUserId: String): List<RouteReply> {
        Log.d(TAG, "Handling route request from $fromUserId for target ${request.targetUserId}")
        
        val replies = mutableListOf<RouteReply>()
        
        // Prevent loops
        if (request.path.contains(currentUserId)) {
            Log.d(TAG, "Loop detected in route request, dropping")
            return replies
        }
        
        // Check if we are the target
        if (request.targetUserId == currentUserId) {
            Log.d(TAG, "We are the target, sending route reply")
            val reply = RouteReply(
                requestId = request.requestId,
                sourceUserId = request.sourceUserId,
                targetUserId = currentUserId,
                path = request.path + currentUserId,
                hops = request.hops + 1,
                timestamp = System.currentTimeMillis(),
                quality = calculateRouteQuality(request.path + currentUserId)
            )
            replies.add(reply)
            return replies
        }
        
        // Check hop limit
        if (request.hops >= MAX_HOPS) {
            Log.d(TAG, "Max hops reached, dropping route request")
            return replies
        }
        
        // Check if we have a route to target
        val routeToTarget = routingTable[request.targetUserId]
        if (routeToTarget != null && isRouteValid(routeToTarget)) {
            Log.d(TAG, "Found existing route to ${request.targetUserId}, sending reply")
            val reply = RouteReply(
                requestId = request.requestId,
                sourceUserId = request.sourceUserId,
                targetUserId = request.targetUserId,
                path = request.path + currentUserId,
                hops = request.hops + 1 + routeToTarget.hops,
                timestamp = System.currentTimeMillis(),
                quality = calculateRouteQuality(request.path + currentUserId)
            )
            replies.add(reply)
        }
        
        // Forward request to neighbors (except sender)
        val neighbors = networkTopology[currentUserId] ?: emptySet()
        neighbors.filter { it != fromUserId }.forEach { neighborId ->
            val forwardedRequest = request.copy(
                path = request.path + currentUserId,
                hops = request.hops + 1
            )
            broadcastRouteRequest(forwardedRequest, neighborId)
        }
        
        return replies
    }

    /**
     * Handle incoming route reply
     */
    suspend fun handleRouteReply(reply: RouteReply, fromUserId: String) {
        Log.d(TAG, "Handling route reply from $fromUserId for ${reply.targetUserId}")
        
        // Check if this reply is for our discovery
        val session = pendingDiscoveries[reply.targetUserId]
        if (session?.requestId == reply.requestId) {
            Log.d(TAG, "Route reply matches our discovery session")
            session.foundRoutes.add(reply.path)
            
            // Create route entry
            if (reply.path.size >= 2) {
                val nextHop = reply.path[1] // First hop after us
                val route = MeshRoute(
                    targetUserId = reply.targetUserId,
                    nextHopUserId = nextHop,
                    hops = reply.hops,
                    quality = reply.quality,
                    lastUpdated = System.currentTimeMillis()
                )
                
                // Update routing table with best route
                val existingRoute = routingTable[reply.targetUserId]
                if (existingRoute == null || isBetterRoute(route, existingRoute)) {
                    routingTable[reply.targetUserId] = route
                    storeRoute(route)
                    Log.d(TAG, "Updated route to ${reply.targetUserId} via $nextHop")
                } else {
                    // Store as alternative route
                    addAlternativeRoute(reply.targetUserId, route)
                }
            }
        } else if (reply.sourceUserId != currentUserId) {
            // Forward reply towards source
            forwardRouteReply(reply, fromUserId)
        }
    }

    /**
     * Update network topology with direct neighbor
     */
    fun addDirectConnection(userId: String) {
        Log.d(TAG, "Adding direct connection to $userId")
        
        val neighbors = networkTopology.getOrPut(currentUserId) { mutableSetOf() }
        neighbors.add(userId)
        
        // Update route metrics
        routeMetrics[userId] = RouteMetrics(
            lastUsed = System.currentTimeMillis(),
            reliability = 1.0f
        )
        
        // Create direct route
        val directRoute = createDirectRoute(userId)
        routingTable[userId] = directRoute
        coroutineScope.launch {
            storeRoute(directRoute)
        }
    }

    /**
     * Remove connection when peer disconnects
     */
    fun removeDirectConnection(userId: String) {
        Log.d(TAG, "Removing direct connection to $userId")
        
        networkTopology[currentUserId]?.remove(userId)
        
        // Invalidate routes through this neighbor
        invalidateRoutesThrough(userId)
    }

    /**
     * Update route quality based on performance
     */
    fun updateRouteQuality(targetUserId: String, success: Boolean, latency: Long = 0L) {
        val route = routingTable[targetUserId] ?: return
        val metrics = routeMetrics.getOrPut(targetUserId) { RouteMetrics() }
        
        if (success) {
            val newSuccessRate = (metrics.successRate * 0.9f) + 0.1f
            val newReliability = minOf(1.0f, metrics.reliability + 0.05f)
            val newLatency = if (latency > 0) (metrics.latency + latency) / 2 else metrics.latency
            
            routeMetrics[targetUserId] = metrics.copy(
                successRate = newSuccessRate,
                reliability = newReliability,
                latency = newLatency,
                lastUsed = System.currentTimeMillis()
            )
            
            // Update route quality
            val newQuality = calculateRouteQuality(listOf(currentUserId, route.nextHopUserId, targetUserId))
            val updatedRoute = route.copy(quality = newQuality, lastUpdated = System.currentTimeMillis())
            routingTable[targetUserId] = updatedRoute
            coroutineScope.launch {
                storeRoute(updatedRoute)
            }
        } else {
            val newFailures = metrics.failures + 1
            val newSuccessRate = maxOf(0.0f, metrics.successRate - 0.2f)
            val newReliability = maxOf(0.0f, metrics.reliability - 0.1f)
            
            routeMetrics[targetUserId] = metrics.copy(
                failures = newFailures,
                successRate = newSuccessRate,
                reliability = newReliability
            )
            
            // If route becomes unreliable, remove it
            if (newReliability < ROUTE_QUALITY_THRESHOLD) {
                Log.w(TAG, "Route to $targetUserId is unreliable, removing")
                invalidateRoute(targetUserId)
            }
        }
    }

    /**
     * Get network statistics
     */
    fun getNetworkStats(): NetworkStats {
        return NetworkStats(
            totalRoutes = routingTable.size,
            totalUsers = networkTopology.keys.size,
            directConnections = networkTopology[currentUserId]?.size ?: 0,
            averageHops = routingTable.values.map { it.hops }.average(),
            networkDiameter = routingTable.values.maxOfOrNull { it.hops } ?: 0
        )
    }

    /**
     * Private helper functions
     */
    
    private fun createDirectRoute(userId: String): MeshRoute {
        return MeshRoute(
            targetUserId = userId,
            nextHopUserId = userId,
            hops = 1,
            quality = 1.0f,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun isRouteValid(route: MeshRoute): Boolean {
        val age = System.currentTimeMillis() - route.lastUpdated
        return route.isActive && 
               age < ROUTE_TIMEOUT && 
               route.quality >= ROUTE_QUALITY_THRESHOLD
    }

    private fun isBetterRoute(newRoute: MeshRoute, existingRoute: MeshRoute): Boolean {
        // Prefer routes with higher quality
        if (newRoute.quality > existingRoute.quality + 0.1f) return true
        if (existingRoute.quality > newRoute.quality + 0.1f) return false
        
        // If quality is similar, prefer shorter routes
        return newRoute.hops < existingRoute.hops
    }

    private fun calculateRouteQuality(path: List<String>): Float {
        if (path.size <= 1) return 1.0f
        
        var quality = 1.0f
        
        // Quality decreases with hops
        quality *= (1.0f - (path.size - 1) * 0.1f)
        
        // Factor in individual link qualities
        for (i in 0 until path.size - 1) {
            val linkMetrics = routeMetrics[path[i + 1]]
            if (linkMetrics != null) {
                quality *= linkMetrics.reliability
            }
        }
        
        return maxOf(0.0f, quality)
    }

    private suspend fun broadcastRouteRequest(request: RouteRequest, neighborId: String) {
        // This would send the route request via BluetoothMeshManager
        Log.d(TAG, "Broadcasting route request to $neighborId")
    }

    private suspend fun forwardRouteReply(reply: RouteReply, fromUserId: String) {
        // Find next hop towards source
        val routeToSource = routingTable[reply.sourceUserId]
        if (routeToSource != null) {
            Log.d(TAG, "Forwarding route reply to ${routeToSource.nextHopUserId}")
            // Send via BluetoothMeshManager
        }
    }

    private fun addAlternativeRoute(targetUserId: String, route: MeshRoute) {
        val alternatives = alternativeRoutes.getOrPut(targetUserId) { mutableListOf() }
        
        // Keep only best alternative routes (max 3)
        alternatives.add(route)
        alternatives.sortByDescending { it.quality }
        if (alternatives.size > 3) {
            alternatives.removeAt(alternatives.size - 1)
        }
    }

    private fun invalidateRoutesThrough(neighborId: String) {
        val toRemove = mutableListOf<String>()
        
        routingTable.forEach { (targetId, route) ->
            if (route.nextHopUserId == neighborId) {
                toRemove.add(targetId)
            }
        }
        
        toRemove.forEach { targetId ->
            routingTable.remove(targetId)
            
            // Try to use alternative route
            val alternatives = alternativeRoutes[targetId]
            val validAlternative = alternatives?.firstOrNull { isRouteValid(it) }
            if (validAlternative != null) {
                routingTable[targetId] = validAlternative
                alternatives.remove(validAlternative)
            }
        }
    }

    private fun invalidateRoute(targetUserId: String) {
        routingTable.remove(targetUserId)
        coroutineScope.launch {
            meshDatabase.routeDao().deleteRoute(targetUserId)
        }
    }

    private suspend fun storeRoute(route: MeshRoute) {
        meshDatabase.routeDao().insertRoute(route)
    }

    private suspend fun loadStoredRoutes() {
        val storedRoutes = meshDatabase.routeDao().getAllRoutes()
        storedRoutes.forEach { route ->
            if (isRouteValid(route)) {
                routingTable[route.targetUserId] = route
            }
        }
        Log.d(TAG, "Loaded ${routingTable.size} valid routes from storage")
    }

    private fun startPeriodicMaintenance() {
        coroutineScope.launch {
            while (true) {
                delay(60000) // Every minute
                performMaintenance()
            }
        }
    }

    private suspend fun performMaintenance() {
        Log.d(TAG, "Performing route maintenance")
        
        // Remove expired routes
        val expiredRoutes = routingTable.filter { !isRouteValid(it.value) }
        expiredRoutes.keys.forEach { routingTable.remove(it) }
        
        // Clean up completed discoveries
        val currentTime = System.currentTimeMillis()
        pendingDiscoveries.entries.removeAll { (_, session) ->
            currentTime - session.startTime > ROUTE_DISCOVERY_TIMEOUT
        }
        
        // Update route ages in database
        meshDatabase.routeDao().deleteExpiredRoutes(currentTime - ROUTE_TIMEOUT)
        
        Log.d(TAG, "Maintenance completed. Active routes: ${routingTable.size}")
    }

    private suspend fun completeRouteDiscovery(targetUserId: String) {
        val session = pendingDiscoveries.remove(targetUserId)
        if (session != null) {
            Log.d(TAG, "Route discovery completed for $targetUserId. Found ${session.foundRoutes.size} routes")
        }
    }

    /**
     * Data classes for route discovery
     */
    data class RouteRequest(
        val requestId: String,
        val sourceUserId: String,
        val targetUserId: String,
        val path: List<String>,
        val hops: Int,
        val timestamp: Long
    )

    data class RouteReply(
        val requestId: String,
        val sourceUserId: String,
        val targetUserId: String,
        val path: List<String>,
        val hops: Int,
        val timestamp: Long,
        val quality: Float
    )

    data class NetworkStats(
        val totalRoutes: Int,
        val totalUsers: Int,
        val directConnections: Int,
        val averageHops: Double,
        val networkDiameter: Int
    )

    /**
     * Shutdown routing manager
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down routing manager")
        coroutineScope.cancel()
        routingTable.clear()
        alternativeRoutes.clear()
        pendingDiscoveries.clear()
        networkTopology.clear()
        routeMetrics.clear()
    }
}