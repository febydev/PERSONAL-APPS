package com.privatemediavault

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.privatemediavault.data.AndroidKeyStoreProvider
import com.privatemediavault.data.ContentUriImportSourceFactory
import com.privatemediavault.data.DefaultMediaRepository
import com.privatemediavault.data.EncryptedFileSecurePrefs
import com.privatemediavault.data.EncryptedFileStore
import com.privatemediavault.data.FileLockoutStore
import com.privatemediavault.data.ImportSource
import com.privatemediavault.data.InMemorySettingsStore
import com.privatemediavault.data.KeyStoreProvider
import com.privatemediavault.data.LockoutStore
import com.privatemediavault.data.MediaDao
import com.privatemediavault.data.MediaItem
import com.privatemediavault.data.MediaRepository
import com.privatemediavault.data.SecurePrefs
import com.privatemediavault.data.SettingsStore
import com.privatemediavault.data.TinkEncryptedFileStore
import com.privatemediavault.data.VaultDatabase
import com.privatemediavault.domain.AuthService
import com.privatemediavault.domain.CryptoService
import com.privatemediavault.domain.SessionManager
import com.privatemediavault.domain.auth.DefaultAuthService
import com.privatemediavault.domain.crypto.Argon2CryptoService
import com.privatemediavault.domain.session.DefaultSessionManager
import com.privatemediavault.viewmodel.AuthViewModel
import com.privatemediavault.viewmodel.SettingsViewModel
import com.privatemediavault.viewmodel.VaultViewModel
import com.privatemediavault.viewmodel.ViewerViewModel
import java.io.File

/**
 * The application's manual dependency-injection container: this is where the whole object
 * graph for the Private Media Vault is constructed and held for the process lifetime.
 *
 * It builds the cryptographic and key-management layer (Keystore master key, Argon2id
 * crypto, the encrypted key record and lockout stores), the authentication and session
 * layer ([AuthService] + the [SessionManager] that owns the in-memory DEK), the encrypted
 * storage and repository layer (Room metadata DB, the Tink-backed file store, and the
 * repository), the shared settings store, and the `Uri -> ImportSource` bridge. From those
 * it exposes the view-model factories the navigation host wires to each screen.
 *
 * A single [SessionManager] instance is shared everywhere so that the activity's lifecycle
 * (auto-lock on background, Req 9.1/9.2) and every view model observe the *same* session
 * state. A single [SettingsStore] is shared between the vault and settings view models so a
 * remove-originals toggle made in settings is honoured by the very next import (Req 4.4).
 *
 * @param context any context; its application context is used so nothing here outlives or
 *   leaks the activity.
 */
class VaultContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    // --- Storage locations (all under app-private filesDir; Req 5.1) ---
    private val vaultDir: File = File(appContext.filesDir, VAULT_DIR_NAME).apply { mkdirs() }
    private val keyRecordFile: File = File(vaultDir, KEY_RECORD_FILE_NAME)
    private val lockoutFile: File = File(appContext.filesDir, LOCKOUT_FILE_NAME)

    // --- Crypto + key management ---
    private val keyStoreProvider: KeyStoreProvider = AndroidKeyStoreProvider()
    private val crypto: CryptoService = Argon2CryptoService()
    private val securePrefs: SecurePrefs = EncryptedFileSecurePrefs(keyRecordFile, keyStoreProvider)
    private val lockoutStore: LockoutStore = FileLockoutStore(lockoutFile)

    // --- Authentication + session ---
    private val authService: AuthService = DefaultAuthService(crypto, securePrefs, lockoutStore)

    /**
     * The single source of truth for "is a session active". Shared with the activity's
     * lifecycle observer (which ends it on background) and every view model (which observe
     * its state to re-blur on lock — Req 6.3, 9.1, 9.2). The view models react to
     * [SessionManager.sessionState] directly, so no imperative `onSessionEnd` hook is needed.
     */
    val sessionManager: SessionManager =
        DefaultSessionManager(authService, crypto, securePrefs)

    // --- Metadata DB + encrypted storage + repository ---
    private val database: VaultDatabase = Room.databaseBuilder(
        appContext,
        VaultDatabase::class.java,
        VaultDatabase.DATABASE_NAME,
    ).build()
    private val mediaDao: MediaDao = database.mediaDao()

    private val fileStore: EncryptedFileStore =
        TinkEncryptedFileStore(vaultDir, crypto, sessionManager)
    private val repository: MediaRepository =
        DefaultMediaRepository(mediaDao, fileStore, sessionManager)

    // --- Shared cross-screen settings (Req 4.4) ---
    private val settingsStore: SettingsStore = InMemorySettingsStore()

    // --- Uri -> ImportSource bridge (ContentResolver/MediaMetadataRetriever-backed) ---
    private val importSourceFactory: (Uri) -> ImportSource =
        ContentUriImportSourceFactory(appContext)

    // --- View-model factories handed to the navigation host (task 10.1) ---

    val authViewModelFactory: AuthViewModel.Factory =
        AuthViewModel.Factory(authService, sessionManager)

    val vaultViewModelFactory: VaultViewModel.Factory =
        VaultViewModel.Factory(repository, sessionManager, importSourceFactory, settingsStore)

    val settingsViewModelFactory: SettingsViewModel.Factory =
        SettingsViewModel.Factory(authService, sessionManager, repository, settingsStore)

    /**
     * Builds a [ViewerViewModel.Factory] bound to a specific [item]; the viewer is created
     * per-navigation because its view model is scoped to the item being viewed.
     */
    fun viewerViewModelFactory(item: MediaItem): ViewerViewModel.Factory =
        ViewerViewModel.Factory(item, repository, sessionManager)

    private companion object {
        const val VAULT_DIR_NAME = "vault"
        const val KEY_RECORD_FILE_NAME = "key_record.bin"
        const val LOCKOUT_FILE_NAME = "lockout"
    }
}
