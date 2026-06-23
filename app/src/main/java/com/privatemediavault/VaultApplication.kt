package com.privatemediavault

import android.app.Application
import com.google.crypto.tink.streamingaead.StreamingAeadConfig

/**
 * Application entry point for Private Media Vault.
 *
 * The app is 100% offline: there are no network permissions (see AndroidManifest.xml)
 * and no networking dependencies. All initialization here stays device-local.
 *
 * Two things are set up once for the process:
 *  - **Tink's streaming AEAD registry.** The media file store uses AES-256-GCM streaming
 *    AEAD; registering [StreamingAeadConfig] once makes Tink's streaming primitives
 *    available process-wide. (The crypto service uses the `subtle` `AesGcmHkdfStreaming`
 *    primitive directly, which does not strictly require registration, so this is wrapped
 *    defensively and never blocks startup.)
 *  - **The dependency graph.** [container] holds the single shared object graph
 *    (crypto, session manager, repository, view-model factories) for the process lifetime.
 */
class VaultApplication : Application() {

    /** The process-wide object graph; built lazily on first access by the activity. */
    val container: VaultContainer by lazy { VaultContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Register Tink's streaming AEAD primitives once for the process (Req 5.2).
        runCatching { StreamingAeadConfig.register() }
    }
}
