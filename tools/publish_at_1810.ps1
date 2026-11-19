$ErrorActionPreference = 'Stop'
$repo = 'C:\Users\VICTUS\Desktop\Grimm Waves\Hexanima'
$taskName = 'Hexanima_Publish_1810'
$logDir = Join-Path $env:LOCALAPPDATA 'Hexanima'
$logPath = Join-Path $logDir 'publish-2026-11-19.log'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Start-Transcript -Path $logPath -Append | Out-Null

$publishSucceeded = $false
try {
    Set-Location -LiteralPath $repo
    $branch = (& git branch --show-current).Trim()
    $origin = (& git remote get-url origin).Trim()
    if ($branch -ne 'main') { throw "Se esperaba la rama main; rama actual: $branch" }
    if ($origin -ne 'https://github.com/GrimmDev-Glitch/hexanima.git') { throw "El remoto origin no coincide: $origin" }

    & git fetch origin main
    if ($LASTEXITCODE -ne 0) { throw 'git fetch origin main falló' }
    & git merge-base --is-ancestor origin/main HEAD
    if ($LASTEXITCODE -ne 0) { throw 'origin/main avanzó o divergió; se cancela para evitar sobrescribir trabajo remoto' }

    & git add -A -- .
    if ($LASTEXITCODE -ne 0) { throw 'git add falló' }
    & git reset -q HEAD -- tools/publish_at_1830.ps1
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo excluir el script de publicación' }
    & git diff --cached --check
    if ($LASTEXITCODE -ne 0) { throw 'git diff --cached --check encontró errores' }
    & git diff --cached --quiet
    if ($LASTEXITCODE -eq 0) {
        Write-Output 'No hay cambios preparados; no se crea ningún commit.'
    } else {
        & git commit -m 'Improve image metadata and model workflows; add SDXL V73 prototype'
        if ($LASTEXITCODE -ne 0) { throw 'git commit falló' }
        & git push origin main
        if ($LASTEXITCODE -ne 0) { throw 'git push origin main falló' }
        Write-Output 'Commit publicado en origin/main.'
    }
    $publishSucceeded = $true
} catch {
    Write-Output "ERROR: $($_.Exception.Message)"
    exit 1
} finally {
    Stop-Transcript | Out-Null
    if ($publishSucceeded) {
        try { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue } catch { }
        Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
    }
}
