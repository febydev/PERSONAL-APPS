# Requirements Document

## Introduction

Private Media Vault is a native Android application, distributed and installed directly on a device via APK, that lets a user store sensitive images and videos (for example, screenshots of passwords) in a private, blurred-by-default vault. The application operates entirely offline with no cloud, server, or network synchronization, keeping all data local to the device. Access to the vault is protected by a PIN. Stored media is displayed in a blurred state by default; a user who has successfully authenticated with the PIN can toggle individual items between blurred and clear states. The application prioritizes privacy and simplicity of use.

## Glossary

- **Vault**: The native Android application described by this document, including all of its responsibilities for authentication, storage, and display.
- **User**: The person who has installed the Vault on their Android device and owns the stored media.
- **PIN**: A numeric passcode chosen by the User that gates access to vault contents.
- **Media Item**: A single image or video file stored within the Vault.
- **Authenticated Session**: The period that begins when the User successfully enters the correct PIN and ends when the session is terminated by timeout, backgrounding, or explicit lock.
- **Blurred State**: A rendering of a Media Item in which the original visual content is obscured and not discernible.
- **Clear State**: A rendering of a Media Item that shows the original, unobscured visual content.
- **Vault Storage**: The device-local, application-private location where the Vault keeps imported Media Items.
- **Lockout Period**: A timed interval during which PIN entry is rejected following repeated incorrect attempts.

## Requirements

### Requirement 1: First-Launch PIN Creation

**User Story:** As a User, I want to create a PIN the first time I open the app, so that only I can access my stored media.

#### Acceptance Criteria

1. WHEN the Vault is launched and no PIN has been set, THE Vault SHALL display a PIN creation screen before granting access to any vault contents.
2. THE Vault SHALL require a PIN of at least 4 numeric digits.
3. WHEN the User submits a new PIN, THE Vault SHALL require the User to re-enter the same PIN for confirmation.
4. IF the two PIN entries do not match, THEN THE Vault SHALL reject the PIN and prompt the User to re-enter both values.
5. WHEN a confirmed PIN is accepted, THE Vault SHALL store the PIN as a one-way salted hash in Vault Storage.

### Requirement 2: PIN Authentication

**User Story:** As a User, I want to enter my PIN to open the vault, so that my media stays inaccessible to anyone without the PIN.

#### Acceptance Criteria

1. WHEN the Vault is launched and a PIN has been set, THE Vault SHALL display a PIN entry screen before granting access to any vault contents.
2. WHEN the User enters a PIN that matches the stored PIN hash, THE Vault SHALL grant access to vault contents and start an Authenticated Session.
3. IF the User enters a PIN that does not match the stored PIN hash, THEN THE Vault SHALL deny access and display an incorrect-PIN message.
4. IF the User enters an incorrect PIN 5 consecutive times, THEN THE Vault SHALL begin a Lockout Period of 30 seconds during which further PIN entry is rejected.
5. WHILE a Lockout Period is active, THE Vault SHALL display the remaining lockout time in seconds.

### Requirement 3: Offline-Only Operation

**User Story:** As a User, I want the app to work fully offline, so that my private media never leaves my device.

#### Acceptance Criteria

1. THE Vault SHALL store and process all Media Items, PIN data, and application data exclusively on the local device.
2. THE Vault SHALL perform all import, storage, blur, and display operations without requiring network connectivity.
3. THE Vault SHALL exclude any feature that transmits Media Items or PIN data to an external server, cloud service, or other device.

### Requirement 4: Importing Media

**User Story:** As a User, I want to import images and videos into the vault, so that I can keep my sensitive media in one private place.

#### Acceptance Criteria

1. WHEN the User selects one or more images or videos from the device to import, THE Vault SHALL copy each selected file into Vault Storage.
2. WHEN an import completes successfully, THE Vault SHALL display the imported Media Item within the vault contents in Blurred State.
3. IF an import operation fails for a selected file, THEN THE Vault SHALL report which file failed and retain any files that imported successfully.
4. WHERE the User enables removal of originals, THE Vault SHALL delete the source copy from its original device location after the import into Vault Storage completes successfully.

### Requirement 5: Local Encrypted Storage

**User Story:** As a User, I want my stored media to be encrypted on the device, so that no other app or file browser can read it.

#### Acceptance Criteria

