<#
.SYNOPSIS
    Runs command-deck natively on the Windows test bench.

.DESCRIPTION
    The bench equivalent of cms/src/docker/bin/startup.sh, and the only way the deck reaches the
    hardware: both driver plugins are Windows-only (a Win32 vendor DLL and the Thesycon USBIO
    kernel driver), so the Linux container cannot drive the machine at all -- it runs the
    tests-and-simulations deployment instead. Detail in doc/03-backend/driver-jars.md.

    There is deliberately no `bench` properties file. This activates the `bench` profile *group*,
    which resolves to `docker` -- one deployment configuration for both the container and this
    script, with the machine-specific parts supplied as environment variables. Anything that would
    have gone in a bench-only properties file belongs in a parameter here instead.

    The drivers are NOT baked into the jar. PropertiesLauncher loads them from -DriversPath at
    launch, so replacing a driver is a file copy and a restart, never a rebuild.

.PARAMETER Jar
    The boot jar. Build it with: ./gradlew :command-deck:bootJar -Pvaadin.productionMode=true

.PARAMETER DriversPath
    Directory holding dscusb.jar and usbmodbus.jar. Both must be present or startup refuses and
    names the one that is missing -- it never falls back to a simulator.

    Defaults to the repo's drivers/, which is the same directory bootRun and
    :command-deck:driverPluginTest read. One plugin directory per machine, whichever way the deck
    is started.

.PARAMETER StorageRoot
    Where `~` in the stored paths resolves to, i.e. the parent of `breaktester/`. Defaults to the
    user profile, which is what the settings assume.

.EXAMPLE
    $env:DB_URL = 'jdbc:postgresql://cloud-host:5432/rupfizupfi'
    ./script/run-bench.ps1 -DbPasswordFile C:\deck\.secrets\db-password.txt
#>
[CmdletBinding()]
param(
    [string]$Jar = 'command-deck/build/libs/command-deck-application.jar',
    [string]$DriversPath = 'drivers',
    [string]$StorageRoot = $env:USERPROFILE,
    [string]$DbUrl = $env:DB_URL,
    [string]$DbPasswordFile = $env:DB_PASSWORD_FILE,
    [string]$KeyStorePath = "$env:USERPROFILE\keystore\rupfizupfi.p12",
    [string]$KeyStorePassword = $(if ($env:KEY_STORE_PASSWORD) { $env:KEY_STORE_PASSWORD } else { 'changeit' })
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot

function Resolve-Against-Repo {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return (Join-Path $repoRoot $PathValue)
}

$jarPath = Resolve-Against-Repo $Jar
$driversPath = Resolve-Against-Repo $DriversPath

if (-not (Test-Path $jarPath)) {
    throw "Boot jar not found at $jarPath. Build it with: ./gradlew :command-deck:bootJar -Pvaadin.productionMode=true"
}

# Empty is a valid state for the directory itself -- HardwareModeCheck is what reports which
# provider is missing, and it names the jar. Creating it keeps loader.path from silently skipping
# a path that does not exist.
if (-not (Test-Path $driversPath)) {
    New-Item -ItemType Directory -Path $driversPath | Out-Null
    Write-Warning "Created empty $driversPath. Put dscusb.jar and usbmodbus.jar there or startup will refuse."
}

# Same self-signed fallback as the container entrypoint, and for the same reason: the bench is
# reached over a trusted local network, so an auto-signed cert is the intended operating mode.
if (-not (Test-Path $KeyStorePath)) {
    $keyStoreDir = Split-Path -Parent $KeyStorePath
    if (-not (Test-Path $keyStoreDir)) {
        New-Item -ItemType Directory -Path $keyStoreDir | Out-Null
    }
    Write-Host "Keystore not found. Creating a self-signed one at $KeyStorePath..."
    & keytool -genkeypair -alias rupfizupfi -keyalg RSA -keysize 2048 -storetype PKCS12 `
        -keystore $KeyStorePath -storepass $KeyStorePassword `
        -dname "CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit $LASTEXITCODE" }
}

if (-not $DbPasswordFile) {
    throw 'DB_PASSWORD_FILE is not set. Pass -DbPasswordFile or set the environment variable; it must point at a file containing the database password.'
}
if (-not (Test-Path $DbPasswordFile)) {
    throw "Database password file not found at $DbPasswordFile"
}
$env:DB_PASSWORD = (Get-Content -Raw -Path $DbPasswordFile).Trim()

# No fallback to a local database: the deck must write into the authoritative dataset, and one
# that quietly wrote somewhere else would be worse than one that refuses to start (OQ-61).
if (-not $DbUrl) {
    throw 'DB_URL is not set. Point it at the cloud Postgres; there is deliberately no local default on the bench.'
}

$env:SPRING_PROFILES_ACTIVE = 'bench'
# Declared here rather than in application-docker.properties, which that profile shares with the
# deck container -- and the container runs simulated. Each deployment states its own mode; an
# environment variable outranks every properties file. Unset, the base default is 'real' too, so
# this is belt-and-braces rather than the only thing standing between a bench and a simulator: the
# 'bench' profile above is what HardwareModeCheck refuses to simulate under.
$env:DECK_HARDWARE_MODE = 'real'
$env:DB_URL = $DbUrl
$env:KEY_STORE_PATH = $KeyStorePath
$env:KEY_STORE_PASSWORD = $KeyStorePassword
$env:DECK_STORAGE_ROOT = $StorageRoot
$env:LOADER_PATH = $driversPath

Write-Host "Starting command-deck on the bench"
Write-Host "  profile      : bench (resolves to docker)"
Write-Host "  hardware     : real"
Write-Host "  drivers      : $driversPath"
Write-Host "  storage root : $StorageRoot"
# Logged without credentials so a typo pointing at a reachable-but-wrong database is visible.
Write-Host "  database     : $DbUrl"

& java -jar $jarPath
exit $LASTEXITCODE
