<#
.SYNOPSIS
    Cross-language typecheck gate: regenerates the Hilla TypeScript client from
    Java bytecode, then runs `tsc --noEmit` over both modules' frontends.

.DESCRIPTION
    Hilla generates src/main/frontend/generated/ from the compiled @BrowserCallable
    services, so a backend signature change surfaces as a TypeScript error at the call
    site. This checks the Java-to-TypeScript contract, not runtime behaviour.

    `hillaGenerate` boots a Spring AOT context to discover @BrowserCallable classes;
    when discovery comes back empty it reports BUILD SUCCESSFUL and deletes the client.
    The generated client is therefore verified after generation and regenerated once
    if empty.

.PARAMETER SkipGenerate
    Skip the Gradle generation step and typecheck whatever is already in
    src/main/frontend/generated/. Valid when no Java has changed since the last
    generation. The generated-client check still runs.

.EXAMPLE
    ./script/typecheck.ps1
    Full gate: regenerate, then typecheck both modules.

.EXAMPLE
    ./script/typecheck.ps1 -SkipGenerate
    Frontend-only change: typecheck against the existing generated client.

.NOTES
    Exit code is 0 only if every module typechecks cleanly.
#>
[CmdletBinding()]
param(
    [switch]$SkipGenerate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$modules = @('cms', 'command-deck')

function Write-Section {
    param([string]$Text)
    Write-Host ''
    Write-Host "== $Text" -ForegroundColor Cyan
}

function Test-IsWindows {
    # $IsWindows only exists in PowerShell 7+; 5.1 runs only on Windows, so its absence means
    # Windows. Test-Path avoids the StrictMode error on the undefined variable. No ?: here:
    # the package.json scripts run this under `powershell` 5.1, where a ternary fails to parse.
    if (-not (Test-Path variable:IsWindows)) { return $true }
    return [bool]$IsWindows
}

# ---------------------------------------------------------------- generation ---

function Get-Gradlew {
    if (Test-IsWindows) { $name = 'gradlew.bat' } else { $name = 'gradlew' }
    $gradlew = Join-Path $repoRoot $name
    if (-not (Test-Path $gradlew)) {
        throw "Gradle wrapper not found at $gradlew"
    }
    return $gradlew
}

function Invoke-GenerateModule {
    <#
        Runs hillaGenerate for one module (AOT scan report: build/hilla-aot-report.txt).

        One module per Gradle invocation: requesting both in one build fails at
        configuration time with "Task with name 'generate' not found in project ':cms'".

        vaadinPrepareFrontend is not run here: it installs with npm despite
        `pnpmEnable = true`, deleting pnpm-lock.yaml and writing package-lock.json.
        Its scaffolding (generated-flow-imports.js, app-shell-imports.js,
        vaadin-react.js) is untracked, so a fresh clone needs
        `./gradlew :MODULE:vaadinPrepareFrontend` once first, as
        .github/workflows/build.yml does.
    #>
    param(
        [string]$Module,
        [switch]$Force
    )

    $gradlew = Get-Gradlew
    $tasks = @(":${Module}:hillaGenerate", '--quiet')
    if ($Force) {
        # Otherwise Gradle reports UP-TO-DATE against the rejected output.
        $tasks += '--rerun'
    }

    & $gradlew @tasks
    return $LASTEXITCODE
}

function Invoke-Generate {
    <#
        Generates, verifies, and retries once on a wipe. The empty AOT scan has been
        seen on a cold Gradle daemon and with a concurrent build on the same project;
        a re-run has recovered it each time.
    #>
    foreach ($module in $modules) {
        Write-Section "Generating Hilla client for $module"

        $code = Invoke-GenerateModule -Module $module
        if ($code -ne 0) {
            throw "Gradle generation failed for $module (exit $code)"
        }

        $problem = Test-GeneratedClient -Module $module
        if ($null -eq $problem) { continue }

        Write-Host "   $problem" -ForegroundColor Yellow
        Write-Host '   Generated client came back empty; retrying once.' -ForegroundColor Yellow

        $code = Invoke-GenerateModule -Module $module -Force
        if ($code -ne 0) {
            throw "Gradle generation failed for $module on retry (exit $code)"
        }

        $problem = Test-GeneratedClient -Module $module
        if ($null -ne $problem) {
            throw "[$module] $problem`n" +
                  "       This persisted across a retry. Recover with:`n" +
                  "       ./gradlew :${module}:hillaGenerate --rerun`n" +
                  '       and check build/hilla-aot-report.txt for the AOT endpoint scan.'
        }
    }
}

function Test-GeneratedClient {
    <#
        Returns a problem description if the client was wiped (see header), else $null.
    #>
    param([string]$Module)

    $generated = Join-Path $repoRoot "$Module/src/main/frontend/generated"
    if (-not (Test-Path $generated)) {
        return "generated/ is missing entirely ($generated)"
    }

    $endpoints = Join-Path $generated 'endpoints.ts'
    if (-not (Test-Path $endpoints)) {
        return 'generated/endpoints.ts is missing -- the Hilla client was not generated'
    }

    $services = @(Get-ChildItem -Path $generated -Filter '*Service.ts' -File)
    if ($services.Count -eq 0) {
        return 'generated/ contains no *Service.ts -- hillaGenerate found zero @BrowserCallable classes'
    }

    return $null
}

# ----------------------------------------------------------------- typecheck ---

function Resolve-Tsc {
    param([string]$ModulePath)

    # On Windows the extensionless shim is a sh script PowerShell cannot run; use tsc.cmd.
    if (Test-IsWindows) {
        $candidates = @('node_modules/.bin/tsc.cmd', 'node_modules/.bin/tsc.CMD')
    } else {
        $candidates = @('node_modules/.bin/tsc')
    }

    foreach ($candidate in $candidates) {
        $path = Join-Path $ModulePath $candidate
        if (Test-Path $path) { return $path }
    }
    return $null
}

function Invoke-Typecheck {
    param([string]$Module)

    $modulePath = Join-Path $repoRoot $Module
    $tsc = Resolve-Tsc -ModulePath $modulePath

    Push-Location $modulePath
    try {
        if ($null -eq $tsc) {
            # No local install: pnpm resolves the module's pinned typescript.
            $output = & pnpm exec tsc --noEmit -p tsconfig.json
        } else {
            $output = & $tsc --noEmit -p tsconfig.json
        }
        $code = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $lines = @($output | Where-Object { $_ -match 'error TS' })
    return [pscustomobject]@{
        Module     = $Module
        ExitCode   = $code
        ErrorCount = $lines.Count
        Output     = $output
    }
}

# ---------------------------------------------------------------------- main ---

$results = @()
$hardFailure = $null

try {
    if ($SkipGenerate) {
        Write-Section 'Skipping generation (-SkipGenerate)'

        # Invoke-Generate verifies its own output; this covers the skipped path.
        foreach ($module in $modules) {
            $problem = Test-GeneratedClient -Module $module
            if ($null -ne $problem) {
                throw "[$module] $problem`n" +
                      '       Re-run without -SkipGenerate to regenerate it.'
            }
        }
    } else {
        Invoke-Generate
    }

    foreach ($module in $modules) {
        Write-Section "Typechecking $module"
        $result = Invoke-Typecheck -Module $module
        if ($result.ErrorCount -gt 0 -or $result.ExitCode -ne 0) {
            $result.Output | ForEach-Object { Write-Host $_ }
        }
        $results += $result
    }
} catch {
    $hardFailure = $_.Exception.Message
}

Write-Host ''
Write-Host '---------------- typecheck summary ----------------'

if ($null -ne $hardFailure) {
    Write-Host "FAIL  generation  $hardFailure" -ForegroundColor Red
    Write-Host '--------------------------------------------------'
    exit 1
}

$failed = 0
foreach ($result in $results) {
    if ($result.ExitCode -eq 0 -and $result.ErrorCount -eq 0) {
        Write-Host ("PASS  {0,-13} 0 type errors" -f $result.Module) -ForegroundColor Green
    } else {
        $failed++
        Write-Host ("FAIL  {0,-13} {1} type error(s)" -f $result.Module, $result.ErrorCount) -ForegroundColor Red
    }
}
Write-Host '--------------------------------------------------'

if ($failed -gt 0) {
    exit 1
}
exit 0