1. THE Vault SHALL store all Media Items in application-private Vault Storage that is not accessible to other applications.
2. THE Vault SHALL encrypt every Media Item at rest within Vault Storage.
3. WHEN a Media Item is needed for display, THE Vault SHALL decrypt the Media Item only within an Authenticated Session.
4. IF a Media Item is needed while no Authenticated Session is active, THEN THE Vault SHALL refuse to decrypt the Media Item and keep it encrypted in Vault Storage.

### Requirement 6: Blurred-by-Default Display

**User Story:** As a User, I want my media to be blurred by default, so that sensitive content is not visible at a glance.

#### Acceptance Criteria

1. WHILE the vault contents are displayed, THE Vault SHALL render each Media Item in Blurred State by default.
2. THE Vault SHALL render a Blurred State such that the original visual content of the Media Item is not discernible.
3. WHEN the Authenticated Session ends, THE Vault SHALL return every Media Item to Blurred State.

### Requirement 7: Unblurring Media

**User Story:** As a User, I want to unblur a specific image or video after entering my PIN, so that I can view its content when I need it.

#### Acceptance Criteria

1. WHILE an Authenticated Session is active, WHEN the User selects to unblur a Media Item, THE Vault SHALL render that Media Item in Clear State.
2. IF the User selects to unblur a Media Item while no Authenticated Session is active, THEN THE Vault SHALL deny the action and display the PIN entry screen.
3. WHEN a Media Item is rendered in Clear State and the Media Item is a video, THE Vault SHALL allow playback of the video.

### Requirement 8: Re-Blurring Media

**User Story:** As a User, I want to re-blur an item after viewing it, so that I can quickly hide it again.

#### Acceptance Criteria

1. WHEN the User selects to blur a Media Item that is in Clear State, THE Vault SHALL return that Media Item to Blurred State.
2. IF the blur operation fails, THEN THE Vault SHALL keep the Media Item in Clear State and report the failure to the User.

### Requirement 9: Session Auto-Lock

**User Story:** As a User, I want the vault to lock itself when I leave the app, so that my media is protected if I set the phone down.

#### Acceptance Criteria

1. WHEN the Vault moves to the background, THE Vault SHALL end the Authenticated Session and return all Media Items to Blurred State.
2. IF ending the Authenticated Session or returning Media Items to Blurred State partially fails when the Vault moves to the background, THEN THE Vault SHALL complete the remaining protective action so that no Media Item remains in Clear State.
3. WHILE the Vault is displayed in the device app switcher, THE Vault SHALL render its contents in Blurred State.
4. WHEN the User explicitly selects to lock the Vault, THE Vault SHALL end the Authenticated Session and display the PIN entry screen.

### Requirement 10: Deleting Media

**User Story:** As a User, I want to permanently delete items from the vault, so that I can remove media I no longer want to keep.

#### Acceptance Criteria

1. WHILE an Authenticated Session is active, WHEN the User selects to delete a Media Item, THE Vault SHALL request confirmation before deletion.
2. WHEN the User confirms deletion of a Media Item, THE Vault SHALL permanently remove the Media Item from Vault Storage.
3. WHEN a Media Item has been deleted, THE Vault SHALL remove the Media Item from the displayed vault contents.

### Requirement 11: Exporting Media

**User Story:** As a User, I want to export an item out of the vault when needed, so that I can use the original file elsewhere.

#### Acceptance Criteria

1. WHERE the User chooses to export a Media Item, WHILE an Authenticated Session is active, THE Vault SHALL save a decrypted copy of the Media Item to a User-selected device location.
2. IF the User attempts to export a Media Item while no Authenticated Session is active, THEN THE Vault SHALL deny the export and display the PIN entry screen.
3. WHEN the PIN entry screen is shown after a denied export, THE Vault SHALL require the User to re-initiate the export after a successful PIN entry rather than resuming the export automatically.

### Requirement 12: Changing the PIN

**User Story:** As a User, I want to change my PIN, so that I can update my passcode if I think it has been compromised.

#### Acceptance Criteria

1. WHERE the User requests to change the PIN, THE Vault SHALL require entry of the current PIN before accepting a new PIN.
2. IF the entered current PIN does not match the stored PIN hash, THEN THE Vault SHALL deny the change and display an incorrect-PIN message.
3. WHEN the User enters a valid current PIN and a confirmed new PIN, THE Vault SHALL replace the stored PIN hash with the hash of the new PIN.
