$ErrorActionPreference = 'Stop'
$environmentPath = Join-Path $PSScriptRoot '.venv'
$pythonPath = Join-Path $environmentPath 'Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonPath)) {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        & py -3 -m venv $environmentPath
    } elseif (Get-Command python -ErrorAction SilentlyContinue) {
        & python -m venv $environmentPath
    } else {
        throw 'Python missing. Install Python 3.10-3.12, then run setup.ps1 again.'
    }
    if ($LASTEXITCODE -ne 0) { throw 'Python environment creation failed.' }
}
& $pythonPath -m pip install -r (Join-Path $PSScriptRoot 'requirements.txt')
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
Write-Host 'Setup complete. Connect your phone and double-click start.cmd.'
