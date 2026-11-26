# StillReader

StillReader is a lightweight, privacy-first Android PDF reader in development for offline use, accessibility, and long-term independence from external services. The app uses no accounts, tracking, advertisements, or internet permission.

## Status

StillReader opens device-local PDFs on Android 15 (API 35) and later. Use the system file picker or open a PDF from a compatible file manager. The reader scrolls vertically and provides page navigation, page jumps, pinch and double-tap zoom, and zoom buttons. Files are opened read-only and rendered in an isolated Android process.

The local library shows covers when available, supports title search and recent or alphabetical sorting, and can switch between list and grid views. It remembers each book’s page, scroll position, and zoom. Open a PDF to keep a read-only link when the provider supports it, or choose **Import a copy** to keep a private local copy.

Use **Search** to find embedded PDF text and jump to highlighted results. Image-only documents need OCR, which is outside the 1.0 scope. **Reading settings** offers light, dark, sepia, high-contrast, and system themes with optional page recoloring. It also includes fit-to-page, margin cropping, screen-awake, and full-screen reading. StillReader saves comfort settings for library books and displays original PDF colors by default.

Android 15 and API 37 emulator suites passed in airplane mode. The Android 15 checks covered backup and restore through the system picker, navigating a 301-page PDF, reader controls at 200% font scale with enlarged display density, and accessibility focus and zoom with TalkBack active. Earlier API 37 tests also verified reading state after a full emulator reboot. Physical-device and broader PDF compatibility testing are pending.

The library’s **Settings** menu lets you set reading defaults, choose a reading orientation, manage cached covers, and export or restore reading history through the Android document picker. A backup stores reading metadata in a portable JSON file. Keep your PDF files separately. [Backup format and restore steps](docs/BACKUP_FORMAT.md)

## Build and install

See [Building](docs/BUILDING.md) for Android Studio and Gradle setup, debug installation, tests, and local release signing. This is primarily a personal project. Maintenance and support are best effort.

## Project policies

- [Privacy](PRIVACY.md) describes local data and operating system boundaries.
- [Architecture](docs/ARCHITECTURE.md) and the [threat model](docs/THREAT_MODEL.md) describe the platform PDF engine and isolation design.
- [Dependencies](docs/DEPENDENCIES.md) records the supply-chain policy and dependency inventory.
- [Security](SECURITY.md) explains how to report a vulnerability.

Contributions should stay within the project scope and include relevant verification. Use synthetic or redistribution-approved document fixtures and follow [AGENTS.md](AGENTS.md). Original code is available under the [MIT license](LICENSE). See [third-party notices](THIRD_PARTY_NOTICES.md) for external components.
