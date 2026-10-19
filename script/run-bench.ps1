<#
.SYNOPSIS
    Runs command-deck natively on the Windows test bench.

.DESCRIPTION
    The bench equivalent of cms/src/docker/bin/startup.sh. Both driver plugins are Windows-only,
    so this is the only deployment that drives the hardware (doc/dev/backend/driver-jars.md).

    Activates the `bench` profile group, which resolves to `docker`; bench-specific settings are
    parameters here rather than a bench properties file.

    PropertiesLauncher loads the drivers from -DriversPath at launch; replacing one needs a
    restart, not a rebuild.

.PARAMETER Jar
    The boot jar. Build it with: ./gradlew :command-deck:bootJar -Pvaadin.productionMode=true

.PARAMETER DriversPath
    Directory holding dscusb.jar and usbmodbus.jar. Startup refuses, naming the missing one, if
    either is absent. Defaults to the repo's drivers/, shared with bootRun and
    :command-deck:driverPluginTest.

.PARAMETER KeyStorePassword
    Keystore password, resolved like startup.sh, first hit wins: this parameter / the KEY_STORE_PASSWORD
    environment variable, then the file "<KeyStorePath>.password" (rupfizupfi.p12.password) beside the
    keystore. Only when a NEW keystore is about to be created and neither exists is a random password
    generated and saved to that file, readable by the current user only. There is no built-in default.
    An existing keystore with no password available stops the script.

.EXAMPLE
    $env:DB_URL = 'jdbc:postgresql://cloud-host:5432/rupfizupfi'
    ./script/run-bench.ps1 -DbPasswordFile C:\deck\.secrets\db-password.txt
#>
[CmdletBinding()]
param(
    [string]$Jar = 'command-deck/build/libs/command-deck-application.jar',
    [string]$DriversPath = 'drivers',
    [string]$DbUrl = $env:DB_URL,
    [string]$DbPasswordFile = $env:DB_PASSWORD_FILE,
    [string]$KeyStorePath = "$env:USERPROFILE\keystore\rupfizupfi.p12",
    [string]$KeyStorePassword = $env:KEY_STORE_PASSWORD
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

# Created empty so loader.path does not skip it; HardwareModeCheck then reports the missing jars.
if (-not (Test-Path $driversPath)) {
    New-Item -ItemType Directory -Path $driversPath | Out-Null
    Write-Warning "Created empty $driversPath. Put dscusb.jar and usbmodbus.jar there or startup will refuse."
}

# Keystore password, first hit wins: parameter/KEY_STORE_PASSWORD, then the file beside the keystore
# (written by an earlier self-sign). Deliberately no built-in default: a well-known one protects nothing.
$keyStorePasswordFile = "$KeyStorePath.password"
if (-not $KeyStorePassword -and (Test-Path $keyStorePasswordFile)) {
    $KeyStorePassword = (Get-Content -Raw -Path $keyStorePasswordFile).Trim()
}

# Self-signed fallback, as in the container entrypoint: the bench is reached over a trusted LAN.
if (-not (Test-Path $KeyStorePath)) {
    $keyStoreDir = Split-Path -Parent $KeyStorePath
    if (-not (Test-Path $keyStoreDir)) {
        New-Item -ItemType Directory -Path $keyStoreDir | Out-Null
    }
    if (-not $KeyStorePassword) {
        Write-Host "No keystore password configured; generating a random one and saving it to $keyStorePasswordFile (current user only)."
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $generated = ''
        try {
            while ($generated.Length -lt 24) {
                $bytes = New-Object byte[] 32
                $rng.GetBytes($bytes)
                $generated += ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', '')
            }
        } finally { $rng.Dispose() }
        $KeyStorePassword = $generated.Substring(0, 24)

        # Create empty, lock the ACL down, then write the secret, so it is never readable by others.
        # Inheritance is removed and only the current user (by SID, so localised names are no issue) is
        # granted access; SYSTEM and Administrators lose their inherited entries, as with chmod 600.
        if (Test-Path $keyStorePasswordFile) { Remove-Item -Force $keyStorePasswordFile }
        New-Item -ItemType File -Path $keyStorePasswordFile | Out-Null
        $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
        & icacls $keyStorePasswordFile /inheritance:r /grant:r "*${sid}:(R,W)" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Remove-Item -Force $keyStorePasswordFile
            throw "icacls failed with exit $LASTEXITCODE; refusing to write the keystore password to an unrestricted file."
        }
        [System.IO.File]::WriteAllText($keyStorePasswordFile, $KeyStorePassword, (New-Object System.Text.UTF8Encoding($false)))
    }
    Write-Host "Keystore not found. Creating a self-signed one at $KeyStorePath..."
    & keytool -genkeypair -alias rupfizupfi -keyalg RSA -keysize 2048 -storetype PKCS12 `
        -keystore $KeyStorePath -storepass $KeyStorePassword `
        -dname "CN=rupfizupfi.ch, OU=IT, O=Rupfizupfi, L=Bern, S=Bern, C=CH"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit $LASTEXITCODE" }
}
elseif (-not $KeyStorePassword) {
    throw ("$KeyStorePath exists but no keystore password is available. Set KEY_STORE_PASSWORD (or pass -KeyStorePassword), " +
        "or put the password in $keyStorePasswordFile. One-time fix for a keystore made by the old default: " +
        "set KEY_STORE_PASSWORD=changeit once (it is not saved; or save it to that file). " +
        "Alternatively delete the keystore to regenerate it with a random password (browsers will see a new certificate).")
}

if (-not $DbPasswordFile) {
    throw 'DB_PASSWORD_FILE is not set. Pass -DbPasswordFile or set the environment variable; it must point at a file containing the database password.'
}
if (-not (Test-Path $DbPasswordFile)) {
    throw "Database password file not found at $DbPasswordFile"
}
$env:DB_PASSWORD = (Get-Content -Raw -Path $DbPasswordFile).Trim()

# No local fallback: bench results must land in the authoritative dataset (OQ-61).
if (-not $DbUrl) {
    throw 'DB_URL is not set. Point it at the cloud Postgres; there is deliberately no local default on the bench.'
}

$env:SPRING_PROFILES_ACTIVE = 'bench'
# Not in application-docker.properties: the deck container shares that profile and runs simulated.
# HardwareModeCheck separately refuses simulation under the 'bench' profile.
$env:DECK_HARDWARE_MODE = 'real'
$env:DB_URL = $DbUrl
$env:KEY_STORE_PATH = $KeyStorePath
$env:KEY_STORE_PASSWORD = $KeyStorePassword
$env:LOADER_PATH = $driversPath

Write-Host "Starting command-deck on the bench"
Write-Host "  profile      : bench (resolves to docker)"
Write-Host "  hardware     : real"
Write-Host "  drivers      : $driversPath"
# Shown so a reachable-but-wrong database URL is visible; it carries no credentials.
Write-Host "  database     : $DbUrl"

& java -jar $jarPath
exit $LASTEXITCODE
