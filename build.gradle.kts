buildscript {
    // Keep AGP's certificate-processing dependencies on the CVE-2026-8763 fix.
    configurations.named("classpath") {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85"
        )
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
