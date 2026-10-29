package com.sportfit.sync.data.repo

import com.sportfit.sync.core.crypto.CryptoBox
import com.sportfit.sync.data.local.AccountEntity
import com.sportfit.sync.data.local.AccountDao
import com.sportfit.sync.domain.Platform
import com.sportfit.sync.platform.PlatformAccount
import kotlinx.coroutines.flow.Flow

/**
 * Owns everything sensitive about a platform account.
 *
 * Credentials only ever exist as ciphertext at rest (Android Keystore,
 * AES-GCM) and are decrypted on demand into a [PlatformAccount] that lives no
 * longer than the sync run using it.
 */
class AccountRepository(private val dao: AccountDao) {

    fun observeAll(): Flow<List<AccountEntity>> = dao.observeAll()

    suspend fun getAll(): List<AccountEntity> = dao.getAll()

    suspend fun get(platform: Platform): AccountEntity? = dao.get(platform.key)

    /** True when the platform has a usable account row. */
    suspend fun has(platform: Platform): Boolean = dao.get(platform.key) != null

    suspend fun save(
        platform: Platform,
        email: String?,
        password: String?,
        region: String?,
        token: String? = null,
        status: String = AccountEntity.STATUS_NEEDS_LOGIN,
    ) {
        val existing = dao.get(platform.key)
        val now = System.currentTimeMillis()
        dao.upsert(
            AccountEntity(
                platform = platform.key,
                displayName = platform.displayName,
                // Blank means "leave unchanged": the edit form never echoes
                // secrets back to the screen.
                emailCipher = email?.let { CryptoBox.encrypt(it) } ?: existing?.emailCipher,
                secretCipher = password?.let { CryptoBox.encrypt(it) } ?: existing?.secretCipher,
                region = region ?: existing?.region,
                tokenCipher = token?.let { CryptoBox.encrypt(it) } ?: existing?.tokenCipher,
                status = status,
                lastError = existing?.lastError,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(platform: Platform) = dao.delete(platform.key)

    suspend fun persistToken(platform: Platform, token: String) =
        dao.updateToken(platform.key, CryptoBox.encrypt(token))

    suspend fun updateStatus(platform: Platform, status: String, error: String? = null) =
        dao.updateStatus(
            platform = platform.key,
            status = status,
            error = error,
            verifiedAt = if (status == AccountEntity.STATUS_CONNECTED) {
                System.currentTimeMillis()
            } else {
                null
            },
        )

    /** Decrypts an account for use by a platform client. */
    suspend fun credentials(platform: Platform): PlatformAccount? {
        val row = dao.get(platform.key) ?: return null
        return PlatformAccount(
            platform = platform,
            email = CryptoBox.decryptOrNull(row.emailCipher),
            password = CryptoBox.decryptOrNull(row.secretCipher),
            region = row.region,
            token = CryptoBox.decryptOrNull(row.tokenCipher),
        )
    }

    /**
     * Plain credentials for flows that must build a platform-specific request
     * (the Strava code exchange needs the raw client id and secret).
     */
    suspend fun plainCredentials(platform: Platform): Pair<String?, String?> {
        val row = dao.get(platform.key) ?: return null to null
        return CryptoBox.decryptOrNull(row.emailCipher) to CryptoBox.decryptOrNull(row.secretCipher)
    }

    /** Human-readable account label for lists, without exposing the secret. */
    fun maskedEmail(row: AccountEntity): String {
        val email = CryptoBox.decryptOrNull(row.emailCipher) ?: return "未配置"
        return if (email.length <= 6) email else "${email.take(3)}***${email.takeLast(2)}"
    }
}
