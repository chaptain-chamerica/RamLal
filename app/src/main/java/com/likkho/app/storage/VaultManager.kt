package com.likkho.app.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.likkho.app.storage.backup.BackupEngine
import com.likkho.app.storage.migration.DiaryMigrator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import kotlin.coroutines.coroutineContext

sealed class UnlockResult {
    data class Success(val vaultId: VaultId, val session: VaultSession) : UnlockResult()
    data class InCooldown(val remainingSeconds: Int) : UnlockResult()
    data class WrongPin(val cooldownTriggeredSeconds: Int, val isMaxApplicableLength: Boolean) : UnlockResult()
    object NoPasscodeConfigured : UnlockResult()
    object Cancelled : UnlockResult()
    data class BiometricInvalidated(
        val message: String = "Biometrics changed. Unlock with PIN and enable again."
    ) : UnlockResult()
    data class Error(val message: String) : UnlockResult()
}

/**
 * VaultManager orchestrates dual-vault storage, key hierarchy, parallel unlock logic,
 * wrong-PIN protection, calibrated PBKDF2 rewrap, CryptoObject biometrics, and memory locking.
 */
class VaultManager(
    val baseDir: File,
    val keyStoreProvider: KeyStoreProvider = AndroidKeyStoreProvider(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pbkdf2Iterations: Int? = null,
    val clockProvider: ClockProvider = DefaultClockProvider,
    val processStartTimeMs: Long = PROCESS_START_TIME_MS,
    private val kdfDispatcher: CoroutineDispatcher = if (dispatcher == Dispatchers.IO) Dispatchers.Default else dispatcher,
    private val targetIterationsProvider: () -> Int = { CryptoUtils.getCalibratedPbkdf2Iterations() }
) {
    companion object {
        val PROCESS_START_TIME_MS: Long = System.currentTimeMillis()
        const val BIOMETRIC_INVALIDATED_MESSAGE = "Biometrics changed. Unlock with PIN and enable again."
    }

    private data class PendingIterationRewrap(
        val vaultId: VaultId,
        val pinCopy: CharArray
    ) {
        fun clear() {
            CryptoUtils.zeroize(pinCopy)
        }
    }

    private val sessionProtectedMediaIds = ConcurrentHashMap.newKeySet<String>()
    private val mutexV1 = Mutex()
    private val mutexV2 = Mutex()
    private val rewrapLock = Any()

    @Volatile
    private var pendingRewrap: PendingIterationRewrap? = null

    val wrongPinCounter = WrongPinCounter(baseDir, keyStoreProvider, clockProvider)
    val pinLengthsRegistry = PinLengthsRegistry(baseDir)

    val envelopeV1 = VaultEnvelope(
        VaultId.PRIMARY,
        File(baseDir, VaultId.PRIMARY.dirName),
        keyStoreProvider,
        pbkdf2Iterations,
        targetIterationsProvider
    )

    val envelopeV2 = VaultEnvelope(
        VaultId.DECOY,
        File(baseDir, VaultId.DECOY.dirName),
        keyStoreProvider,
        pbkdf2Iterations,
        targetIterationsProvider
    )

    val primaryDiaryRepo = DiaryRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName))
    val decoyDiaryRepo = DiaryRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName))

    val primaryMediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName), clockProvider)
    val decoyMediaRepo = EncryptedMediaRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName), clockProvider)

    val primaryPasswordRepo = PasswordRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName))
    val decoyPasswordRepo = PasswordRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName))

    val primaryWordRepo = WordAnalysisRepository(VaultId.PRIMARY, File(baseDir, VaultId.PRIMARY.dirName))
    val decoyWordRepo = WordAnalysisRepository(VaultId.DECOY, File(baseDir, VaultId.DECOY.dirName))

    val backupEngine = BackupEngine(
        File(baseDir, "tmp"),
        defaultIterations = pbkdf2Iterations ?: CryptoUtils.DEFAULT_PBKDF2_ITERATIONS
    )
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

    fun currentSessionProtectedMediaIds(): Set<String> = sessionProtectedMediaIds.toSet()

    fun recordSessionImportedMedia(mediaId: String, vaultId: VaultId? = currentVault) {
        if (mediaId.isBlank()) return
        sessionProtectedMediaIds.add(mediaId)
        val targetVault = vaultId ?: return
        getMediaRepository(targetVault).recordPendingSessionImport(mediaId)
    }

    fun unprotectSessionMedia(mediaIds: Set<String>) {
        if (mediaIds.isEmpty()) return
        sessionProtectedMediaIds.removeAll(mediaIds)
    }

    fun clearSavedSessionMedia(mediaIds: Set<String>, vaultId: VaultId? = currentVault) {
        if (mediaIds.isEmpty()) return
        sessionProtectedMediaIds.removeAll(mediaIds)
        val targetVault = vaultId ?: return
        getMediaRepository(targetVault).clearPendingSessionImports(mediaIds)
    }

    fun cleanupVaultOrphans(
        vaultId: VaultId,
        dek: ByteArray,
        olderThanMs: Long = EncryptedMediaRepository.ORPHAN_AGE_THRESHOLD_MS
    ): Int {
        val diaryRepo = getDiaryRepository(vaultId)
        val mediaRepo = getMediaRepository(vaultId)
        val protectedIds = sessionProtectedMediaIds.toSet()
        val forcedOrphans = mediaRepo.loadPendingSessionImports().keys - protectedIds
        return diaryRepo.cleanupOrphanMedia(
            dek = dek,
            mediaRepo = mediaRepo,
            olderThanMs = olderThanMs,
            processStartTimeMs = processStartTimeMs,
            protectedMediaIds = protectedIds,
            forcedOrphanIds = forcedOrphans
        )
    }

    fun cleanupActiveVaultOrphans(
        dek: ByteArray,
        olderThanMs: Long = EncryptedMediaRepository.ORPHAN_AGE_THRESHOLD_MS
    ): Int {
        val vault = currentVault ?: throw IllegalStateException("No active vault unlocked")
        return cleanupVaultOrphans(vault, dek, olderThanMs)
    }

    fun cleanupStaleTempDirs(): Long = backupEngine.cleanupStaleTempDirs()

    private fun clearPendingRewrap() {
        synchronized(rewrapLock) {
            pendingRewrap?.clear()
            pendingRewrap = null
        }
    }

    private fun schedulePendingRewrap(vaultId: VaultId, pin: CharArray) {
        synchronized(rewrapLock) {
            pendingRewrap?.clear()
            pendingRewrap = PendingIterationRewrap(vaultId, pin.copyOf())
        }
    }

    fun hasPendingIterationRewrap(): Boolean = synchronized(rewrapLock) { pendingRewrap != null }

    /**
     * Executes deferred post-unlock maintenance (stale temp cleanup, scanAndRecover, orphan cleanup,
     * and background PBKDF2 iteration migration if scheduled) off the critical unlock path at low priority.
     */
    suspend fun runPostUnlockMaintenance(
        expectedVaultId: VaultId? = currentVault,
        beforeRewrapCommitHook: ((File) -> Unit)? = null
    ): RecoveryReport? = withContext(dispatcher) {
        val vaultId = expectedVaultId ?: return@withContext null
        val mutex = if (vaultId == VaultId.PRIMARY) mutexV1 else mutexV2
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }

        mutex.withLock {
            val session = activeSession ?: return@withLock null
            if (!session.isOpen || session.vaultId != vaultId) return@withLock null

            var report: RecoveryReport? = null
            try {
                val t0 = System.nanoTime()
                backupEngine.cleanupStaleTempDirs()

                val rewrapTask = synchronized(rewrapLock) {
                    val pending = pendingRewrap
                    if (pending != null && pending.vaultId == vaultId) {
                        pendingRewrap = null
                        pending
                    } else {
                        null
                    }
                }
                if (rewrapTask != null) {
                    try {
                        val env = if (vaultId == VaultId.PRIMARY) envelopeV1 else envelopeV2
                        val targetIter = env.resolveTargetIterations()
                        session.useDek { dek ->
                            val dekCopy = dek.copyOf()
                            try {
                                env.rewrapIterationsAtomic(
                                    currentDek = dekCopy,
                                    pin = rewrapTask.pinCopy,
                                    newIterations = targetIter,
                                    beforeCommitHook = beforeRewrapCommitHook
                                )
                                CryptoUtils.logPerf("background_rewrap_success iter=$targetIter")
                            } finally {
                                CryptoUtils.zeroize(dekCopy)
                            }
                        }
                    } catch (t: Throwable) {
                        CryptoUtils.logPerf("background_rewrap_aborted reason=${t.javaClass.simpleName}")
                    } finally {
                        rewrapTask.clear()
                    }
                }

                if (!session.isOpen || activeSession !== session) return@withLock null
                session.useDek { dek ->
                    val diaryRepo = getDiaryRepository(vaultId)
                    val tScan0 = System.nanoTime()
                    val scanReport = diaryRepo.scanAndRecover(dek, dekVerified = true)
                    val scanMs = (System.nanoTime() - tScan0) / 1_000_000.0
                    val tOrph0 = System.nanoTime()
                    cleanupVaultOrphans(vaultId, dek)
                    val orphMs = (System.nanoTime() - tOrph0) / 1_000_000.0
                    lastRecoveryReport = scanReport
                    report = scanReport
                    val totalMs = (System.nanoTime() - t0) / 1_000_000.0
                    CryptoUtils.logPerf(
                        "post_unlock_maintenance scanMs=${"%.2f".format(scanMs)} orphanMs=${"%.2f".format(orphMs)} totalMs=${"%.2f".format(totalMs)}"
                    )
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (t: Throwable) {
                CryptoUtils.logPerf("post_unlock_maintenance_error type=${t.javaClass.simpleName}")
            }
            report
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
     * If not present, creates it in Keystore-only (no-passcode) mode and opens session immediately.
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
            lock()
            activeSession = session
            lastRecoveryReport = null
            session
        }
    }

    /**
     * Attempts to unlock either Primary (v1) or Decoy (v2) vault using [pin].
     * Checks both envelopes in PARALLEL and waits for both before returning so neither timing
     * nor failures leak which vault is configured or opened.
     * Returns [UnlockResult.Success] immediately after DEK unwrap (maintenance runs deferred).
     */
    suspend fun unlockWithPin(
        pin: CharArray,
        isExplicitSubmit: Boolean = false,
        zeroizeInput: Boolean = false
    ): UnlockResult = withContext(dispatcher) {
        try {
            val cooldown = wrongPinCounter.remainingCooldownSeconds()
            if (cooldown > 0) {
                return@withContext UnlockResult.InCooldown(cooldown)
            }

            val v1Active = envelopeV1.exists() && envelopeV1.hasPasscode()
            val v2Active = envelopeV2.exists() && envelopeV2.hasPasscode()

            val tTotal0 = System.nanoTime()
            var v1Dek: ByteArray? = null
            var v2Dek: ByteArray? = null

            val pinCopy1 = if (v1Active) pin.copyOf() else null
            val pinCopy2 = if (v2Active) pin.copyOf() else null

            try {
                coroutineScope {
                    val d1 = if (v1Active) {
                        async(kdfDispatcher) {
                            val p = pinCopy1!!
                            try {
                                if (!isActive) return@async null
                                mutexV1.withLock {
                                    if (!isActive) return@withLock null
                                    runCatching { envelopeV1.unwrapWithPin(p) }.getOrNull()
                                }
                            } finally {
                                CryptoUtils.zeroize(p)
                            }
                        }
                    } else null

                    val d2 = if (v2Active) {
                        async(kdfDispatcher) {
                            val p = pinCopy2!!
                            try {
                                if (!isActive) return@async null
                                mutexV2.withLock {
                                    if (!isActive) return@withLock null
                                    runCatching { envelopeV2.unwrapWithPin(p) }.getOrNull()
                                }
                            } finally {
                                CryptoUtils.zeroize(p)
                            }
                        }
                    } else null

                    val results = listOfNotNull(d1, d2).awaitAll()
                    var idx = 0
                    if (d1 != null) v1Dek = results[idx++]
                    if (d2 != null) v2Dek = results[idx]
                }
            } catch (t: Throwable) {
                CryptoUtils.zeroize(v1Dek)
                CryptoUtils.zeroize(v2Dek)
                CryptoUtils.zeroize(pinCopy1)
                CryptoUtils.zeroize(pinCopy2)
                throw t
            }

            if (!coroutineContext.isActive) {
                CryptoUtils.zeroize(v1Dek)
                CryptoUtils.zeroize(v2Dek)
                throw CancellationException("Unlock attempt superseded")
            }

            val totalMs = (System.nanoTime() - tTotal0) / 1_000_000.0
            CryptoUtils.logPerf("unlock_pin_parallel ms=${"%.2f".format(totalMs)}")

            val matchedVault: VaultId? = when {
                v1Dek != null -> {
                    CryptoUtils.zeroize(v2Dek)
                    VaultId.PRIMARY
                }
                v2Dek != null -> VaultId.DECOY
                else -> null
            }

            if (matchedVault != null) {
                val matchedDek = if (matchedVault == VaultId.PRIMARY) v1Dek!! else v2Dek!!
                wrongPinCounter.recordSuccess()
                lock()
                val session = VaultSession(matchedVault, matchedDek)
                activeSession = session
                lastRecoveryReport = null

                val env = if (matchedVault == VaultId.PRIMARY) envelopeV1 else envelopeV2
                val storedIter = env.storedIterations() ?: 0
                val minTargetFloor = pbkdf2Iterations ?: CryptoUtils.MIN_CALIBRATED_ITERATIONS
                if (storedIter > minTargetFloor) {
                    schedulePendingRewrap(matchedVault, pin)
                }
                return@withContext UnlockResult.Success(matchedVault, session)
            }

            val isMaxLen = pinLengthsRegistry.isMaxRegisteredLength(pin.size) || isExplicitSubmit
            val triggeredCooldown = if (isMaxLen) {
                wrongPinCounter.recordFailure()
            } else {
                0
            }

            UnlockResult.WrongPin(triggeredCooldown, isMaxLen)
        } finally {
            if (zeroizeInput) {
                CryptoUtils.zeroize(pin)
            }
        }
    }

    /**
     * Unlocks vault if no passcode is set. Returns [UnlockResult.Success] immediately (maintenance deferred).
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
                    lastRecoveryReport = null
                    UnlockResult.Success(vaultId, session)
                } catch (e: Exception) {
                    UnlockResult.Error(e.message ?: "Failed to unwrap Keystore key")
                }
            }
        }

    /**
     * Prepares a Keystore ENCRYPT Cipher for BiometricPrompt enrollment (Primary vault only).
     */
    fun prepareBiometricEncryptCipher(): Cipher {
        val session = activeSession
        check(session != null && session.isOpen && session.vaultId == VaultId.PRIMARY) {
            "Primary vault must be unlocked to enable biometrics"
        }
        check(envelopeV1.hasPasscode()) { "Set a passcode before enabling biometrics" }
        return envelopeV1.prepareBiometricEncryptCipher()
    }

    /**
     * Enables biometric unlock using the active unlocked session DEK and the authenticated
     * BiometricPrompt ENCRYPT [authedCipher]. Never requires re-entering the user PIN.
     */
    suspend fun enableBiometricWithCipher(authedCipher: Cipher) = withContext(dispatcher) {
        mutexV1.withLock {
            val session = activeSession
            check(session != null && session.isOpen && session.vaultId == VaultId.PRIMARY) {
                "Primary vault must be unlocked to enable biometrics"
            }
            val dekCopy = ByteArray(CryptoUtils.AES_KEY_SIZE_BYTES)
            session.useDek { dek -> System.arraycopy(dek, 0, dekCopy, 0, dekCopy.size) }
            try {
                envelopeV1.enableBiometricWithCipher(dekCopy, authedCipher)
            } finally {
                CryptoUtils.zeroize(dekCopy)
            }
        }
    }

    /**
     * Disables biometric unlock for Primary vault, deleting the biometric Keystore key and wrapped DEK copy.
     */
    suspend fun disableBiometric() = withContext(dispatcher) {
        mutexV1.withLock {
            envelopeV1.clearBiometricKeyAndEnvelope()
        }
    }

    /**
     * Unlocks Primary vault strictly through a BiometricPrompt CryptoObject authenticator.
     * Never bypasses BiometricPrompt and never executes PBKDF2.
     * On biometric invalidation (e.g. new fingerprint enrolled), purges biometric key + wrapped copy cleanly.
     */
    suspend fun unlockWithBiometricPrompt(
        authenticateCipher: suspend (Cipher) -> Cipher?
    ): UnlockResult = withContext(dispatcher) {
        val cooldown = wrongPinCounter.remainingCooldownSeconds()
        if (cooldown > 0) {
            return@withContext UnlockResult.InCooldown(cooldown)
        }

        if (!envelopeV1.exists() || !envelopeV1.hasBiometric()) {
            return@withContext UnlockResult.Error("Biometrics not enabled on vault")
        }

        mutexV1.withLock {
            val decryptCipher = try {
                envelopeV1.prepareBiometricDecryptCipher()
            } catch (t: Throwable) {
                if (isBiometricInvalidationError(t)) {
                    envelopeV1.clearBiometricKeyAndEnvelope()
                    return@withLock UnlockResult.BiometricInvalidated(BIOMETRIC_INVALIDATED_MESSAGE)
                }
                return@withLock UnlockResult.Error(t.message ?: "Biometric setup error")
            }

            val authedCipher = try {
                authenticateCipher(decryptCipher)
            } catch (t: Throwable) {
                if (isBiometricInvalidationError(t)) {
                    envelopeV1.clearBiometricKeyAndEnvelope()
                    return@withLock UnlockResult.BiometricInvalidated(BIOMETRIC_INVALIDATED_MESSAGE)
                }
                val msg = t.message?.takeIf { it.isNotBlank() && t !is CancellationException }
                if (msg != null && t is IllegalStateException) {
                    return@withLock UnlockResult.Error(msg)
                }
                return@withLock UnlockResult.Cancelled
            } ?: return@withLock UnlockResult.Cancelled

            if (!coroutineContext.isActive) {
                return@withLock UnlockResult.Cancelled
            }

            try {
                val dek = envelopeV1.unwrapWithBiometricCipher(authedCipher)
                wrongPinCounter.recordSuccess()
                lock()
                val session = VaultSession(VaultId.PRIMARY, dek)
                activeSession = session
                lastRecoveryReport = null
                UnlockResult.Success(VaultId.PRIMARY, session)
            } catch (t: Throwable) {
                if (isBiometricInvalidationError(t)) {
                    envelopeV1.clearBiometricKeyAndEnvelope()
                    UnlockResult.BiometricInvalidated(BIOMETRIC_INVALIDATED_MESSAGE)
                } else {
                    UnlockResult.Error("Biometric authentication failed: ${t.message}")
                }
            }
        }
    }

    /**
     * Sets or updates PIN on Primary vault. Re-wraps existing DEK.
     * Keeps biometric wrapped copy valid if already enabled (same DEK).
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
     * Removes passcode on Primary vault. Re-wraps DEK with Keystore only and deletes biometric key & copy.
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
     * Section 10: Immediately locks memory, zero-filling the active DEK and any pending rewrap PIN.
     */
    fun lock() {
        clearPendingRewrap()
        activeSession?.close()
        activeSession = null
    }

    /**
     * Crypto-shreds all keys and data for both vaults.
     * Irreversible destruction of all Keystore keys, wrapped keys, and disk data.
     */
    suspend fun clearAllData() = withContext(dispatcher) {
        lock()
        sessionProtectedMediaIds.clear()
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
