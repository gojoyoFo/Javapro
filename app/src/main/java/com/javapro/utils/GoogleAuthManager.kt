package com.javapro.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GoogleUser(
    val email       : String,
    val displayName : String,
    val photoUrl    : String?,
    val idToken     : String,
)

object GoogleAuthManager {

    private const val WEB_CLIENT_ID = "116820814193-93tmjku69ban1mraa0pgo96rd8ff636n.apps.googleusercontent.com"

    private const val PREFS_NAME    = "javapro_google_auth"
    private const val KEY_EMAIL     = "google_email"
    private const val KEY_NAME      = "google_name"
    private const val KEY_PHOTO_URL = "google_photo_url"
    private const val KEY_ID_TOKEN  = "google_id_token"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Simpan / baca user ────────────────────────────────────────────────────

    private fun saveUser(context: Context, user: GoogleUser) {
        prefs(context).edit()
            .putString(KEY_EMAIL,     user.email)
            .putString(KEY_NAME,      user.displayName)
            .putString(KEY_PHOTO_URL, user.photoUrl)
            .putString(KEY_ID_TOKEN,  user.idToken)
            .apply()
    }

    fun getUser(context: Context): GoogleUser? {
        val p     = prefs(context)
        val email = p.getString(KEY_EMAIL, null) ?: return null
        val name  = p.getString(KEY_NAME,  null) ?: return null
        val token = p.getString(KEY_ID_TOKEN, null) ?: return null
        return GoogleUser(
            email       = email,
            displayName = name,
            photoUrl    = p.getString(KEY_PHOTO_URL, null),
            idToken     = token,
        )
    }

    fun isSignedIn(context: Context): Boolean = getUser(context) != null

    fun clearUser(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ── Sign-in via Credential Manager ───────────────────────────────────────

    /**
     * Tampilkan Google Sign-In dialog.
     * Panggil dari Activity (bukan Application context).
     *
     * @return GoogleUser jika berhasil, null jika user batal / error
     */
    suspend fun signIn(context: Context): Result<GoogleUser> = withContext(Dispatchers.IO) {
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false) // tampilkan semua akun di device
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(context)
            val result            = credentialManager.getCredential(context, request)
            val credential        = result.credential

            if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return@withContext Result.failure(Exception("Unexpected credential type"))
            }

            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            val user = GoogleUser(
                email       = googleCred.id,
                displayName = googleCred.displayName ?: googleCred.id,
                photoUrl    = googleCred.profilePictureUri?.toString(),
                idToken     = googleCred.idToken,
            )

            saveUser(context, user)
            Result.success(user)

        } catch (e: GetCredentialException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Sign-out ──────────────────────────────────────────────────────────────

    suspend fun signOut(context: Context) {
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: ClearCredentialException) {
            // Abaikan error clear — tetap hapus data lokal
        } finally {
            clearUser(context)
            // Juga invalidate cache premium supaya re-check saat login ulang
            PremiumManager.clearPremium(context)
        }
    }
}
