<#
.SYNOPSIS
    Cross-language typecheck gate: regenerates the Hilla TypeScript client from
    Java bytecode, then runs `tsc --noEmit` over both modules' frontends.

.DESCRIPTION
    Hilla generates src/main/frontend/generated/ from the compiled @BrowserCallable
    services, so a backend signature change (renamed method, changed parameter type,
    added DTO field, changed nullability) surfaces as a TypeScript compile error at
    the call site. That makes `tsc --noEmit` a Java-to-TypeScript contract check --
    the cheapest real verification available to this repo.

    It is a *contract* check, not a *behaviour* check: it says nothing about runtime
    behaviour, auth enforcement, WebSocket wiring, or rendering.

    The generation step is `hillaGenerate`, run once per module. It has one nasty
    failure mode worth knowing about: it boots a Spring AOT context to discover
    @BrowserCallable classes, and when that discovery comes back empty it reports
    BUILD SUCCESSFUL and *deletes* the client instead of writing it. So the generated
    client is verified after generation, and regenerated once if it came back empty,
    rather than trusting Gradle's exit code.

.PARAMETER SkipGenerate
    Skip the Gradle generation step and typecheck whatever is already in
    src/main/frontend/generated/. This is the fast path (seconds instead of a minute)
    and is correct whenever no Java has changed since the last generation. The
    sanity check on the generated client still runs.

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

# ---------------------------------------------------------------- generation ---

function Get-Gradlew {
    $gradlew = Join-Path $repoRoot 'gradlew.bat'
    if (-not (Test-Path $gradlew)) {
        throw "Gradle wrapper not found at $gradlew"
    }
    return $gradlew
}

function Invoke-GenerateModule {
    <#
        Runs hillaGenerate for one module. `hillaGenerate` is the task that turns the
        compiled @BrowserCallable classes into src/main/frontend/generated/ -- it boots
        a Spring AOT context (see build/hilla-aot-report.txt) to discover them.

        One invocation per module, never both at once. Requesting
        ':cms:hillaGenerate :command-deck:hillaGenerate' together fails at
        configuration time with "Could not determine the dependencies of task
        ':cms:jar' > Task with name 'generate' not found in project ':cms'" -- the
        Hilla plugin does not survive being asked to generate for two projects in a
        single build.

        vaadinPrepareFrontend is deliberately NOT run here. It would refresh the Flow
        scaffolding (vaadin.ts, vite-devmode.ts, jar-resources/), but it also performs
        a frontend install using *npm* despite `pnpmEnable = true` in build.gradle --
        it deletes pnpm-lock.yaml and writes package-lock.json. Far too destructive
        for a gate meant to run on every change, and the scaffolding it produces is
        already committed. Only hillaGenerate produces the Java-to-TypeScript contract
        this gate exists to check.
    #>
    param(
        [string]$Module,
        [switch]$Force
    )

    $gradlew = Get-Gradlew
    $tasks = @(":${Module}:hillaGenerate", '--quiet')
    if ($Force) {
        # Outputs were wiped or are suspect; make Gradle redo the work rather than
        # reporting UP-TO-DATE against a directory we have already rejected.
        $tasks += '--rerun'
    }

    & $gradlew @tasks
    return $LASTEXITCODE
}

function Invoke-Generate {
    <#
        Generates, verifies, and retries once on a wipe.

        The retry is the point. When the AOT endpoint scan comes back empty --
        observed on a cold Gradle daemon, and when another build is running against
        the same project concurrently -- hillaGenerate reports BUILD SUCCESSFUL and
        *deletes* the client it was supposed to write. A single attempt would then
        fail the gate for a reason that has nothing to do with the code under test,
        and a gate that cries wolf gets switched off. Re-running has recovered it
        every time it has been seen.
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
        Guards the silent-wipe failure mode described in the header comment: Gradle
        reports success, but the generated directory came back empty. Without this
        check the gate would then "pass" purely because there was nothing left that
        referenced the missing endpoints, or fail with a confusing cascade of
        module-not-found errors.
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

    foreach ($candidate in @('node_modules/.bin/tsc.cmd', 'node_modules/.bin/tsc.CMD')) {
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
            # No local install -- fall back to pnpm, which resolves the pinned
            # typescript from the module's devDependencies.
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

        # Invoke-Generate verifies its own output, so this check only has to cover
        # the skipped path: typechecking against a client that was never generated
        # (or was left wiped by an earlier run) would produce a wall of
        # module-not-found noise that looks nothing like the real problem.
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
