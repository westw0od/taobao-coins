param(
    [string]$Serial,
    [string]$Adb,
    [int]$AndroidUser = 0,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONUNBUFFERED = '1'
$pythonPath = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) {
    throw 'Python environment missing. Run setup.ps1 first.'
}
$arguments = @('-u', (Join-Path $PSScriptRoot 'main.py'), '--android-user', $AndroidUser)
if ($Serial) { $arguments += @('--serial', $Serial) }
if ($Adb) { $arguments += @('--adb', $Adb) }
if ($Check) { $arguments += '--check' }
$previousErrorActionPreference = $ErrorActionPreference
try {
    # Windows PowerShell otherwise turns native stderr into terminating errors.
    $ErrorActionPreference = 'Continue'
    & $pythonPath @arguments 2>&1 | ForEach-Object { $_.ToString() }
    $runExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($runExitCode -ne 0) {
    Write-Host "Run exited with code $runExitCode. See logs for details."
}
exit $runExitCode
