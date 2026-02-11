package app.revive.tosave

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.revive.tosave.ui.theme.ReviveTheme
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

@Composable
fun getScreenBasedPadding(): PaddingValues {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    
    return when {
        screenWidth < 360.dp -> PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        screenWidth < 480.dp -> PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        else -> PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    }
}

@Composable
fun getScreenBasedSpacing(): Dp {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    
    return when {
        screenWidth < 360.dp -> 8.dp
        screenWidth < 480.dp -> 12.dp
        else -> 16.dp
    }
}

class MainActivity : ComponentActivity() {
    private val serviceId = "com.revive.meshchat"
    private val strategy = Strategy.P2P_CLUSTER
    private val connectionsClient by lazy { Nearby.getConnectionsClient(this) }
    
    // Connected endpoints management
    private val connectedEndpoints = mutableStateMapOf<String, String>() // endpointId -> endpointName
    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var isAdvertising by mutableStateOf(false)
    private var isDiscovering by mutableStateOf(false)
    private var currentMessage by mutableStateOf("")
    
    data class ChatMessage(
        val sender: String,
        val message: String,
        val isRelay: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val requiredPermissions = mutableListOf<String>().apply {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_WIFI_STATE)
        add(Manifest.permission.CHANGE_WIFI_STATE)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.POST_NOTIFICATIONS)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d("MeshChat", "All permissions granted")
        } else {
            Log.w("MeshChat", "Some permissions denied")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            ReviveTheme {
                MainAppScreen()
            }
        }
        
        checkAndRequestPermissions()
        
        // Add initial system message
        addSystemMessage("MeshChat ready - Start Hub or Join Hub to begin")
        
        // Start SOS service for emergency detection
        startSOSService()
    }
    
    private fun startSOSService() {
        try {
            val serviceIntent = android.content.Intent(this, SOSService::class.java)
            startForegroundService(serviceIntent)
            Log.d("MainActivity", "SOS service started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting SOS service", e)
        }
    }
    
    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
    
    @Composable
    fun MainAppScreen() {
        var selectedTab by remember { mutableStateOf(0) }
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                // Enhanced Tab row with icons and modern design
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary,
                            height = 3.dp
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.padding(vertical = 12.dp),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothConnected,
                                contentDescription = "Nearby Chat",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Chat",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.padding(vertical = 12.dp),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Emergency,
                                contentDescription = "Emergency SOS",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SOS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        modifier = Modifier.padding(vertical = 12.dp),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = "Mesh Network",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Mesh",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                
                // Content based on selected tab with proper spacing
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 12.dp)
                ) {
                    when (selectedTab) {
                        0 -> ChatScreen()
                        1 -> EmergencySOSScreen()
                        2 -> MeshChatScreen()
                    }
                }
            }
        }
    }
    
    @Composable
    fun ChatScreen() {
        val context = LocalContext.current
        val chatViewModel = remember { ChatViewModel() }
        val nearbyManager = remember { NearbyManager(context, chatViewModel) }
        var currentNearbyMessage by remember { mutableStateOf("") }
        var isNearbyAdvertising by remember { mutableStateOf(false) }
        var isNearbyDiscovering by remember { mutableStateOf(false) }
        var nearbyConnectedDevices by remember { mutableStateOf(setOf<String>()) }
        var connectionStatus by remember { mutableStateOf("Disconnected") }
        
        // Collect messages from the ChatViewModel
        val messages by chatViewModel.messages.collectAsState()
        val messageText by chatViewModel.messageText.collectAsState()
        
        val responsivePadding = getScreenBasedPadding()
        val responsiveSpacing = getScreenBasedSpacing()
        
        // Update connection status periodically
        LaunchedEffect(Unit) {
            while (true) {
                connectionStatus = nearbyManager.getConnectionStatus()
                nearbyConnectedDevices = if (nearbyManager.isConnected()) {
                    setOf("Device")
                } else {
                    emptySet()
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
            ) {
                // Clean Status Header
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothConnected,
                            contentDescription = null,
                            tint = if (nearbyConnectedDevices.isNotEmpty()) 
                                MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Device Connection",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = connectionStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Connection indicator
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = if (nearbyConnectedDevices.isNotEmpty()) 
                                        Color(0xFF4CAF50) 
                                    else Color(0xFF9E9E9E),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                }
                
                // Clean Control Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ElevatedButton(
                        onClick = { 
                            isNearbyAdvertising = true
                            nearbyManager.startAdvertising()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isNearbyAdvertising,
                        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isNearbyAdvertising) "Advertise" else "Advertise",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    
                    ElevatedButton(
                        onClick = { 
                            isNearbyDiscovering = true
                            nearbyManager.startDiscovery()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isNearbyDiscovering,
                        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isNearbyDiscovering) "Discovering..." else "Discover",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                
                // Disconnect button (only show when connected)
                if (nearbyConnectedDevices.isNotEmpty() || isNearbyAdvertising || isNearbyDiscovering) {
                    OutlinedButton(
                        onClick = { 
                            nearbyManager.disconnect()
                            isNearbyAdvertising = false
                            isNearbyDiscovering = false
                            nearbyConnectedDevices = emptySet()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Disconnect")
                    }
                }
                
                // Clean Messages Display
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Messages header
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Messages",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        // Messages list
                        if (messages.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Chat,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No messages yet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Start chatting with nearby devices",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                reverseLayout = true
                            ) {
                                items(messages.reversed()) { message ->
                                    MessageBubble(message = message)
                                }
                            }
                        }
                    }
                }
                
                // Clean Message Input
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = currentNearbyMessage,
                            onValueChange = { 
                                currentNearbyMessage = it
                                chatViewModel.updateMessageText(it)
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { 
                                Text(
                                    text = if (nearbyConnectedDevices.isNotEmpty()) 
                                        "Type your message..." 
                                    else "Connect to start chatting",
                                    style = MaterialTheme.typography.bodyMedium
                                ) 
                            },
                            enabled = nearbyConnectedDevices.isNotEmpty(),
                            shape = MaterialTheme.shapes.large,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { 
                                    if (currentNearbyMessage.isNotBlank()) {
                                        chatViewModel.sendMessage(nearbyManager)
                                        currentNearbyMessage = ""
                                    }
                                }
                            ),
                            singleLine = false,
                            maxLines = 3
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        FilledIconButton(
                            onClick = { 
                                if (currentNearbyMessage.isNotBlank()) {
                                    chatViewModel.sendMessage(nearbyManager)
                                    currentNearbyMessage = ""
                                }
                            },
                            enabled = currentNearbyMessage.isNotBlank() && nearbyConnectedDevices.isNotEmpty(),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Send, 
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    fun MessageBubble(message: String) {
        val isFromUser = message.startsWith("You:")
        val isSystemMessage = message.startsWith("📡") || message.startsWith("🔍") || message.startsWith("🔌") || message.startsWith("✅") || message.startsWith("❌")
        
        // Only show system messages if they're connection confirmations
        val shouldShowMessage = !isSystemMessage || message.contains("Connected") || message.contains("disconnected")
        
        if (!shouldShowMessage) return
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start
        ) {
            if (!isFromUser && !isSystemMessage) Spacer(modifier = Modifier.width(8.dp))
            
            Card(
                modifier = Modifier.widthIn(max = 280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isSystemMessage -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        isFromUser -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = if (isSystemMessage) message else message,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isSystemMessage -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        isFromUser -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    }
                )
            }
            
            if (isFromUser) Spacer(modifier = Modifier.width(8.dp))
        }
    }
    
    @Composable
    fun MeshChatScreen() {
        val listState = rememberLazyListState()
        val responsivePadding = getScreenBasedPadding()
        val responsiveSpacing = getScreenBasedSpacing()
        var showClearDialog by remember { mutableStateOf(false) }
        
        // Auto-scroll to bottom when new messages arrive
        LaunchedEffect(chatMessages.size) {
            if (chatMessages.isNotEmpty()) {
                listState.animateScrollToItem(chatMessages.size - 1)
            }
        }
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
            ) {
                // Simple connection status like Chat tab
                if (connectedEndpoints.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = "✅ Connected to ${connectedEndpoints.size} device(s)",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            
                // Simple Control buttons in single row with equal distribution
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { startAdvertising() },
                        modifier = Modifier.weight(1f),
                        enabled = !isAdvertising
                    ) {
                        Text(if (isAdvertising) "Active" else "Hub")
                    }
                    
                    Button(
                        onClick = { startDiscovery() },
                        modifier = Modifier.weight(1f),
                        enabled = !isDiscovering
                    ) {
                        Text(if (isDiscovering) "Scanning..." else "Scan")
                    }
                    
                    OutlinedButton(
                        onClick = { disconnect() },
                        modifier = Modifier.weight(1f),
                        enabled = connectedEndpoints.isNotEmpty() || isAdvertising || isDiscovering,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                }
            
                // Simple Messages Display like Chat tab
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Simple messages header with clear icon
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Messages",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                if (chatMessages.isNotEmpty()) {
                                    IconButton(
                                        onClick = { showClearDialog = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear all messages",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Messages list
                        if (chatMessages.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Chat,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "No messages yet",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Start chatting with nearby devices",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(chatMessages) { message ->
                                    ChatMessageItem(message)
                                }
                            }
                        }
                    }
                }
            
                // Simple Message Input like Chat tab
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        OutlinedTextField(
                            value = currentMessage,
                            onValueChange = { currentMessage = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { 
                                Text(
                                    text = if (connectedEndpoints.isNotEmpty()) 
                                        "Type your message..." 
                                    else "Connect to start chatting",
                                    style = MaterialTheme.typography.bodyMedium
                                ) 
                            },
                            enabled = connectedEndpoints.isNotEmpty(),
                            shape = MaterialTheme.shapes.large,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { 
                                    if (currentMessage.isNotBlank()) {
                                        sendMessage()
                                    }
                                }
                            ),
                            singleLine = false,
                            maxLines = 3
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        FilledIconButton(
                            onClick = { 
                                if (currentMessage.isNotBlank()) {
                                    sendMessage()
                                }
                            },
                            enabled = currentMessage.isNotBlank() && connectedEndpoints.isNotEmpty(),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Send, 
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
        
        // Confirmation dialog for clearing messages
        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = {
                    Text(
                        text = "Clear Messages",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                text = {
                    Text(
                        text = "Clearing entire messages",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            chatMessages.clear()
                            showClearDialog = false
                        }
                    ) {
                        Text("OK")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showClearDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
    
    @Composable
    fun ChatMessageItem(message: ChatMessage) {
        val isSystemMessage = message.sender == "System"
        val isFromUser = message.sender == "You"
        
        // Simple styling like regular MessageBubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start
        ) {
            if (!isFromUser && !isSystemMessage) Spacer(modifier = Modifier.width(8.dp))
            
            Card(
                modifier = Modifier.widthIn(max = 280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isSystemMessage -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        isFromUser -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = if (isSystemMessage) message.message else "${message.sender}: ${message.message}",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isFromUser -> MaterialTheme.colorScheme.onPrimary
                        isSystemMessage -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }
            
            if (isFromUser) Spacer(modifier = Modifier.width(8.dp))
        }
    }
    
    // Nearby Connections implementation
    private fun startAdvertising() {
        Log.d("MeshChat", "Starting advertising...")
        
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(strategy)
            .build()
            
        connectionsClient.startAdvertising(
            Build.MODEL ?: "Unknown Device",
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        )
        .addOnSuccessListener {
            isAdvertising = true
            Log.d("MeshChat", "Advertising started successfully")
            addSystemMessage("Started advertising as hub")
        }
        .addOnFailureListener { exception ->
            Log.e("MeshChat", "Failed to start advertising", exception)
            addSystemMessage("Failed to start advertising: ${exception.message}")
        }
    }
    
    private fun startDiscovery() {
        Log.d("MeshChat", "Starting discovery...")
        
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(strategy)
            .build()
            
        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        )
        .addOnSuccessListener {
            isDiscovering = true
            Log.d("MeshChat", "Discovery started successfully")
            addSystemMessage("Started searching for hubs")
        }
        .addOnFailureListener { exception ->
            Log.e("MeshChat", "Failed to start discovery", exception)
            addSystemMessage("Failed to start discovery: ${exception.message}")
        }
    }
    
    private fun disconnect() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        
        connectedEndpoints.clear()
        isAdvertising = false
        isDiscovering = false
        
        Log.d("MeshChat", "Disconnected from all endpoints")
        addSystemMessage("Disconnected from all devices")
    }
    
    private fun sendMessage() {
        if (currentMessage.isBlank() || connectedEndpoints.isEmpty()) return
        
        val message = currentMessage.trim()
        currentMessage = ""
        
        // Add to local chat log
        chatMessages.add(ChatMessage(sender = "You", message = message))
        
        // Send to all connected endpoints
        val payload = Payload.fromBytes(message.toByteArray())
        connectedEndpoints.keys.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, payload)
        }
        
        Log.d("MeshChat", "Sent message to ${connectedEndpoints.size} endpoints: $message")
    }
    
    private fun relayMessage(senderId: String, message: String) {
        // Get sender name from connected endpoints
        val senderName = connectedEndpoints[senderId] ?: senderId
        
        // Relay to all other endpoints (skip sender)
        val payload = Payload.fromBytes(message.toByteArray())
        connectedEndpoints.keys.forEach { endpointId ->
            if (endpointId != senderId) {
                connectionsClient.sendPayload(endpointId, payload)
                Log.d("MeshChat", "Relayed message from $senderName to ${connectedEndpoints[endpointId]}")
            }
        }
        
        // Add relay message to chat log
        if (connectedEndpoints.size > 1) {
            chatMessages.add(ChatMessage(
                sender = "$senderName → Others",
                message = message,
                isRelay = true
            ))
        }
    }
    
    private fun addSystemMessage(message: String) {
        chatMessages.add(ChatMessage(sender = "System", message = message))
    }
    
    // Connection lifecycle callback
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d("MeshChat", "Connection initiated with ${connectionInfo.endpointName}")
            addSystemMessage("Connecting to ${connectionInfo.endpointName}...")
            
            // Auto-accept connections for mesh behavior
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }
        
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d("MeshChat", "Connected to endpoint: $endpointId")
                    // Store endpoint info - will get name from first message or use ID
                    connectedEndpoints[endpointId] = "Device_${endpointId.take(8)}"
                    addSystemMessage("Connected to ${connectedEndpoints[endpointId]}")
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d("MeshChat", "Connection rejected by $endpointId")
                    addSystemMessage("Connection rejected by device")
                }
                else -> {
                    Log.d("MeshChat", "Connection failed with $endpointId")
                    addSystemMessage("Connection failed")
                }
            }
        }
        
        override fun onDisconnected(endpointId: String) {
            val deviceName = connectedEndpoints[endpointId] ?: "Unknown Device"
            connectedEndpoints.remove(endpointId)
            Log.d("MeshChat", "Disconnected from $endpointId")
            addSystemMessage("$deviceName disconnected")
        }
    }
    
    // Endpoint discovery callback
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d("MeshChat", "Discovered endpoint: ${info.endpointName}")
            addSystemMessage("Found hub: ${info.endpointName}")
            
            // Auto-connect to discovered endpoints for mesh behavior
            connectionsClient.requestConnection(
                Build.MODEL ?: "Unknown Device",
                endpointId,
                connectionLifecycleCallback
            )
        }
        
        override fun onEndpointLost(endpointId: String) {
            Log.d("MeshChat", "Lost endpoint: $endpointId")
            addSystemMessage("Lost connection to hub")
        }
    }
    
    // Payload callback for receiving messages
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            try {
                val bytes = payload.asBytes()
                if (bytes != null) {
                    val message = String(bytes)
                    val senderName = connectedEndpoints[endpointId] ?: "Unknown"
                    
                    Log.d("MeshChat", "Received message from $senderName: $message")
                    
                    // Add received message to chat log
                    chatMessages.add(ChatMessage(sender = senderName, message = message))
                    
                    // Relay message to other connected endpoints
                    relayMessage(endpointId, message)
                } else {
                    Log.w("MeshChat", "Received null payload bytes from $endpointId")
                }
            } catch (e: Exception) {
                Log.e("MeshChat", "Error processing received message", e)
                addSystemMessage("Error receiving message: ${e.message}")
            }
        }
        
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Handle payload transfer updates if needed
        }
    }
    
    @Composable
    fun EmergencySOSScreen() {
        val context = LocalContext.current
        val sosManager = remember { SOSManager(context) }
        val emergencyContacts = remember { mutableStateOf(sosManager.getEmergencyContacts()) }
        var showLearnMore by remember { mutableStateOf(false) }
        
        val responsivePadding = getScreenBasedPadding()
        val responsiveSpacing = getScreenBasedSpacing()
        
        // Refresh contacts when screen is displayed
        LaunchedEffect(Unit) {
            emergencyContacts.value = sosManager.getEmergencyContacts()
        }
        
        if (showLearnMore) {
            LearnMoreScreen(onBack = { showLearnMore = false })
            return
        }
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
            ) {
                // Emergency Contacts Card (moved to top)
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Phone, 
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Emergency Contacts",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            
                            FilledTonalButton(
                                onClick = { 
                                    val intent = android.content.Intent(context, EmergencyContactsActivity::class.java)
                                    context.startActivity(intent)
                                }
                            ) {
                                Text("Manage")
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (emergencyContacts.value.isEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "No emergency contacts configured",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(
                                            Color(0xFF4CAF50).copy(alpha = 0.2f),
                                            androidx.compose.foundation.shape.CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${emergencyContacts.value.size}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "${emergencyContacts.value.size} contact(s) configured",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (emergencyContacts.value.isNotEmpty()) {
                                        Text(
                                            text = emergencyContacts.value.take(2).joinToString(", ") + 
                                                if (emergencyContacts.value.size > 2) "..." else "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Learn More Component
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Learn More",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = "Get detailed information about how to use the emergency features",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = { showLearnMore = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Instructions")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Emergency Action Button
                val isSOSReady = sosManager.hasRequiredPermissions() && emergencyContacts.value.isNotEmpty()
                
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (isSOSReady) Color(0xFFFF5722) else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (isSOSReady) {
                                    // Trigger full emergency functionality
                                    val sosAdvertiser = SOSAdvertiser.getInstance(context)
                                    sosAdvertiser.startAdvertising()
                                    sosManager.triggerEmergency()
                                    
                                    // Start emergency chat advertising
                                    val broadcastIntent = android.content.Intent("app.revive.tosave.START_EMERGENCY_CHAT")
                                    context.sendBroadcast(broadcastIntent)
                                    
                                    addSystemMessage("🚨 Emergency SOS activated! Alerts sent to contacts and nearby devices.")
                                }
                            },
                            enabled = isSOSReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSOSReady) Color(0xFFFF5722) else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSOSReady) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                Icons.Default.Warning, 
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isSOSReady) "🚨 EMERGENCY SOS" else "Setup Required",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        if (!isSOSReady) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (!sosManager.hasRequiredPermissions()) {
                                    "Grant location and SMS permissions to enable Emergency SOS"
                                } else {
                                    "Add emergency contacts to enable Emergency SOS"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
    
    @Composable
    fun TriggerOption(
        icon: String,
        title: String,
        description: String
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    
    @Composable
    fun LearnMoreScreen(onBack: () -> Unit) {
        val responsivePadding = getScreenBasedPadding()
        val responsiveSpacing = getScreenBasedSpacing()
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
            ) {
                // Header
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "How to Use Emergency SOS",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        InstructionCard(
                            title = "🚨 Emergency Activation",
                            description = "There are multiple ways to trigger emergency SOS:",
                            details = listOf(
                                "• Press power button 3 times quickly",
                                "• Press volume up/down buttons 5 times quickly", 
                                "• Use the Emergency Button in the SOS tab",
                                "• Voice command: 'Emergency SOS'"
                            )
                        )
                    }
                    
                    item {
                        InstructionCard(
                            title = "📱 What Happens When Activated",
                            description = "Emergency SOS will automatically:",
                            details = listOf(
                                "• Send SMS alerts to all emergency contacts",
                                "• Share your current location via GPS",
                                "• Start advertising emergency signal to nearby devices",
                                "• Begin mesh communication for rescue coordination",
                                "• Continue broadcasting until manually stopped"
                            )
                        )
                    }
                    
                    item {
                        InstructionCard(
                            title = "👥 Emergency Contacts",
                            description = "Set up your emergency contacts for faster response:",
                            details = listOf(
                                "• Add at least 2-3 trusted contacts",
                                "• Include family members and close friends",
                                "• Verify all phone numbers are correct",
                                "• Inform contacts about this emergency system",
                                "• Test the system in non-emergency situations"
                            )
                        )
                    }
                    
                    item {
                        InstructionCard(
                            title = "🔗 Mesh Communication",
                            description = "Connect with nearby devices during emergencies:",
                            details = listOf(
                                "• Your device becomes an emergency hub",
                                "• Other disaster communication apps can connect",
                                "• Share location and status with nearby survivors",
                                "• Relay messages through the mesh network",
                                "• Works without internet or cellular service"
                            )
                        )
                    }
                    
                    item {
                        InstructionCard(
                            title = "⚠️ Important Notes",
                            description = "Keep these guidelines in mind:",
                            details = listOf(
                                "• Only use in real emergencies",
                                "• Keep your device charged when possible",
                                "• Enable location services for accurate positioning",
                                "• Grant all required permissions for full functionality",
                                "• Test the system regularly to ensure it works"
                            )
                        )
                    }
                }
                
                // Got It button
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = "Got It",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
    
    @Composable
    fun InstructionCard(
        title: String,
        description: String,
        details: List<String>
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                details.forEach { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
    
    @Composable
    fun EmergencyBroadcastScreen() {
        val context = LocalContext.current
        val sosAdvertiser = remember { SOSAdvertiser.getInstance(context) }
        var isEmergencyAdvertising by remember { mutableStateOf(sosAdvertiser.isCurrentlyAdvertising()) }
        
        // Update advertising status periodically
        LaunchedEffect(Unit) {
            while (true) {
                isEmergencyAdvertising = sosAdvertiser.isCurrentlyAdvertising()
                kotlinx.coroutines.delay(1000)
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Broadcast Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isEmergencyAdvertising) {
                        Color(0xFF4CAF50).copy(alpha = 0.1f)
                    } else {
                        Color(0xFF2196F3).copy(alpha = 0.1f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isEmergencyAdvertising) Color(0xFF4CAF50) else Color(0xFF2196F3)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEmergencyAdvertising) {
                                "🟢 Emergency Broadcasting ACTIVE"
                            } else {
                                "Emergency Broadcasting"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = if (isEmergencyAdvertising) {
                            "Your device is broadcasting emergency signals via Bluetooth and WiFi. Other devices in range can detect your distress signal."
                        } else {
                            "Broadcast emergency signals to help rescuers locate you or discover other survivors in range using Bluetooth LE and WiFi Direct."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            
            // Broadcasting Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        sosAdvertiser.startAdvertising()
                        addSystemMessage("Emergency broadcasting started - sending distress signals")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isEmergencyAdvertising,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text(if (isEmergencyAdvertising) "Broadcasting..." else "Start Broadcasting")
                }
                
                Button(
                    onClick = {
                        sosAdvertiser.stopAdvertising()
                        addSystemMessage("Emergency broadcasting stopped")
                    },
                    modifier = Modifier.weight(1f),
                    enabled = isEmergencyAdvertising,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
                ) {
                    Text("Stop Broadcasting")
                }
            }
            
            // Discovery Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        // Start emergency discovery using nearby connections
                        startDiscovery()
                        addSystemMessage("Scanning for emergency signals and survivors")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) {
                    Text("Scan for Survivors")
                }
                
                Button(
                    onClick = {
                        // Start advertising for survivors to find us
                        startAdvertising()
                        addSystemMessage("Advertising as emergency hub for survivors")
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
                ) {
                    Text("Become Emergency Hub")
                }
            }
            
            // Debug Information
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF607D8B).copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Debug Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = {
                            val debugInfo = sosAdvertiser.getDebugInfo()
                            addSystemMessage("Debug: $debugInfo")
                            Log.d("MainActivity", debugInfo)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B))
                    ) {
                        Text("Show Debug Info")
                    }
                }
            }
            
            // Discovered Devices/Status
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Emergency Network Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Connected devices: ${connectedEndpoints.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    if (connectedEndpoints.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        connectedEndpoints.forEach { (_, name) ->
                            Text(
                                text = "• $name",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    } else {
                        Text(
                            text = "No devices detected.\nUse 'Scan for Survivors' or 'Become Emergency Hub' to connect with other devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}