package com.likkho.app.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.likkho.app.storage.backup.BackupEngine
import com.likkho.app.storage.migration.DiaryMigrator
import java.io.File
import javax.crypto.AEADBadTagException

sealed class UnlockResult {
    data class Success(val vaultId: VaultId, val session: VaultSession) : UnlockResult()
    data class InCooldown(val remainingSeconds: Int) : UnlockResult()
    data class WrongPin(val cooldownTriggeredSeconds: Int, val isMaxApplicableLength: Boolean) : UnlockResult()
    object NoPasscodeConfigured : UnlockResult()
    data class Error(val message: String) : UnlockResult()
}

/**
 * VaultManager orchestrates dual-vault storage, key hierarchy, unlock logic,
 * wrong-PIN protection, and memory locking per STORAGE_SPEC Sections 1, 2, and 10.
 */
class VaultManager(
    val baseDir: File,
    val keyStoreProvider: KeyStoreProvider = AndroidKeyStoreProvider(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pbkdf2Iterations: Int = CryptoUtils.DEFAULT_PBKDF2_ITERATIONS,
    val clockProvider: ClockProvider = DefaultClockProvider
) {
    private val mutexV1 = Mutex()
    private val mutexV2 = Mutex()

    val wrongPinCounter = WrongPinCounter(baseDir, keyStoreProvider, clockProvider)
    val pinLengthsRegistry = PinLengthsRegistry(baseDir)

    val envelopeV1 = VaultEnvelope(
        VaultId.PRIMARY,
        File(baseDir, VaultId.PRIMARY.dirName),
        keyStoreProvider,
        pbkdf2Iterations
    )

    val envelopeV2 = VaultEnvelope(
        VaultId.DECOY,
        File(baseDir, VaultId.DECOY.dirName),
        keyStoreProvider,
        pbkdf2Iterations
    )

    val primaryDiaryRepo = DiaryRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName))
    val decoyDiaryRepo = DiaryRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName))

    val primaryMediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName), clockProvider)
    val decoyMediaRepo = EncryptedMediaRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName), clockProvider)

    val primaryPasswordRepo = PasswordRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName))
    val decoyPasswordRepo = PasswordRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName))

    val primaryWordRepo = WordAnalysisRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName))
    val decoyWordRepo = WordAnalysisRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName))

    val backupEngine = BackupEngine(File(baseDir, "tmp"), defaultIterations = pbkdf2Iterations)
    val diaryMigrator = DiaryMigrator()

    fun getDiaryRepository(vaultId: VaultId): DiaryRepository =
        if (vaultId == VaultId.PRIMARY) primaryDiaryRepo else decoyDiaryRepo

    fun getMediaRepository(vaultId: VaultId): EncryptedMediaRepository =
        if (vaultId == VaultId.PRIMARY) primaryMediaRepo else decoyMediaRepo

    fun getPasswordRepository(vaultId: VaultId): PasswordRepository =
        if (vaultId == VaultId.PRIMARY) primaryPasswordRepo else decoyPasswordRepo

    fun getWordAnalysisRepository(vaultId: VaultId): WordAnalysisRepository =
        if (vaultId == VaultId.PRIMARY) primaryWordRepo else decoyWordRepo

    fun activeDiaryRepository(): DiaryRepository {
        val vault = currentVault ?: throw IllegalStateException("No active vault unlocked")
        return getDiaryRepository(vault)
    }

    fun activeMediaRepository(): EncryptedMediaRepository {
        val vault = currentVault ?: throw IllegalStateException("No active vault unlocked")
        return getMediaRepository(vault)
    }

    fun activePasswordRepository(): PasswordRepository {
        val vault = currentVault ?: throw IllegalStateException("No active vault unlocked")
        return getPasswordRepository(vault)
    }

    fun activeWordAnalysisRepository(): WordAnalysisRepository {
        val vault = currentVault ?: throw IllegalStateException("No active vault unlocked")
        return getWordAnalysisRepository(vault)
    }

    @Volatile
    private var activeSession: VaultSession? = null

    @Volatile
    var lastRecoveryReport: RecoveryReport? = null
        private set

    val currentVault: VaultId? get() = activeSession?.vaultId
    val isUnlocked: Boolean get() = activeSession?.isOpen == true

    private fun runUnlockMaintenance(vaultId: VaultId, session: VaultSession) {
        session.useDek { dek ->
            val diaryRepo = getDiaryRepository(vaultId)
            val mediaRepo = getMediaRepository(vaultId)
            val report = diaryRepo.scanAndRecover(dek, dekVerified = true)
            lastRecoveryReport = report
            diaryRepo.cleanupOrphanMedia(dek, mediaRepo)
        }
    }

    /**
     * Executes an operation with the active DEK in memory.
     * Throws [IllegalStateException] if locked.
     */
    fun <T> withActiveDek(block: (ByteArray) -> T): T {
        val session = activeSession ?: throw IllegalStateException("Vault is locked")
        return session.useDek(block)
    }

    /**
     * Ensures Primary vault (v1) is initialized on first app launch.
     * If not present, creates it in Keystore-only (no-passcode) mode.
     */
    suspend fun ensurePrimaryVaultInitialized(): VaultSession = withContext(dispatcher) {
        mutexV1.withLock {
            val session = if (!envelopeV1.exists()) {
                val dek = envelopeV1.createVault(pin = null)
                VaultSession(VaultId.PRIMARY, dek)
            } else if (!envelopeV1.hasPasscode()) {
                val dek = envelopeV1.unwrapNoPasscode()
                VaultSession(VaultId.PRIMARY, dek)
            } else {
                throw IllegalStateException("Primary vault requires passcode unlock")
            }
            activeSession = session
            runUnlockMaintenance(VaultId.PRIMARY, session)
            session
        }
    }

    /**
     * Attempts to unlock either Primary (v1) or Decoy (v2) vault using [pin].
     * Which vault opens depends solely on which DEK unwrap tag succeeds.
     * Never logs PIN, DEK, or vault identity.
     */
    suspend fun unlockWithPin(pin: CharArray, isExplicitSubmit: Boolean = false): UnlockResult =
        withContext(dispatcher) {
            val cooldown = wrongPinCounter.remainingCooldownSeconds()
            if (cooldown > 0) {
                return@withContext UnlockResult.InCooldown(cooldown)
            }

            // 1. Try Primary Vault (v1)
            var v1Success: ByteArray? = null
            if (envelopeV1.exists() && envelopeV1.hasPasscode()) {
                mutexV1.withLock {
                    try {
                        v1Success = envelopeV1.unwrapWithPin(pin)
                    } catch (e: AEADBadTagException) {
                        // Wrong PIN for V1
                    } catch (e: Exception) {
                        // Other error
                    }
                }
            }

            if (v1Success != null) {
                wrongPinCounter.recordSuccess()
                lock()
                val session = VaultSession(VaultId.PRIMARY, v1Success!!)
                activeSession = session
                runUnlockMaintenance(VaultId.PRIMARY, session)
                return@withContext UnlockResult.Success(VaultId.PRIMARY, session)
            }

            // 2. Try Decoy Vault (v2)
            var v2Success: ByteArray? = null
            if (envelopeV2.exists() && envelopeV2.hasPasscode()) {
                mutexV2.withLock {
                    try {
                        v2Success = envelopeV2.unwrapWithPin(pin)
                    } catch (e: AEADBadTagException) {
                        // Wrong PIN for V2
                    } catch (e: Exception) {
                        // Other error
                    }
                }
            }

            if (v2Success != null) {
                wrongPinCounter.recordSuccess()
                lock()
                val session = VaultSession(VaultId.DECOY, v2Success!!)
                activeSession = session
                runUnlockMaintenance(VaultId.DECOY, session)
                return@withContext UnlockResult.Success(VaultId.DECOY, session)
            }

            // 3. Failed: Both vaults failed
            val isMaxLen = pinLengthsRegistry.isMaxRegisteredLength(pin.size) || isExplicitSubmit
            val triggeredCooldown = if (isMaxLen) {
                wrongPinCounter.recordFailure()
            } else {
                0
            }

            UnlockResult.WrongPin(triggeredCooldown, isMaxLen)
        }

    /**
     * Unlocks primary vault if no passcode is set.
     */
    suspend fun unlockNoPasscode(vaultId: VaultId = VaultId.PRIMARY): UnlockResult =
        withContext(dispatcher) {
            val envelope = if (vaultId == VaultId.PRIMARY) envelopeV1 else envelopeV2
            val mutex = if (vaultId == VaultId.PRIMARY) mutexV1 else mutexV2

            if (!envelope.exists()) {
                return@withContext UnlockResult.Error("Vault ${vaultId.dirName} does not exist")
            }
            if (envelope.hasPasscode()) {
                return@withContext UnlockResult.NoPasscodeConfigured
            }

            mutex.withLock {
                try {
                    val dek = envelope.unwrapNoPasscode()
                    lock()
                    val session = VaultSession(vaultId, dek)
                    activeSession = session
                    runUnlockMaintenance(vaultId, session)
                    UnlockResult.Success(vaultId, session)
                } catch (e: Exception) {
                    UnlockResult.Error(e.message ?: "Failed to unwrap Keystore key")
                }
            }
        }

    /**
     * Unlocks primary vault using biometric authentication key.
     */
    suspend fun unlockWithBiometric(): UnlockResult = withContext(dispatcher) {
        val cooldown = wrongPinCounter.remainingCooldownSeconds()
        if (cooldown > 0) {
            return@withContext UnlockResult.InCooldown(cooldown)
        }

        if (!envelopeV1.exists() || !envelopeV1.hasBiometric()) {
            return@withContext UnlockResult.Error("Biometrics not enabled on vault")
        }

        mutexV1.withLock {
            try {
                val dek = envelopeV1.unwrapWithBiometric()
                wrongPinCounter.recordSuccess()
                lock()
                val session = VaultSession(VaultId.PRIMARY, dek)
                activeSession = session
                runUnlockMaintenance(VaultId.PRIMARY, session)
                UnlockResult.Success(VaultId.PRIMARY, session)
            } catch (e: Exception) {
                // Biometric key invalid or failed -> fallback to PIN without crash
                UnlockResult.Error("Biometric authentication failed: ${e.message}")
            }
        }
    }

    /**
     * Sets or updates PIN on Primary vault. Re-wraps existing DEK.
     * Does NOT re-encrypt diary files.
     */
    suspend fun setPrimaryPasscode(newPin: CharArray, oldPin: CharArray? = null) =
        withContext(dispatcher) {
            mutexV1.withLock {
                val dek = when {
                    activeSession?.vaultId == VaultId.PRIMARY -> {
                        var d: ByteArray? = null
                        activeSession!!.useDek {
                            d = ByteArray(it.size)
                            System.arraycopy(it, 0, d!!, 0, it.size)
                        }
                        d!!
                    }
                    oldPin != null && envelopeV1.hasPasscode() -> {
                        envelopeV1.unwrapWithPin(oldPin)
                    }
                    !envelopeV1.hasPasscode() -> {
                        envelopeV1.unwrapNoPasscode()
                    }
                    else -> throw IllegalStateException("Current vault DEK required to set new passcode")
                }

                try {
                    envelopeV1.rewrapDek(dek, newPin, keepBiometrics = true)
                    pinLengthsRegistry.registerLength(newPin.size)
                } finally {
                    CryptoUtils.zeroize(dek)
                }
            }
        }

    /**
     * Removes passcode on Primary vault. Re-wraps DEK with Keystore only.
     */
    suspend fun removePrimaryPasscode(oldPin: CharArray? = null) = withContext(dispatcher) {
        mutexV1.withLock {
            val dek = when {
                activeSession?.vaultId == VaultId.PRIMARY -> {
                    var d: ByteArray? = null
                    activeSession!!.useDek {
                        d = ByteArray(it.size)
                        System.arraycopy(it, 0, d!!, 0, it.size)
                    }
                    d!!
                }
                oldPin != null && envelopeV1.hasPasscode() -> {
                    envelopeV1.unwrapWithPin(oldPin)
                }
                else -> throw IllegalStateException("Active primary session or oldPin required")
            }
            try {
                envelopeV1.rewrapDek(dek, newPin = null, keepBiometrics = false)
                if (oldPin != null) {
                    pinLengthsRegistry.unregisterLength(oldPin.size)
                }
            } finally {
                CryptoUtils.zeroize(dek)
            }
        }
    }

    /**
     * Removes decoy vault and its files.
     */
    suspend fun removeDecoyPasscode() = withContext(dispatcher) {
        mutexV2.withLock {
            if (activeSession?.vaultId == VaultId.DECOY) {
                lock()
            }
            envelopeV2.shred()
        }
    }

    /**
     * Sets up or updates Decoy vault (v2).
     * Decoy PIN must be different from Primary PIN.
     * Decoy vault directory does not exist on disk until PIN is set.
     */
    suspend fun setDecoyPasscode(decoyPin: CharArray, primaryPin: CharArray) =
        withContext(dispatcher) {
            require(!decoyPin.contentEquals(primaryPin)) {
                "Decoy PIN must be different from primary PIN"
            }

            mutexV2.withLock {
                if (!envelopeV2.exists()) {
                    val dek = envelopeV2.createVault(pin = decoyPin)
                    CryptoUtils.zeroize(dek)
                } else {
                    val dek = when {
                        activeSession?.vaultId == VaultId.DECOY -> {
                            var d: ByteArray? = null
                            activeSession!!.useDek {
                                d = ByteArray(it.size)
                                System.arraycopy(it, 0, d!!, 0, it.size)
                            }
                            d!!
                        }
                        else -> {
                            throw IllegalStateException("Decoy vault must be open to change its PIN")
                        }
                    }
                    try {
                        envelopeV2.rewrapDek(dek, decoyPin, keepBiometrics = false)
                    } finally {
                        CryptoUtils.zeroize(dek)
                    }
                }
                pinLengthsRegistry.registerLength(decoyPin.size)
            }
        }

    /**
     * Enables biometric unlock for Primary vault.
     */
    suspend fun enableBiometric(currentPin: CharArray) = withContext(dispatcher) {
        mutexV1.withLock {
            val dek = envelopeV1.unwrapWithPin(currentPin)
            try {
                envelopeV1.enableBiometric(dek, currentPin)
            } finally {
                CryptoUtils.zeroize(dek)
            }
        }
    }

    /**
     * Disables biometric unlock for Primary vault and deletes the biometric key.
     */
    suspend fun disableBiometric(currentPin: CharArray) = withContext(dispatcher) {
        mutexV1.withLock {
            val dek = envelopeV1.unwrapWithPin(currentPin)
            try {
                envelopeV1.disableBiometric(dek, currentPin)
            } finally {
                CryptoUtils.zeroize(dek)
            }
        }
    }

    /**
     * Section 10: Immediately locks memory, zero-filling the active DEK.
     */
    fun lock() {
        activeSession?.close()
        activeSession = null
    }

    /**
     * Crypto-shreds all keys and data for both vaults.
     * Irreversible destruction of all Keystore keys, wrapped keys, and disk data.
     */
    suspend fun clearAllData() = withContext(dispatcher) {
        lock()
        mutexV1.withLock { envelopeV1.shred() }
        mutexV2.withLock { envelopeV2.shred() }
        wrongPinCounter.clear()
        pinLengthsRegistry.clear()
    }

    fun hasPasscode(vaultId: VaultId): Boolean {
        return if (vaultId == VaultId.PRIMARY) envelopeV1.hasPasscode() else envelopeV2.hasPasscode()
    }

    fun hasBiometric(vaultId: VaultId): Boolean {
        return if (vaultId == VaultId.PRIMARY) envelopeV1.hasBiometric() else envelopeV2.hasBiometric()
    }

    fun decoyExists(): Boolean = envelopeV2.exists()
}
