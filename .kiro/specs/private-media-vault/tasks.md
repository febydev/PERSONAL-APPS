# Implementation Plan: Private Media Vault

## Overview

This plan implements the Private Media Vault as a native Kotlin Android app (single-Activity, Jetpack Compose, MVVM). It builds bottom-up: cryptographic primitives and key management first, then authentication and session lifecycle, then encrypted storage and the repository, then blur rendering, then the Compose UI and view models, and finally the activity lifecycle wiring that enforces auto-lock and app-switcher protection. Each step builds on the previous and ends wired into the app. Property-based tests (jqwik) validate the 15 correctness properties from the design and are placed next to the code they cover.

## Tasks

- [x] 1. Set up project structure and core types
  - [x] 1.1 Initialize the Android project and shared types
    - Create single-Activity Compose project structure with `ui`, `viewmodel`, `domain`, and `data` packages
    - Configure Gradle: Compose, Room, Google Tink, ExoPlayer, Argon2 binding, and jqwik test dependency
    - Confirm `AndroidManifest.xml` declares **no** `INTERNET` and **no** `ACCESS_NETWORK_STATE` permission (offline guarantee)
    - Define core enums and result types: `MediaType`, `AuthResult`, `CreateResult`, `VerifyResult`, `ChangeResult`, `ImportReport`, `FailedImport`, `SessionState`, `LockoutState`
    - _Requirements: 3.1, 3.2, 3.3_

- [x] 2. Implement CryptoService
  - [x] 2.1 Implement PIN hashing and key derivation
    - Implement `hashPin`, `verifyPinHash`, and `deriveKek` using Argon2id with `ArgonParams`
    - _Requirements: 1.5, 2.2_

  - [x]* 2.2 Write property test for salted one-way PIN hashing
    - **Property 3: PIN hashing is salted and one-way**
    - **Validates: Requirements 1.5**

  - [x] 2.3 Implement DEK wrapping
    - Implement `wrapDek` and `unwrapDek` using AES-256-GCM
    - _Requirements: 5.2, 12.3_

  - [x]* 2.4 Write property test for DEK wrap round-trip
    - **Property 9: DEK wrapping round-trips and is key-bound**
    - **Validates: Requirements 5.2, 12.3**

  - [x] 2.5 Implement streaming media encryption
    - Implement `encryptStream` and `decryptStream` using Tink streaming AEAD (AES-256-GCM) with AAD binding
    - _Requirements: 5.2, 5.3_

  - [x]* 2.6 Write property test for encryption round-trip
    - **Property 7: Media encryption round-trips**
    - **Validates: Requirements 5.2, 5.3**

  - [x]* 2.7 Write property test for encryption integrity
    - **Property 8: Encryption provides integrity** (tampered ciphertext, wrong DEK, wrong AAD all fail)
    - **Validates: Requirements 5.2**

- [x] 3. Implement KeyStoreProvider
  - [x] 3.1 Implement Android Keystore wrapper
    - Implement `getOrCreateMasterKey` (hardware-backed AES-256-GCM where available), `encryptBlob`, `decryptBlob` for the wrapped-DEK record
    - _Requirements: 5.1, 5.2_

  - [x]* 3.2 Write unit tests for Keystore blob round-trip
    - Test encrypt/decrypt round-trip and failure on tampered blob
    - _Requirements: 5.2_

- [x] 4. Implement authentication and session management
  - [x] 4.1 Implement key record persistence and PIN creation
    - Implement `SecurePrefs` persistence of `VaultKeyRecord` (pinSalt, pinHash, kekSalt, wrappedDek, argonParams)
    - Implement `AuthService.isPinSet` and `AuthService.createPin` (length check, confirmation match, generate random DEK, derive KEK, wrap DEK, write record)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [x]* 4.2 Write property test for PIN length validation
    - **Property 1: PIN length validation is total**
    - **Validates: Requirements 1.2**

  - [x]* 4.3 Write property test for PIN creation confirmation
    - **Property 2: PIN creation requires matching confirmation**
    - **Validates: Requirements 1.3, 1.4**

  - [x] 4.4 Implement PIN verification and lockout
    - Implement `AuthService.verifyPin` with `LockoutState` consecutive-failure counting, 5-attempt threshold, 30-second lockout, and remaining-time reporting
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x]* 4.5 Write property test for PIN verification exactness
    - **Property 4: PIN verification is exact**
    - **Validates: Requirements 2.2, 2.3**

  - [x]* 4.6 Write property test for lockout threshold
    - **Property 5: Lockout triggers at the fifth consecutive failure**
    - **Validates: Requirements 2.4**

  - [x]* 4.7 Write property test for lockout countdown
    - **Property 6: Lockout countdown is bounded and monotonic**
    - **Validates: Requirements 2.4, 2.5**

  - [x] 4.8 Implement SessionManager
    - Implement `authenticate` (verify, derive KEK, unwrap DEK into memory), `isUnlocked`, `withDek`, and `endSession` (zero DEK, reset all render state to blurred); expose `sessionState` StateFlow
    - _Requirements: 2.2, 5.3, 5.4, 6.3, 9.1, 9.4_

  - [x]* 4.9 Write property test for session-end re-blur
    - **Property 14: Session end returns everything to blurred**
    - **Validates: Requirements 6.3, 9.1, 9.2**

  - [x] 4.10 Implement PIN change
    - Implement `AuthService.changePin`: verify current PIN, validate new/confirm, unwrap DEK with old KEK, re-wrap with new KEK, rewrite record without re-encrypting media
    - _Requirements: 12.1, 12.2, 12.3_

  - [x]* 4.11 Write property test for PIN change re-wrap
    - **Property 15: PIN change re-wraps the DEK without re-encrypting media**
    - **Validates: Requirements 12.1, 12.2, 12.3**

