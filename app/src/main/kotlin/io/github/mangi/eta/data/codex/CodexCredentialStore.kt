package io.github.mangi.eta.data.codex

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/** Stores ChatGPT OAuth tokens locally, independent of provider API-key storage. */
internal class CodexCredentialStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun read(): CodexAccountState {
        if (!file.isFile) return CodexAccountState()
        return runCatching {
            val bytes = file.readBytes()
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            json.decodeFromString(
                CodexAccountState.serializer(),
                cipher.doFinal(encrypted).decodeToString(),
            ).normalized()
        }.getOrDefault(CodexAccountState())
    }

    fun write(state: CodexAccountState) {
        // Old OAuthManager writes CodexAccountState(account = ...). Treat that shape as
        // an upsert so signing in with a second account never erases the first one.
        val normalized = if (state.account != null && state.accounts.isEmpty()) {
            val current = read().normalized()
            val incoming = state.account
            current.copy(
                accounts = current.accounts
                    .filterNot { it.chatgptAccountId == incoming.chatgptAccountId } + incoming,
            ).normalized()
        } else {
            state.normalized()
        }
        writeEncrypted(normalized)
    }

    fun clear() {
        file.delete()
    }

    private fun writeEncrypted(state: CodexAccountState) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(
            json.encodeToString(CodexAccountState.serializer(), state).encodeToByteArray(),
        )
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeBytes(cipher.iv + encrypted)
        temporary.copyTo(file, overwrite = true)
        temporary.delete()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "codex_oauth.enc"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "eta_codex_oauth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_LENGTH_BITS = 128
    }
}
