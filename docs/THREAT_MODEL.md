# Threat model

| Risk | Current control or follow-up |
| --- | --- |
| Malicious PDFs and parser faults | Only the isolated service opens the PDF parser. Its input is a read-only descriptor. The app exposes no PDF link or embedded-action controls. The PDF engine depends on Android security updates. |
| Resource exhaustion | One renderer request at a time, bounded bitmap sizes and memory cache, bounded search queries and retained matches, bounded file-opening work, and timeouts on opening, rendering, and searching. Private copies are streamed with a size limit and a free-space reserve. A native PDF call may outlive a timed-out session. |
| Unauthorized component use | The release exports one Activity for launcher and PDF view intents. PDF opening accepts a content URI with a read grant. The private service verifies the IPC caller and validates inputs. The client validates response metadata before mapping pixels and checks match counts and normalized rectangles before drawing search highlights. |
| Unauthorized local access | The renderer has no access to private app data. Android Auto Backup is disabled and storage is excluded from transfer rules. A compromised system can defeat app isolation. |
| Inaccessible or unsupported sources | A missing grant or unavailable provider can be relinked through the picker. Users can explicitly copy a readable stream into private storage. The picker asks for locally available files, and external providers control their behavior. |
| Untrusted document names | The app strips control and formatting characters and caps names before storing and showing them. Database lookup values use bound parameters. |
| Malicious backups | Import accepts a local user-selected JSON document through a bounded background worker. The strict UTF-8 parser rejects unsupported versions, duplicate IDs, unexpected fields and values outside the schema. Confirmation and a database transaction follow validation. Restore imports metadata. Relinking uses the system picker. An exported backup contains readable titles and viewing history. |
| Dependency or build infrastructure compromise | Exclusive repository sourcing, artifact age and integrity checks, dependency locks, wrapper hashes, and read-only CI. The build host and SDK remain trust inputs. |
| Accidental public disclosure | Review staged changes and binary assets, scan for secrets and personal files, and keep signing keys outside Git. |

Production PDF handling still needs broader compatibility and resource testing before personal distribution.
