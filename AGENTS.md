# StillReader project instructions

- Read the README, architecture, threat model, and dependency policy before changing behavior. Keep the app offline with zero telemetry, accounts, advertising, and network permissions. Verify the merged and packaged release manifests.
- Use scoped read-only Android document access. Treat incoming intents, PDFs, metadata, and backup files as untrusted. Parse PDFs in the isolated renderer and keep private app data outside that process. Never modify or delete a user’s original document.
- Keep dependencies minimal and pinned. Review publication timestamps, source, licenses, transitive artifacts, advisories, integrity metadata, and locks before accepting a change. Require at least 24 hours of artifact age and verify pinned CI actions. Investigate checksum failures before updating verification metadata.
- Keep credentials, signing keys, user data, personal paths, copyrighted books, local agent state, and generated outputs out of Git. Review staged changes and run the repository scan before committing.
- Get owner approval for a third-party PDF engine, significant new dependencies, new app permissions, a dependency policy exception, major architectural or privacy changes, license changes, publication, GitHub settings changes, or destructive Git operations.
- Build and run relevant tests, lint, and security checks at each milestone. Record what passed and what remains pending. Keep docs aligned with implemented behavior.
