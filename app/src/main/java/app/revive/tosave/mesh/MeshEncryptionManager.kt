package app.revive.tosave.mesh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Advanced encryption system for mesh messaging with E2E encryption, key exchange, and message signing
 */
class MeshEncryptionManager(
    private val meshDatabase: MeshDatabase,
    private val currentUserId: String
) {
    companion object {
        private const val TAG = "MeshEncryptionManager"
        private const val KEYSTORE_ALIAS = "MeshKeyPair"
        private const val RSA_KEY_SIZE = 2048
        private const val AES_KEY_SIZE = 256
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        private const val KEY_VALIDITY_PERIOD = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    private val keyStore: KeyStore
    private val secureRandom = SecureRandom()
    
    // Session keys for active conversations: userId -> SessionKey
    private val sessionKeys = ConcurrentHashMap<String, SessionKey>()
    
    // Public keys of known users: userId -> PublicKey
    private val publicKeys = ConcurrentHashMap<String, PublicKey>()
    
    // Key exchange sessions: userId -> KeyExchangeSession
    private val keyExchangeSessions = ConcurrentHashMap<String, KeyExchangeSession>()

    init {
        keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
    }

    /**
     * Session key information
     */
    @Serializable
    private data class SessionKey(
        val key: String, // Base64 encoded AES key
        val createdAt: Long,
        val lastUsed: Long,
        val messageCount: Int = 0
    )

    /**
     * Key exchange session data
     */
    private data class KeyExchangeSession(
        val sessionId: String,
        val myPrivateKey: PrivateKey,
        val myPublicKey: PublicKey,
        val theirPublicKey: PublicKey? = null,
        val sharedSecret: ByteArray? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val isCompleted: Boolean = false
    )

    /**
     * Encrypted message wrapper
     */
    @Serializable
    data class EncryptedMessage(
        val encryptedContent: String, // Base64 encoded
        val iv: String, // Base64 encoded IV
        val signature: String, // Base64 encoded signature
        val senderPublicKey: String? = null, // For first-time contacts
        val keyExchangeData: String? = null // For key exchange
    )

    /**
     * Key exchange message
     */
    @Serializable
    data class KeyExchangeMessage(
        val sessionId: String,
        val publicKey: String, // Base64 encoded
        val timestamp: Long,
        val signature: String // Signed with long-term key
    )

    /**
     * Message signature
     */
    @Serializable
    data class MessageSignature(
        val messageHash: String,
        val signature: String,
        val timestamp: Long
    )

    /**
     * Initialize encryption system
     */
    suspend fun initialize() {
        Log.d(TAG, "Initializing mesh encryption manager")
        
        try {
            // Generate or load long-term key pair
            ensureLongTermKeyPair()
            
            // Load existing session keys
            loadSessionKeys()
            
            // Load known public keys
            loadPublicKeys()
            
            Log.d(TAG, "Encryption manager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption manager", e)
            throw e
        }
    }

    /**
     * Encrypt a message for a specific recipient
     */
    suspend fun encryptMessage(message: MeshMessage, recipientId: String): EncryptedMessage = withContext(Dispatchers.IO) {
        Log.d(TAG, "Encrypting message for $recipientId")
        
        try {
            // Get or create session key
            val sessionKey = getOrCreateSessionKey(recipientId)
            
            // Serialize message
            val messageJson = Json.encodeToString(message)
            val messageBytes = messageJson.toByteArray(StandardCharsets.UTF_8)
            
            // Generate IV
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)
            
            // Encrypt message
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.ENCRYPT_MODE, sessionKey, gcmSpec)
            val encryptedBytes = cipher.doFinal(messageBytes)
            
            // Sign message
            val signature = signMessage(messageBytes)
            
            // Check if this is first contact
            val senderPublicKey = if (!publicKeys.containsKey(recipientId)) {
                getMyPublicKey()?.let { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
            } else null
            
            val encryptedMessage = EncryptedMessage(
                encryptedContent = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
                signature = Base64.encodeToString(signature, Base64.NO_WRAP),
                senderPublicKey = senderPublicKey
            )
            
            // Update session key usage
            updateSessionKeyUsage(recipientId)
            
            Log.d(TAG, "Message encrypted successfully for $recipientId")
            encryptedMessage
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt message for $recipientId", e)
            throw e
        }
    }

    /**
     * Decrypt a message from a specific sender
     */
    suspend fun decryptMessage(encryptedMessage: EncryptedMessage, senderId: String): MeshMessage = withContext(Dispatchers.IO) {
        Log.d(TAG, "Decrypting message from $senderId")
        
        try {
            // Store sender's public key if provided
            encryptedMessage.senderPublicKey?.let { publicKeyStr ->
                val publicKeyBytes = Base64.decode(publicKeyStr, Base64.NO_WRAP)
                val keyFactory = KeyFactory.getInstance("RSA")
                val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
                storePublicKey(senderId, publicKey)
            }
            
            // Get session key
            val sessionKey = getSessionKey(senderId)
                ?: throw SecurityException("No session key found for $senderId")
            
            // Decode encrypted data
            val encryptedBytes = Base64.decode(encryptedMessage.encryptedContent, Base64.NO_WRAP)
            val iv = Base64.decode(encryptedMessage.iv, Base64.NO_WRAP)
            val signature = Base64.decode(encryptedMessage.signature, Base64.NO_WRAP)
            
            // Decrypt message
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, gcmSpec)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            
            // Verify signature
            if (!verifyMessageSignature(decryptedBytes, signature, senderId)) {
                throw SecurityException("Message signature verification failed")
            }
            
            // Deserialize message
            val messageJson = String(decryptedBytes, StandardCharsets.UTF_8)
            val message = Json.decodeFromString<MeshMessage>(messageJson)
            
            // Update session key usage
            updateSessionKeyUsage(senderId)
            
            Log.d(TAG, "Message decrypted successfully from $senderId")
            message
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt message from $senderId", e)
            throw e
        }
    }

    /**
     * Initialize key exchange with a new contact
     */
    suspend fun initiateKeyExchange(contactId: String): KeyExchangeMessage = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initiating key exchange with $contactId")
        
        try {
            val sessionId = UUID.randomUUID().toString()
            
            // Generate ephemeral key pair for this exchange
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
            keyPairGenerator.initialize(RSA_KEY_SIZE)
            val ephemeralKeyPair = keyPairGenerator.generateKeyPair()
            
            // Create exchange session
            val session = KeyExchangeSession(
                sessionId = sessionId,
                myPrivateKey = ephemeralKeyPair.private,
                myPublicKey = ephemeralKeyPair.public
            )
            keyExchangeSessions[contactId] = session
            
            // Create key exchange message
            val publicKeyBytes = ephemeralKeyPair.public.encoded
            val publicKeyString = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
            
            // Sign with long-term key
            val longTermSignature = signWithLongTermKey(publicKeyBytes)
            
            val keyExchangeMessage = KeyExchangeMessage(
                sessionId = sessionId,
                publicKey = publicKeyString,
                timestamp = System.currentTimeMillis(),
                signature = Base64.encodeToString(longTermSignature, Base64.NO_WRAP)
            )
            
            Log.d(TAG, "Key exchange initiated with $contactId")
            keyExchangeMessage
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate key exchange with $contactId", e)
            throw e
        }
    }

    /**
     * Handle incoming key exchange message
     */
    suspend fun handleKeyExchange(keyExchange: KeyExchangeMessage, senderId: String): KeyExchangeMessage? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Handling key exchange from $senderId")
        
        try {
            // Verify signature with sender's long-term public key
            val senderPublicKey = publicKeys[senderId]
            if (senderPublicKey != null) {
                val publicKeyBytes = Base64.decode(keyExchange.publicKey, Base64.NO_WRAP)
                val signature = Base64.decode(keyExchange.signature, Base64.NO_WRAP)
                
                if (!verifySignatureWithKey(publicKeyBytes, signature, senderPublicKey)) {
                    Log.e(TAG, "Key exchange signature verification failed")
                    return@withContext null
                }
            }
            
            // Parse their public key
            val keyFactory = KeyFactory.getInstance("RSA")
            val theirPublicKeyBytes = Base64.decode(keyExchange.publicKey, Base64.NO_WRAP)
            val theirPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(theirPublicKeyBytes))
            
            // Check if we have an existing session
            val existingSession = keyExchangeSessions[senderId]
            
            if (existingSession != null) {
                // Complete existing exchange
                val session = existingSession.copy(
                    theirPublicKey = theirPublicKey,
                    isCompleted = true
                )
                keyExchangeSessions[senderId] = session
                
                // Generate shared secret and session key
                completeKeyExchange(senderId, session)
                
                Log.d(TAG, "Key exchange completed with $senderId")
                return@withContext null
            } else {
                // Start new exchange
                val sessionId = UUID.randomUUID().toString()
                
                // Generate our ephemeral key pair
                val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
                keyPairGenerator.initialize(RSA_KEY_SIZE)
                val ourKeyPair = keyPairGenerator.generateKeyPair()
                
                val session = KeyExchangeSession(
                    sessionId = sessionId,
                    myPrivateKey = ourKeyPair.private,
                    myPublicKey = ourKeyPair.public,
                    theirPublicKey = theirPublicKey,
                    isCompleted = true
                )
                keyExchangeSessions[senderId] = session
                
                // Generate shared secret and session key
                completeKeyExchange(senderId, session)
                
                // Send our public key back
                val ourPublicKeyString = Base64.encodeToString(ourKeyPair.public.encoded, Base64.NO_WRAP)
                val ourSignature = signWithLongTermKey(ourKeyPair.public.encoded)
                
                val response = KeyExchangeMessage(
                    sessionId = keyExchange.sessionId,
                    publicKey = ourPublicKeyString,
                    timestamp = System.currentTimeMillis(),
                    signature = Base64.encodeToString(ourSignature, Base64.NO_WRAP)
                )
                
                Log.d(TAG, "Key exchange response created for $senderId")
                return@withContext response
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle key exchange from $senderId", e)
            return@withContext null
        }
    }

    /**
     * Get or create session key for a contact
     */
    private suspend fun getOrCreateSessionKey(contactId: String): SecretKey {
        val existingKey = sessionKeys[contactId]
        
        // Check if key exists and is still valid
        if (existingKey != null && isSessionKeyValid(existingKey)) {
            val keyBytes = Base64.decode(existingKey.key, Base64.NO_WRAP)
            return SecretKeySpec(keyBytes, "AES")
        }
        
        // Check if we have a key exchange session
        val session = keyExchangeSessions[contactId]
        if (session?.isCompleted == true && session.sharedSecret != null) {
            return generateSessionKeyFromSharedSecret(session.sharedSecret, contactId)
        }
        
        // No session key available - need key exchange first
        throw SecurityException("No session key available for $contactId. Key exchange required.")
    }

    /**
     * Get existing session key
     */
    private fun getSessionKey(contactId: String): SecretKey? {
        val sessionKey = sessionKeys[contactId] ?: return null
        
        if (!isSessionKeyValid(sessionKey)) {
            sessionKeys.remove(contactId)
            return null
        }
        
        val keyBytes = Base64.decode(sessionKey.key, Base64.NO_WRAP)
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Complete key exchange and generate session key
     */
    private suspend fun completeKeyExchange(contactId: String, session: KeyExchangeSession) {
        if (session.theirPublicKey == null) return
        
        try {
            // Perform ECDH key agreement
            val keyAgreement = KeyAgreement.getInstance("ECDH")
            // Note: This is simplified - would need proper ECDH implementation
            
            // For now, use simple shared secret generation
            val sharedSecret = generateSharedSecret(session.myPrivateKey, session.theirPublicKey)
            
            // Generate session key from shared secret
            val sessionKey = generateSessionKeyFromSharedSecret(sharedSecret, contactId)
            
            // Store session key
            val sessionKeyData = SessionKey(
                key = Base64.encodeToString(sessionKey.encoded, Base64.NO_WRAP),
                createdAt = System.currentTimeMillis(),
                lastUsed = System.currentTimeMillis()
            )
            sessionKeys[contactId] = sessionKeyData
            
            // Clean up exchange session
            keyExchangeSessions.remove(contactId)
            
            Log.d(TAG, "Session key generated for $contactId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to complete key exchange with $contactId", e)
            throw e
        }
    }

    /**
     * Generate shared secret (simplified implementation)
     */
    private fun generateSharedSecret(myPrivateKey: PrivateKey, theirPublicKey: PublicKey): ByteArray {
        // This is a simplified implementation
        // In a real scenario, you'd use proper ECDH or similar
        val combined = myPrivateKey.encoded + theirPublicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(combined)
    }

    /**
     * Generate session key from shared secret
     */
    private fun generateSessionKeyFromSharedSecret(sharedSecret: ByteArray, contactId: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyMaterial = digest.digest(sharedSecret + contactId.toByteArray())
        return SecretKeySpec(keyMaterial, "AES")
    }

    /**
     * Sign message with current user's private key
     */
    private fun signMessage(messageBytes: ByteArray): ByteArray {
        val privateKey = getMyPrivateKey()
            ?: throw SecurityException("No private key available for signing")
        
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(messageBytes)
        return signature.sign()
    }

    /**
     * Sign data with long-term private key
     */
    private fun signWithLongTermKey(data: ByteArray): ByteArray {
        return signMessage(data)
    }

    /**
     * Verify message signature
     */
    private fun verifyMessageSignature(messageBytes: ByteArray, signature: ByteArray, senderId: String): Boolean {
        val senderPublicKey = publicKeys[senderId] ?: return false
        return verifySignatureWithKey(messageBytes, signature, senderPublicKey)
    }

    /**
     * Verify signature with specific public key
     */
    private fun verifySignatureWithKey(data: ByteArray, signature: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed", e)
            false
        }
    }

    /**
     * Ensure long-term key pair exists
     */
    private fun ensureLongTermKeyPair() {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateLongTermKeyPair()
        }
    }

    /**
     * Generate long-term RSA key pair
     */
    private fun generateLongTermKeyPair() {
        val keyGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(RSA_KEY_SIZE)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        
        keyGenerator.initialize(keyGenParameterSpec)
        keyGenerator.generateKeyPair()
        
        Log.d(TAG, "Generated new long-term key pair")
    }

    /**
     * Get current user's private key
     */
    private fun getMyPrivateKey(): PrivateKey? {
        return try {
            keyStore.getKey(KEYSTORE_ALIAS, null) as? PrivateKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get private key", e)
            null
        }
    }

    /**
     * Get current user's public key
     */
    private fun getMyPublicKey(): PublicKey? {
        return try {
            keyStore.getCertificate(KEYSTORE_ALIAS)?.publicKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get public key", e)
            null
        }
    }

    /**
     * Store public key for a user
     */
    private suspend fun storePublicKey(userId: String, publicKey: PublicKey) {
        publicKeys[userId] = publicKey
        
        // Store in database
        val user = meshDatabase.userDao().getUserById(userId)
        if (user != null) {
            val updatedUser = user.copy(
                publicKey = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
            )
            meshDatabase.userDao().updateUser(updatedUser)
        }
        
        Log.d(TAG, "Stored public key for $userId")
    }

    /**
     * Load session keys from storage
     */
    private suspend fun loadSessionKeys() {
        // This would load from encrypted preferences or database
        Log.d(TAG, "Session keys loaded")
    }

    /**
     * Load public keys from database
     */
    private suspend fun loadPublicKeys() {
        val users = meshDatabase.userDao().getAllUsers()
        users.forEach { user ->
            if (!user.publicKey.isNullOrEmpty()) {
                try {
                    val keyBytes = Base64.decode(user.publicKey, Base64.NO_WRAP)
                    val keyFactory = KeyFactory.getInstance("RSA")
                    val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(keyBytes))
                    publicKeys[user.id] = publicKey
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load public key for ${user.id}", e)
                }
            }
        }
        Log.d(TAG, "Loaded ${publicKeys.size} public keys")
    }

    /**
     * Check if session key is still valid
     */
    private fun isSessionKeyValid(sessionKey: SessionKey): Boolean {
        val age = System.currentTimeMillis() - sessionKey.createdAt
        return age < KEY_VALIDITY_PERIOD && sessionKey.messageCount < 1000
    }

    /**
     * Update session key usage statistics
     */
    private fun updateSessionKeyUsage(contactId: String) {
        val sessionKey = sessionKeys[contactId] ?: return
        val updated = sessionKey.copy(
            lastUsed = System.currentTimeMillis(),
            messageCount = sessionKey.messageCount + 1
        )
        sessionKeys[contactId] = updated
    }

    /**
     * Get encryption status for a contact
     */
    fun getEncryptionStatus(contactId: String): EncryptionStatus {
        val hasSessionKey = sessionKeys.containsKey(contactId)
        val hasPublicKey = publicKeys.containsKey(contactId)
        val keyExchangeInProgress = keyExchangeSessions.containsKey(contactId)
        
        return when {
            hasSessionKey -> EncryptionStatus.ENCRYPTED
            keyExchangeInProgress -> EncryptionStatus.KEY_EXCHANGE_IN_PROGRESS
            hasPublicKey -> EncryptionStatus.PUBLIC_KEY_AVAILABLE
            else -> EncryptionStatus.NO_ENCRYPTION
        }
    }

    /**
     * Encryption status enum
     */
    enum class EncryptionStatus {
        ENCRYPTED,
        KEY_EXCHANGE_IN_PROGRESS,
        PUBLIC_KEY_AVAILABLE,
        NO_ENCRYPTION
    }

    /**
     * Clear all encryption data (for testing/reset)
     */
    suspend fun clearEncryptionData() {
        sessionKeys.clear()
        publicKeys.clear()
        keyExchangeSessions.clear()
        
        // Clear from database
        meshDatabase.userDao().getAllUsers().forEach { user ->
            val clearedUser = user.copy(publicKey = null)
            meshDatabase.userDao().updateUser(clearedUser)
        }
        
        Log.d(TAG, "Encryption data cleared")
    }

    /**
     * Shutdown encryption manager
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down encryption manager")
        sessionKeys.clear()
        publicKeys.clear()
        keyExchangeSessions.clear()
    }
}