- [x] 5. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Implement data layer
  - [x] 6.1 Implement Room metadata store
    - Implement `MediaItem` entity and `MediaDao` (insert, delete, observe) for metadata only (no media bytes)
    - _Requirements: 5.1_

  - [x] 6.2 Implement EncryptedFileStore
    - Implement `importFrom` (copy + encrypt into `filesDir/vault/`), `openDecrypted`, `exportTo`, and `delete`; refuse decrypt/export operations when the session is locked
    - _Requirements: 4.1, 5.1, 5.2, 5.3, 5.4, 10.2, 11.1_

  - [x]* 6.3 Write property test for session-gated decryption
    - **Property 10: Decryption is session-gated**
    - **Validates: Requirements 5.3, 5.4, 7.2, 11.2**

  - [x] 6.4 Implement MediaRepository
    - Implement `observeItems`, `importItems` (per-file success/failure reporting, optional remove-originals), `deleteItem`, `exportItem`, `decryptedThumbnail` (session-gated)
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 10.2, 10.3, 11.1_

  - [x]* 6.5 Write property test for import partitioning
    - **Property 11: Import partitions inputs without loss**
    - **Validates: Requirements 4.1, 4.3**

  - [x]* 6.6 Write unit tests for delete and export flows
    - Test permanent removal from storage + metadata, and export of decrypted copy
    - _Requirements: 10.2, 10.3, 11.1_

- [x] 7. Implement blur rendering
  - [x] 7.1 Implement BlurRenderer
    - Implement `blurModifier` (RenderEffect / `Modifier.blur`), `blurredPosterFrame` fallback for API < 31, and `isDiscernible` QA helper
    - _Requirements: 6.1, 6.2_

  - [x]* 7.2 Write property test for blur indiscernibility
    - **Property 13: Blurred rendering is not discernible**
    - **Validates: Requirements 6.2**

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement UI and view models
  - [x] 9.1 Implement AuthViewModel and PinScreen
    - Wire PIN creation (first launch), PIN entry, incorrect-PIN messaging, and live lockout countdown to `AuthService`/`SessionManager`
    - _Requirements: 1.1, 2.1, 2.3, 2.4, 2.5_

  - [x] 9.2 Implement VaultViewModel and VaultGridScreen
    - Observe repository items, hold `MediaRenderState` (default `isClear = false`), render every item blurred by default via `BlurRenderer`, surface import action and per-file import errors
    - _Requirements: 4.2, 4.3, 6.1, 6.3_

  - [x]* 9.3 Write property test for blurred-by-default load
    - **Property 12: Items load blurred by default**
    - **Validates: Requirements 6.1**

  - [x] 9.4 Implement ViewerViewModel and MediaViewerScreen
    - Implement unblur (session-gated, deny + show PIN screen when locked), re-blur (with failure handling keeping clear state), and ExoPlayer video playback in clear state
    - _Requirements: 7.1, 7.2, 7.3, 8.1, 8.2_

  - [x]* 9.5 Write unit tests for viewer unblur/re-blur and session-denied actions
    - Test unblur denial when locked, re-blur failure path, and export denial requiring re-initiation
    - _Requirements: 7.2, 8.2, 11.2, 11.3_

  - [x] 9.6 Implement SettingsScreen
    - Implement PIN change flow, explicit lock action, delete confirmation flow, export action, and remove-originals toggle, wired to repository/auth/session
    - _Requirements: 4.4, 9.4, 10.1, 11.1, 12.1_

- [x] 10. Implement lifecycle and session wiring
  - [x] 10.1 Implement VaultActivity lifecycle integration
    - Set `FLAG_SECURE` on the window at creation; observe process lifecycle and call `SessionManager.endSession()` on `ON_STOP`; route locked state to PinScreen; wire single-Activity Compose navigation across all screens
    - _Requirements: 5.4, 9.1, 9.2, 9.3, 9.4_

  - [x]* 10.2 Write integration tests for auto-lock and recents protection
    - Test background → session end → all items blurred, and locked-state routing to PIN entry
    - _Requirements: 9.1, 9.2, 9.3_

- [x] 11. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirement sub-clauses for traceability.
- The 15 property-based tests map one-to-one to the Correctness Properties in `design.md` and are placed next to the code they validate so regressions surface early.
- Checkpoints provide incremental validation points between layers.
- The offline guarantee (Requirement 3) is enforced structurally in task 1.1 by omitting network permissions and network dependencies, rather than by a runtime test.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "6.1"] },
    { "id": 2, "tasks": ["2.3", "2.2", "3.2"] },
    { "id": 3, "tasks": ["2.5", "2.4"] },
    { "id": 4, "tasks": ["2.6", "2.7", "4.1"] },
    { "id": 5, "tasks": ["4.2", "4.3", "4.4"] },
    { "id": 6, "tasks": ["4.5", "4.6", "4.7", "4.8"] },
    { "id": 7, "tasks": ["4.9", "4.10"] },
    { "id": 8, "tasks": ["4.11", "6.2"] },
    { "id": 9, "tasks": ["6.3", "6.4"] },
    { "id": 10, "tasks": ["6.5", "6.6", "7.1"] },
    { "id": 11, "tasks": ["7.2", "9.1"] },
    { "id": 12, "tasks": ["9.2"] },
    { "id": 13, "tasks": ["9.3", "9.4"] },
    { "id": 14, "tasks": ["9.5", "9.6"] },
    { "id": 15, "tasks": ["10.1"] },
    { "id": 16, "tasks": ["10.2"] }
  ]
}
```
