param([string]$Apk)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$package = 'io.github.santtua.stillreader'
$launcher = "$package.MainActivity"
$renderer = "$package.PdfRenderService"
if (-not $Apk) {
    $Apk = "$root/app/build/outputs/apk/release/app-release-unsigned.apk"
}

function Get-AndroidAttribute($Node, [string]$Name) {
    return $Node.GetAttribute($Name, $androidNamespace)
}

function Assert-LaunchFilters($Activity) {
    $filters = @($Activity.SelectNodes('intent-filter'))
    if ($filters.Count -ne 2) { throw 'The launcher has unexpected intent filters.' }
    $seen = @{}
    foreach ($filter in $filters) {
        $actions = @($filter.SelectNodes('action'))
        $categories = @($filter.SelectNodes('category'))
        $data = @($filter.SelectNodes('data'))
        if ($actions.Count -ne 1 -or $categories.Count -ne 1) {
            throw 'A launcher intent filter has an unexpected action or category.'
        }
        $action = Get-AndroidAttribute $actions[0] 'name'
        $category = Get-AndroidAttribute $categories[0] 'name'
        if ($seen.ContainsKey($action)) { throw 'A launcher intent filter is duplicated.' }
        $seen[$action] = $true
        if ($action -ceq 'android.intent.action.MAIN') {
            if ($category -cne 'android.intent.category.LAUNCHER' -or $data.Count -ne 0) {
                throw 'The launcher filter has unexpected data.'
            }
        } elseif ($action -ceq 'android.intent.action.VIEW') {
            if ($category -cne 'android.intent.category.DEFAULT' -or $data.Count -ne 1 -or
                (Get-AndroidAttribute $data[0] 'scheme') -cne 'content' -or
                (Get-AndroidAttribute $data[0] 'mimeType') -cne 'application/pdf') {
                throw 'The external PDF filter is broader than the approved content URI filter.'
            }
        } else {
            throw 'Found an unexpected launcher action.'
        }
    }
}

$mergedManifests = @(
    "$root/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    "$root/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
if (@($mergedManifests).Count -eq 0) { throw 'The merged release manifest is missing.' }

foreach ($path in $mergedManifests) {
    [xml]$manifest = Get-Content -LiteralPath $path -Raw
    if ($manifest.DocumentElement.GetAttribute('package') -cne $package -or
        $manifest.SelectNodes('/manifest/*[starts-with(local-name(), "uses-permission") or local-name()="permission"]').Count -ne 0) {
        throw 'The merged release manifest has an unexpected package or permission.'
    }
    $application = $manifest.SelectSingleNode('/manifest/application')
    if ((Get-AndroidAttribute $application 'allowBackup') -cne 'false' -or
        (Get-AndroidAttribute $application 'debuggable') -eq 'true') {
        throw 'The merged release manifest has an unexpected backup or debug setting.'
    }
    $components = @($application.SelectNodes('activity|activity-alias|service|receiver|provider'))
    $activity = @($components | Where-Object { $_.LocalName -ceq 'activity' })
    $service = @($components | Where-Object { $_.LocalName -ceq 'service' })
    if ($components.Count -ne 2 -or $activity.Count -ne 1 -or $service.Count -ne 1 -or
        (Get-AndroidAttribute $activity[0] 'name') -cne $launcher -or
        (Get-AndroidAttribute $activity[0] 'exported') -cne 'true' -or
        (Get-AndroidAttribute $service[0] 'name') -cne $renderer -or
        (Get-AndroidAttribute $service[0] 'exported') -cne 'false' -or
        (Get-AndroidAttribute $service[0] 'isolatedProcess') -cne 'true' -or
        $service[0].SelectNodes('intent-filter').Count -ne 0) {
        throw 'The merged release manifest has an unexpected launcher or renderer.'
    }
    Assert-LaunchFilters $activity[0]
}

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK installation.' }
$aapt = "$sdk/build-tools/36.0.0/aapt"
if ($env:OS -eq 'Windows_NT') { $aapt += '.exe' }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw 'The release APK is missing.' }

$permissions = & $aapt dump permissions $Apk
if ($LASTEXITCODE -ne 0 -or $permissions -cnotcontains "package: $package" -or
    @($permissions | Where-Object { $_ -match '^(uses-)?permission' }).Count -ne 0) {
    throw 'The release APK permission check failed.'
}
$tree = & $aapt dump xmltree $Apk AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Could not decode the packaged Android manifest.' }
$components = @($tree | Where-Object { $_ -match '^\s*E: (activity|activity-alias|service|receiver|provider)\s' })
$xmlTree = $tree -join "`n"
$activity = [regex]::Match($xmlTree, '(?m)^ {6}E: activity [^\n]*\n(?: {8,}[^\n]*\n?)*').Value
$service = [regex]::Match($xmlTree, '(?m)^ {6}E: service [^\n]*\n(?: {8,}[^\n]*\n?)*').Value
if ($components.Count -ne 2 -or
    $activity -cnotmatch ('android:name\(0x[0-9a-f]+\)="' + [regex]::Escape($launcher) + '"') -or
    $activity -notmatch 'android:exported\(0x[0-9a-f]+\)=\(type 0x12\)0xffffffff' -or
    $activity -notmatch 'android:mimeType\(0x[0-9a-f]+\)="application/pdf"' -or
    $activity -notmatch 'android:scheme\(0x[0-9a-f]+\)="content"' -or
    $service -cnotmatch ('android:name\(0x[0-9a-f]+\)="' + [regex]::Escape($renderer) + '"') -or
    $service -notmatch 'android:exported\(0x[0-9a-f]+\)=\(type 0x12\)0x0\b' -or
    $service -notmatch 'android:isolatedProcess\(0x[0-9a-f]+\)=\(type 0x12\)0xffffffff' -or
    $service -match 'E: intent-filter' -or
    -not ($tree -match 'android:allowBackup\(0x[0-9a-f]+\)=\(type 0x12\)0x0\b') -or
    ($tree -match 'android:debuggable\(0x[0-9a-f]+\)=\(type 0x12\)0xffffffff')) {
    throw 'The packaged release manifest differs from the approved launcher or renderer settings.'
}

Write-Output 'The merged release manifest and APK request zero permissions and expose only the launcher. The PDF renderer is private and isolated.'
