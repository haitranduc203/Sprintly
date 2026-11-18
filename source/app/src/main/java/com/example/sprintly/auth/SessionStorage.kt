package com.example.sprintly.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.sprintly.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONException
import org.json.JSONObject

internal class StoredSession(val user: AuthUser, val refreshToken: String)

internal interface SessionStorage {
    fun read(): StoredSession?
    fun write(session: StoredSession)
    fun clear()
}

internal class SessionStorageException(cause: Exception) : Exception(cause)

@Singleton
internal class EncryptedSessionStorage @Inject constructor(
    @ApplicationContext context: Context,
) : SessionStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "auth_session"))
    private val environment = BuildConfig.API_BASE_URL.toByteArray(Charsets.UTF_8)

    override fun read(): StoredSession? = accessStorage {
        val bytes = try {
            file.readFully()
        } catch (exception: FileNotFoundException) {
            if (filesExist()) throw exception
            return@accessStorage null
        }
        if (bytes.size <= IV_LENGTH + TAG_BITS / Byte.SIZE_BITS) throw IOException()

        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: throw GeneralSecurityException()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH))
            updateAAD(environment)
        }
        val plaintext = cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
        val payload = JSONObject(plaintext.toString(Charsets.UTF_8))
        StoredSession(
            AuthUser(payload.getString("id"), payload.getString("email"), payload.getString("displayName")),
            payload.getString("refreshToken"),
        )
    }

    override fun write(session: StoredSession) = accessStorage {
        val payload = JSONObject()
            .put("id", session.user.id)
            .put("email", session.user.email)
            .put("displayName", session.user.displayName)
            .put("refreshToken", session.refreshToken)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, existingOrNewKey())
            updateAAD(environment)
        }
        val record = cipher.iv + cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        val output = file.startWrite()
        try {
            output.write(record)
            file.finishWrite(output)
        } catch (exception: IOException) {
            file.failWrite(output)
            throw exception
        }
        if (!file.readFully().contentEquals(record)) throw IOException()
    }

    override fun clear() = accessStorage {
        file.delete()
        if (filesExist()) keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun filesExist(): Boolean = file.baseFile.exists() ||
        File(file.baseFile.path + ".bak").exists() ||
        File(file.baseFile.path + ".new").exists()

    private fun existingOrNewKey(): SecretKey =
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private inline fun <T> accessStorage(block: () -> T): T = try {
        block()
    } catch (exception: IOException) {
        throw SessionStorageException(exception)
    } catch (exception: GeneralSecurityException) {
        throw SessionStorageException(exception)
    } catch (exception: ProviderException) {
        throw SessionStorageException(exception)
    } catch (exception: JSONException) {
        throw SessionStorageException(exception)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.example.sprintly.auth.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
