# Dependencies

## Acceptance policy

Accept only exact stable versions. Review the publication time of each new artifact or action revision, the release notes, license, maintenance, known vulnerabilities, and transitive changes. Require publisher evidence that the artifact has been available for at least 24 hours. An absent timestamp or unexpected checksum requires investigation and owner review for any exception. Routine upgrades should favor mature releases.

Google Maven exclusively serves the reviewed Android, AndroidX, and Google testing groups. Maven Central serves other Maven groups. Gradle locking pins resolvable app configurations and strict verification covers the build, runtime, and test artifacts. Review all checksum changes before accepting them.

## Direct tool and library inventory

| Dependency | Version | Source | License | Purpose |
| --- | --- | --- | --- | --- |
| Gradle distribution | 9.9.2 | Gradle releases | Apache-2.0 | Pinned build tool |
| Gradle wrapper JAR | 9.1.0 / 9.0.0 shared binary | Gradle release checksums | Apache-2.0 | Checked-in bootstrap from the Android Studio template |
| Android Gradle Plugin | 9.5.2 | Google Maven | Apache-2.0 | Build, built-in Kotlin, packaging and lint |
| JDK | 25 | Local JDK or GitHub runner toolchain | GPL-2.0 with Classpath Exception for OpenJDK | Build JVM |
| Android platform and Build Tools | API 37.0 and 36.0.0 | Android SDK Manager | Android SDK license terms | Compile and inspect APKs |
| Bouncy Castle provider, PKIX, and utility modules | 1.85 | Maven Central | Bouncy Castle Licence | Transitive build and lint dependencies pinned at the security fix |
| Kotlin standard library | 2.2.10 | Maven Central | Apache-2.0 | Transitive APK runtime selected by AGP |
| JetBrains Annotations | 13.0 | Maven Central | Apache-2.0 | Transitive annotations from Kotlin |
| JUnit | 4.13.2 | Maven Central | EPL-1.0 | Host tests |
| AndroidX Test JUnit | 1.1.5 | Google Maven | Apache-2.0 | Instrumentation test integration |
| AndroidX Test Runner | 1.5.2 | Google Maven | Apache-2.0 | Run device tests |
| GitHub Checkout | 7.0.1, full commit SHA in CI | GitHub releases | MIT | Checkout with credential persistence disabled |

Exact transitive versions and artifact hashes are recorded in the Gradle lock and verification files. Publisher URLs and UTC age evidence are recorded per artifact in gradle/dependency-evidence.csv. The component inventory records direct and transitive purpose and upstream license metadata. The two Sonatype parent POM entries describe build metadata and publish no license field. [Maven component inventory](../gradle/dependency-inventory.csv) · [Publisher timestamps and checksums](../gradle/dependency-evidence.csv) The APK uses only the Kotlin runtime, its annotations dependency, and framework Android APIs.

## Security review

The AGP and lint classpaths pin Bouncy Castle 1.85 to address critical CVE-2026-8763 and the related parser and PKIX advisories. The Maven Central artifacts are older than 24 hours. Review this pin when AGP updates its own dependencies. [Bouncy Castle certificate advisory](https://github.com/advisories/GHSA-9pwp-9qqc-pr26) · [ASN.1 advisory](https://github.com/advisories/GHSA-qp49-qgx5-5m26) · [PKIX advisory](https://github.com/advisories/GHSA-wg6q-6289-32hp)

The OSV review also identified build-time advisories for Guava, Commons Lang, Apache HttpClient, jose4j, JDOM, and the Kotlin Gradle Plugin. These dependencies are outside the packaged app’s runtime. Review their fixes when updating the build stack. [Guava](https://github.com/advisories/GHSA-xxph-c9ww-hj94) · [Commons Lang](https://github.com/advisories/GHSA-j288-q9x7-2f5v) · [HttpClient](https://github.com/advisories/GHSA-7r82-7xv7-xcpj) · [jose4j](https://github.com/advisories/GHSA-3677-xxcr-wjqv) · [JDOM](https://github.com/advisories/GHSA-2363-cqg2-863c) · [Kotlin Gradle plugin](https://github.com/advisories/GHSA-r937-wjx7-w2jp)

Gradle 9.9.2 includes the critical version-catalog fix from 9.9.1 and the earlier fix for repository fallback after TLS errors. Gradle published the distribution on November 19, 2026. The bootstrap JAR and the distribution have separate published SHA-256 values. Strict dependency verification and exclusive repository content remain in effect. [Gradle 9.9.2 release notes](https://docs.gradle.org/9.9.2/release-notes.html) · [Gradle checksums](https://gradle.org/release-checksums/)

Gradle’s build classpath resolves Kotlin `kotlin-reflect` and `kotlin-stdlib` 2.4.21 from Maven Central. Their four resolved artifacts were published on October 8, 2026. The release descriptors identify the Apache 2.0 license. These build components are separate from the Kotlin runtime selected by AGP for the app. [Kotlin 2.4.21 release](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21)
