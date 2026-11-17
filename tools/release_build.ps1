# Signed release APK. Run in a PowerShell window: powershell -ExecutionPolicy Bypass -File tools\release_build.ps1
# The keystore must be at %USERPROFILE%\.hexanima-signing\release.keystore (copy it there from the backup; a new key cannot update the installed app).
$ErrorActionPreference = 'Stop'
$ks = Join-Path $env:USERPROFILE '.hexanima-signing\release.keystore'
if (-not (Test-Path $ks)) { Write-Host "Falta la clave de firma: $ks" -ForegroundColor Red; exit 1 }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$wslRepo = (wsl.exe -d Ubuntu -- wslpath -a ($repo -replace '\','/')).Trim()
wsl.exe -d Ubuntu -- bash "$wslRepo/tools/release_build.sh"
if (Test-Path (Join-Path $repo 'app\hexanima-release.apk')) { Write-Host 'LISTO: app\hexanima-release.apk' -ForegroundColor Green } else { Write-Host 'La compilacion fallo.' -ForegroundColor Red }
