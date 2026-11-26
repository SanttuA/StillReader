# Reading backup format

Use **Settings → Export reading backup** to save a UTF-8 JSON file through the Android system picker. Keep the file in a location you trust. It contains book titles, last-opened dates, positions, and per-book reading preferences in plain text.

On another installation, choose **Settings → Restore reading backup**, select the file, and confirm. Restore uses the book's portable ID. A matching library entry keeps its existing document access and takes the backed-up reading state. A new entry shows **Locate PDF** so you can select its source again. Restore never opens a document from a backup and never grants file access. If a destination library already contains the same PDF under a different portable ID, the picker reports the duplicate when you try to link it. Restoring before linking those PDFs avoids this conflict.

## Version 1

```json
{
  "format": "StillReader",
  "version": 1,
  "books": [
    {
      "id": "a0123456789abcdef0123456789abcde",
      "title": "Sample.pdf",
      "pageCount": 80,
      "lastOpened": 1770000000000,
      "position": [15, 15, 0.25, 1.5, 0.5],
      "comfort": ["SEPIA", true, false, true, 5]
    }
  ]
}
```

- `id` is a stable, random 32-character lowercase hexadecimal value. It contains no source-file location.
- `title` is the display name, limited to 160 characters after sanitization. `pageCount` is the number of pages. `lastOpened` is a UTC Unix timestamp in milliseconds.
- `position` contains the zero-based current page and scroll anchor, the fractional offset within the anchor page, zoom from 1 to 4, and horizontal pan from 0 to 1.
- `comfort` contains the theme, page recoloring, fit-to-page, keep-screen-awake, and margin crop. Themes are `SYSTEM`, `LIGHT`, `DARK`, `SEPIA`, or `HIGH_CONTRAST`. The crop percentage per edge is 0, 5, or 10.
- The parser accepts up to 4 MiB and 5,000 entries per file. It rejects duplicate IDs, duplicate or unknown fields, unsupported versions, and invalid values before any database changes. Restore applies the validated records in one transaction.

Exports omit PDF contents, private copies, thumbnails, paths, document URIs, access grants, search terms, and app-wide preferences.
