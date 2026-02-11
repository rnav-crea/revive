package app.revive.tosave.mesh.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.revive.tosave.mesh.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main mesh chat screen with Instagram-like user interface
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshChatScreen(
    viewModel: MeshChatViewModel = viewModel(),
    onNavigateToChat: (MeshUser) -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val discoveredUsers by viewModel.discoveredUsers.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isInitialized by viewModel.isInitialized.collectAsState()
    
    val scope = rememberCoroutineScope()
    var showUserDiscovery by remember { mutableStateOf(false) }
    var showNetworkStatus by remember { mutableStateOf(false) }
    var showSOSDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // Initialize mesh chat system
    LaunchedEffect(Unit) {
        if (!isInitialized) {
            viewModel.initialize()
        }
    }

    // Handle events
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MeshChatViewModel.MeshChatEvent.SOSReceived -> {
                    // Handle SOS message reception
                }
                is MeshChatViewModel.MeshChatEvent.Error -> {
                    // Handle errors
                }
                else -> {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top app bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "MeshChat",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (networkStatus.isActive) {
                        Text(
                            text = "${networkStatus.connectedPeers} connected • ${discoveredUsers.size} users",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            actions = {
                // Network status indicator with peer count
                Box {
                    IconButton(onClick = { showNetworkStatus = true }) {
                        Icon(
                            imageVector = if (networkStatus.isActive) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = "Network Status",
                            tint = if (networkStatus.isActive) Color.Green else Color.Red
                        )
                    }
                    
                    // Show connected peer count badge
                    if (networkStatus.connectedPeers > 0) {
                        Badge(
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = "${networkStatus.connectedPeers}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                
                // User discovery
                IconButton(onClick = { showUserDiscovery = true }) {
                    Icon(
                        imageVector = Icons.Default.PersonSearch,
                        contentDescription = "Discover Users"
                    )
                }
                
                // Debug: Add test user (for testing)
                IconButton(onClick = { viewModel.addTestUser() }) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "Add Test User"
                    )
                }
                
                // SOS button
                IconButton(
                    onClick = { showSOSDialog = true },
                    modifier = Modifier.background(
                        Color.Red.copy(alpha = 0.1f),
                        CircleShape
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Emergency SOS",
                        tint = Color.Red
                    )
                }
                
                // Settings
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text("Initializing mesh network...")
                }
            }
        } else {
            // Main content
            if (conversations.isEmpty() && discoveredUsers.isEmpty()) {
                EmptyStateScreen(
                    onDiscoverUsers = { showUserDiscovery = true },
                    onShowHelp = { showHelpDialog = true }
                )
            } else {
                ConversationsList(
                    conversations = conversations,
                    discoveredUsers = discoveredUsers,
                    onConversationClick = onNavigateToChat,
                    onUserClick = onNavigateToChat
                )
            }
        }
    }

    // User Discovery Sheet
    if (showUserDiscovery) {
        UserDiscoverySheet(
            discoveredUsers = discoveredUsers,
            isDiscovering = uiState.showUserDiscovery,
            onDismiss = { showUserDiscovery = false },
            onUserSelect = { user ->
                onNavigateToChat(user)
                showUserDiscovery = false
            },
            onStartDiscovery = { viewModel.startUserDiscovery() },
            onStopDiscovery = { viewModel.stopUserDiscovery() }
        )
    }

    // Network Status Sheet
    if (showNetworkStatus) {
        NetworkStatusSheet(
            networkStatus = networkStatus,
            networkStats = viewModel.getNetworkStats(),
            onDismiss = { showNetworkStatus = false }
        )
    }

    // SOS Dialog
    if (showSOSDialog) {
        SOSDialog(
            onDismiss = { showSOSDialog = false },
            onSendSOS = { location ->
                scope.launch {
                    viewModel.sendSOSMessage(location)
                    showSOSDialog = false
                }
            }
        )
    }

    // Help Dialog
    if (showHelpDialog) {
        HelpDialog(onDismiss = { showHelpDialog = false })
    }
}

@Composable
private fun ConversationsList(
    conversations: List<MeshChatViewModel.ConversationSummary>,
    discoveredUsers: List<MeshUser>,
    onConversationClick: (MeshUser) -> Unit,
    onUserClick: (MeshUser) -> Unit
) {
    // Debug logging
    LaunchedEffect(discoveredUsers) {
        Log.d("MeshChatScreen", "Discovered users count: ${discoveredUsers.size}")
        discoveredUsers.forEach { user ->
            Log.d("MeshChatScreen", "Discovered user: ${user.username} (${user.bluetoothAddress})")
        }
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // Recent conversations
        if (conversations.isNotEmpty()) {
            item {
                SectionHeader("Recent Conversations")
            }
            
            items(conversations) { conversation ->
                ConversationItem(
                    conversation = conversation,
                    onClick = { onConversationClick(conversation.user) }
                )
            }
            
            if (discoveredUsers.isNotEmpty()) {
                item {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
        
        // Available users
        if (discoveredUsers.isNotEmpty()) {
            item {
                SectionHeader("Available Users (${discoveredUsers.size})")
            }
            
            items(discoveredUsers.filter { user ->
                conversations.none { it.user.id == user.id }
            }) { user ->
                UserItem(
                    user = user,
                    onClick = { onUserClick(user) }
                )
            }
        } else {
            // Show message when no users discovered
            item {
                Text(
                    text = "No users discovered yet.\nTap 'Discover Users Nearby' to scan for other devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: MeshChatViewModel.ConversationSummary,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // User avatar
            UserAvatar(
                user = conversation.user,
                size = 56.dp,
                showOnlineIndicator = conversation.isOnline
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Conversation info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.user.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (conversation.lastMessage != null) {
                        Text(
                            text = formatTimestamp(conversation.lastMessage.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Text(
                    text = "@${conversation.user.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                
                if (conversation.lastMessage != null) {
                    Text(
                        text = when (conversation.lastMessage.messageType) {
                            MessageType.SOS_EMERGENCY -> "🆘 Emergency Message"
                            else -> conversation.lastMessage.content
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Status indicators
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Unread count
                if (conversation.unreadCount > 0) {
                    Badge {
                        Text(
                            text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                
                // Encryption status
                EncryptionIndicator(conversation.encryptionStatus)
                
                // Hop count
                if (conversation.user.hops > 0) {
                    Text(
                        text = "${conversation.user.hops} hops",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun UserItem(
    user: MeshUser,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(
                user = user,
                size = 48.dp,
                showOnlineIndicator = user.isOnline
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    
                    // Test user indicator
                    if (user.bluetoothAddress?.startsWith("TEST:") == true) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Test User",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (user.bluetoothAddress?.startsWith("TEST:") == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                
                if (user.bluetoothAddress?.startsWith("TEST:") == true) {
                    Text(
                        text = "Debug user - won't connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (user.hops > 0) {
                    Text(
                        text = "${user.hops} hop${if (user.hops > 1) "s" else ""} away",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // New chat indicator
            Icon(
                imageVector = Icons.Default.ChatBubbleOutline,
                contentDescription = "Start Chat",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun UserAvatar(
    user: MeshUser,
    size: androidx.compose.ui.unit.Dp,
    showOnlineIndicator: Boolean = false
) {
    Box {
        Surface(
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.displayName.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        if (showOnlineIndicator && user.isOnline) {
            Surface(
                modifier = Modifier
                    .size(16.dp)
                    .align(Alignment.BottomEnd),
                shape = CircleShape,
                color = Color.Green,
                border = androidx.compose.foundation.BorderStroke(
                    2.dp, 
                    MaterialTheme.colorScheme.surface
                )
            ) {}
        }
    }
}

@Composable
internal fun EncryptionIndicator(status: MeshEncryptionManager.EncryptionStatus) {
    val (icon, color) = when (status) {
        MeshEncryptionManager.EncryptionStatus.ENCRYPTED -> Icons.Default.Lock to Color.Green
        MeshEncryptionManager.EncryptionStatus.KEY_EXCHANGE_IN_PROGRESS -> Icons.Default.Sync to Color(0xFFFF9800) // Orange
        MeshEncryptionManager.EncryptionStatus.PUBLIC_KEY_AVAILABLE -> Icons.Default.Key to Color.Blue
        MeshEncryptionManager.EncryptionStatus.NO_ENCRYPTION -> Icons.Default.LockOpen to Color.Red
    }
    
    Icon(
        imageVector = icon,
        contentDescription = "Encryption Status",
        tint = color,
        modifier = Modifier.size(16.dp)
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun EmptyStateScreen(
    onDiscoverUsers: () -> Unit,
    onShowHelp: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.People,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Welcome to MeshChat",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Connect with people nearby without internet or cellular coverage",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onDiscoverUsers,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Discover Users Nearby")
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedButton(
            onClick = onShowHelp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Help,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("How it Works")
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "now"
        diff < 3600_000 -> "${diff / 60_000}m"
        diff < 86400_000 -> "${diff / 3600_000}h"
        diff < 604800_000 -> "${diff / 86400_000}d"
        else -> SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Help,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("How Mesh Messaging Works")
            }
        },
        text = {
            LazyColumn {
                item {
                    Text(
                        text = "🌐 Bluetooth Mesh Network",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This app creates a decentralized communication network using Bluetooth Low Energy. Messages can hop between devices to reach distant users.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "📱 Getting Started",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "1. Enable Bluetooth and Location permissions\n" +
                                "2. Tap 'Discover Users Nearby' to find other devices\n" +
                                "3. Wait for other users to appear in the list\n" +
                                "4. Tap on a user to start chatting",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "🔐 Security",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "All messages are encrypted end-to-end. Each device generates unique keys that are never shared in plain text.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "🆘 Emergency Features",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The SOS button broadcasts emergency messages to all nearby devices, even those you haven't connected to directly.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "⚡ Troubleshooting",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Make sure Bluetooth is enabled\n" +
                                "• Grant location permissions for device discovery\n" +
                                "• Stay within 10-30 meters of other devices\n" +
                                "• Try restarting discovery if no users appear\n" +
                                "• Check the network status indicator",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it!")
            }
        }
    )
}