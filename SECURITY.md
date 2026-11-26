# Security policy

StillReader is in development and has no supported public release. Report vulnerabilities through GitHub private vulnerability reporting if the repository offers it. Otherwise, open an issue requesting a private reporting channel and omit sensitive impact or reproduction details.

The app has an empty permission list and uses Android’s application sandbox. The production PDF renderer runs in a private isolated process. It receives read-only descriptors and returns bounded page rasters and search rectangles. The app validates incoming file intents and renderer responses. Manual backup restore validates the entire JSON file before it can change the library. See the [threat model](docs/THREAT_MODEL.md) for remaining risks.

Security fixes are best effort. Keep Android itself updated because PDF parsing relies on the operating system. The app is intended to keep working offline without mandatory application updates.
