# Architecture

StillReader is a single native Android module with a Kotlin Activity and platform views. Android 15 is the minimum. The build compiles and targets API 37.

## Documents and library

The system picker requests a device-local, openable PDF. File managers can open the same Activity with an application/pdf content URI and a read grant. The app checks the URI and opens a read-only, seekable file descriptor on a bounded background worker. The renderer receives the descriptor alone.

The library takes a read-only persisted grant when the provider offers one. A temporary grant can be used to read until the session ends. The user can choose to copy a PDF into StillReader’s private storage, including from a non-seekable provider that supplies a readable stream. Copies are staged, limited to 1 GiB and available disk space, and kept after the renderer validates the document. The original file is opened read-only. [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)

A private SQLite database stores one book per selected URI with its sanitized display name, optional cover, page count, date last opened, and reading state. PdfRenderer provides no reliable author metadata for this reader. The shelf supports title search, recent or alphabetical order, list and grid layouts, and a resume button. Books whose source cannot be opened can be relinked through the picker. Removing a book deletes its app-owned copy before deleting the library entry and releasing its grant. If the copy cannot be deleted, the entry remains available for retry.

The app saves position, offset within the current page, zoom, and horizontal pan periodically and when leaving the reader. It saves the theme, optional page recoloring, page fit, margin crop, and screen-awake preference separately for each library book. The Activity saves the current reader state and any in-progress open, copy, or relink request across recreation. Library information and copies remain on the device.

## Manual backup and storage

The system picker creates a new local JSON file for export and supplies read access for restore. Backup file I/O runs off the main and database threads. The app validates the entire input within fixed byte and book limits before showing the restore confirmation. A single SQLite transaction applies validated rows by stable portable ID. Relinking a restored entry requires selecting a PDF through the system picker. [Backup format](BACKUP_FORMAT.md)

App settings can clear cover thumbnails or delete library rows, saved progress, and app-private PDF copies. Deleting the library releases its persistent read grants. Reading defaults live in private preferences and apply when opening a new book.

## PDF rendering

The reader binds explicitly to a private isolated Android service for the current document. The service checks the caller UID and descriptor, then uses platform PdfRenderer on one worker thread. It returns a page count and bounded page rasters through read-only shared memory. The client validates request IDs, worker UID, dimensions, and buffer length before mapping a raster. The service receives no URI, path, or app data. [Android PDF framework](https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer)

A native scrolling list requests visible pages one at a time and caches up to 32 MiB of rasters in memory. Each raster is limited to 2,048 pixels per edge and 8 MiB. The app generates a small cached cover when page one is rendered. Zoom may scale existing pixels while a sharper raster is prepared. A renderer timeout or disconnect closes the current session. Native calls may run until the system reclaims the isolated process.

## Search and reading comfort

The isolated renderer searches one page at a time using the platform `PdfRenderer.Page.searchText` API. The UI prioritizes visible page renders between searches. The service uses text presence to distinguish image-only pages from pages with embedded text. It sends bounded match rectangles and a text-presence flag over IPC. It keeps extracted text inside the renderer. Search retains up to 256 matches per page, 1,000 per document, and eight rectangles per match. Cancellation drops queued searches and ignores a pending result. Native calls can finish or time out after cancellation.

Reading themes tint the reader controls. Original PDF colors are the default. Optional GPU recoloring primarily changes neutral tones, including grayscale photos and illustrations. Fit-to-page and fit-to-width work with manual zoom. Manual cropping hides an equal 5% or 10% on each edge and temporarily shows the whole page when a selected match lies outside the visible area.
