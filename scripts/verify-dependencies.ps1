param(
    [string]$CandidateMetadata
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot

function Get-ArtifactSource([string]$Group, [string]$Module, [string]$Version, [string]$File) {
    $base = 'https://repo.maven.apache.org/maven2'
    if ($Group -match '^(com\.android|androidx|com\.google\.testing)(\.|$)') {
        $base = 'https://dl.google.com/dl/android/maven2'
    }
    return "$base/$($Group.Replace('.', '/'))/$Module/$Version/$File"
}

function Read-VerifiedArtifacts([string]$Path) {
    [xml]$metadata = Get-Content -LiteralPath $Path -Raw
    if ($metadata.SelectSingleNode("//*[local-name()='verify-metadata']").InnerText -ne 'true') {
        throw 'Gradle verification must cover dependency metadata.'
    }
    $components = @($metadata.SelectNodes("//*[local-name()='component']"))
    if ($components.Count -eq 0 -or $metadata.SelectNodes("//*[local-name()='trust']").Count -ne 0) {
        throw 'A populated verification file without broad trust exceptions is required.'
    }
    foreach ($component in $components) {
        $group = $component.GetAttribute('group')
        $module = $component.GetAttribute('name')
        $version = $component.GetAttribute('version')
        if ($version -match '(?i)snapshot|latest\.|[+\[\]()]') {
            throw "Unpinned component version: ${group}:${module}:$version"
        }
        foreach ($artifact in $component.SelectNodes("*[local-name()='artifact']")) {
            $file = $artifact.GetAttribute('name')
            $digests = @($artifact.SelectNodes("*[local-name()='sha256']"))
            if ($digests.Count -ne 1 -or $digests[0].GetAttribute('value') -cnotmatch '^[0-9a-f]{64}$' -or
                $digests[0].SelectNodes("*[local-name()='also-trust']").Count -ne 0) {
                throw "Expected one reviewed SHA-256 for $file."
            }
            [pscustomobject]@{
                Artifact = "${group}:${module}:${version}/$file"
                Source = Get-ArtifactSource $group $module $version $file
                Sha256 = $digests[0].GetAttribute('value')
            }
        }
    }
}

function Assert-MinimumAge([string]$Utc, [string]$Artifact) {
    $published = [DateTimeOffset]::ParseExact(
        $Utc, 'yyyy-MM-ddTHH:mm:ssZ',
        [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::AssumeUniversal
    )
    if ($published -gt [DateTimeOffset]::UtcNow.AddHours(-24)) {
        throw "Publication evidence is less than 24 hours old for $Artifact."
    }
}

if ($CandidateMetadata) {
    $candidate = @(Read-VerifiedArtifacts $CandidateMetadata)
    # Native aapt2 is resolved by host OS, so review Linux and macOS on Windows too.
    foreach ($item in @($candidate | Where-Object { $_.Artifact -match '/aapt2-.+-windows\.jar$' })) {
        foreach ($platform in @('linux', 'osx')) {
            $candidate += [pscustomobject]@{
                Artifact = $item.Artifact.Replace('-windows.jar', "-$platform.jar")
                Source = $item.Source.Replace('-windows.jar', "-$platform.jar")
                Sha256 = ''
            }
        }
    }

    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    $hash = [Security.Cryptography.SHA256]::Create()
    try {
        $records = foreach ($entry in $candidate) {
            $response = $client.GetAsync($entry.Source, [Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
            try {
                if (-not $response.IsSuccessStatusCode -or -not $response.Content.Headers.LastModified) {
                    throw "Missing publisher artifact or Last-Modified header for $($entry.Artifact)."
                }
                $timestamp = $response.Content.Headers.LastModified.UtcDateTime.ToString(
                    'yyyy-MM-ddTHH:mm:ssZ', [Globalization.CultureInfo]::InvariantCulture
                )
                Assert-MinimumAge $timestamp $entry.Artifact
                $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
                try {
                    $digest = [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
                } finally {
                    $stream.Dispose()
                }
                if ($entry.Sha256 -and $entry.Sha256 -cne $digest) {
                    throw "Publisher checksum differs from the candidate for $($entry.Artifact)."
                }
                [pscustomobject]@{
                    Artifact = $entry.Artifact
                    Source = $entry.Source
                    PublishedUtc = $timestamp
                    Sha256 = $digest
                }
            } finally {
                $response.Dispose()
            }
        }
        $records | Sort-Object Artifact | ConvertTo-Csv -NoTypeInformation
    } finally {
        $hash.Dispose()
        $client.Dispose()
    }
    return
}

$wrapperDigest = (Get-FileHash -Algorithm SHA256 -LiteralPath "$root/gradle/wrapper/gradle-wrapper.jar").Hash
if ($wrapperDigest -ne '76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3') {
    throw 'The checked-in Gradle wrapper JAR differs from the reviewed publisher hash.'
}
Assert-MinimumAge '2025-10-01T00:00:00Z' 'Gradle wrapper JAR'
$wrapperProperties = Get-Content -LiteralPath "$root/gradle/wrapper/gradle-wrapper.properties"
if ($wrapperProperties -cnotcontains 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.2-bin.zip' -or
    $wrapperProperties -cnotcontains 'distributionSha256Sum=971fcd7150ca4ba04852c0b2ca0d2d0ab9468e14b03473eab19528809914c76b') {
    throw 'The pinned Gradle distribution or its publisher checksum changed.'
}
Assert-MinimumAge '2026-11-20T00:00:00Z' 'Gradle 9.9.2 distribution'

$approvedAction = 'actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1'
Assert-MinimumAge '2026-07-22T00:00:00Z' $approvedAction
$workflows = Get-ChildItem -LiteralPath "$root/.github/workflows" -File | Where-Object { $_.Extension -in '.yml', '.yaml' }
foreach ($workflow in $workflows) {
    foreach ($line in Get-Content -LiteralPath $workflow.FullName) {
        if ($line -match '^\s*(?:-\s*)?uses\s*:\s*(.+?)\s*(?:#.*)?$' -and $Matches[1].Trim() -cne $approvedAction) {
            throw 'Found a workflow action outside the reviewed full-SHA allowlist.'
        }
    }
}

$accepted = @(Import-Csv -LiteralPath "$root/gradle/dependency-evidence.csv")
$evidence = @{}
foreach ($entry in $accepted) {
    if ($evidence.ContainsKey($entry.Artifact)) {
        throw "Duplicate publication record for $($entry.Artifact)."
    }
    $evidence[$entry.Artifact] = $entry
}
$artifacts = @(Read-VerifiedArtifacts "$root/gradle/verification-metadata.xml")
foreach ($artifact in $artifacts) {
    $entry = $evidence[$artifact.Artifact]
    if (-not $entry -or $entry.Source -cne $artifact.Source -or $entry.Sha256 -cne $artifact.Sha256) {
        throw "Missing matching publisher evidence for $($artifact.Artifact)."
    }
    Assert-MinimumAge $entry.PublishedUtc $artifact.Artifact
    $evidence.Remove($artifact.Artifact)
}
if ($evidence.Count -ne 0) {
    throw 'The publisher inventory contains entries absent from Gradle verification.'
}
Write-Output "Verified wrapper pins and publication evidence for $($artifacts.Count) Gradle artifacts."
