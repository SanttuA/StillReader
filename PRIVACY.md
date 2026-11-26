# Privacy

StillReader has no app networking, telemetry, accounts, ads, or remote crash reporting. The reader opens a user-selected PDF read-only. StillReader saves an app-private document copy when the user chooses to import or save a copy.

The library stores document references, display names, covers, last-opened dates, page positions, zoom, reading comfort settings, and layout preferences in private app storage. The current document grant is read-only, and Android can retain it if the provider supports persistent grants. Removing a book clears its library data, releases the app’s persistent grant, and deletes any private copy. The source PDF stays in its original location.

The app disables Android Auto Backup and excludes app storage from cloud backup, device-to-device transfer, and supported cross-platform transfer rules. Manufacturer and Android behavior can vary.

Document search runs on the device. Search queries and highlights stay in memory for the session. Library and document search fields ask the installed keyboard to disable personalized learning.

The file picker requests locally available files. External file managers and document providers control how they supply their own documents. Choose a device-local file to read offline.

Manual backups contain titles, last-opened dates, reading positions, and per-book appearance settings in a local JSON file you choose. They omit document contents, file URIs, and access grants. Android does not automatically back up this data for StillReader. The app’s settings can delete library rows, reading history, and private PDF copies. Android app settings can clear all app-managed data, including preferences. Android, device manufacturers, and document providers have their own data practices. [Backup format](docs/BACKUP_FORMAT.md)
