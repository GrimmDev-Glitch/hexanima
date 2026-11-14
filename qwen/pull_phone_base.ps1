# Copy the installed Qwen glue and DiT blocks from the main HexAnima Dev profile.
# PowerShell 7.4+ preserves native stdout bytes when redirected to a file.
param([string]$Device = '6e6dbddf')

$ErrorActionPreference = 'Stop'
$sourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$downloadScript = Get-Content -LiteralPath (Join-Path $sourceDir 'download-phone.sh') -Raw
$targetDir = Join-Path $sourceDir 'phone_base'
New-Item -ItemType Directory -Force -Path (Join-Path $targetDir 'dit') | Out-Null

function Copy-Checked([string]$relative) {
    $escapedName = [regex]::Escape($relative)
    $match = [regex]::Match($downloadScript, "echo '([0-9a-f]{64})  $escapedName'")
    if (-not $match.Success) { throw "Missing expected hash for $relative" }
    $expected = $match.Groups[1].Value.ToUpperInvariant()
    $target = Join-Path $targetDir ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
    if ((Test-Path -LiteralPath $target) -and
        (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $expected) {
        Write-Host "$relative already verified"
        return
    }
    & adb -s $Device exec-out run-as com.hexanima.npu.dev cat "files/qwen/$relative" > $target
    if ($LASTEXITCODE -ne 0) { throw "ADB failed while copying $relative" }
    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($actual -ne $expected) { throw "SHA-256 mismatch for $relative`: $actual != $expected" }
    Write-Host "$relative verified"
}

Copy-Checked 'glue.bin'
for ($i = 0; $i -lt 32; $i++) {
    Copy-Checked ('dit/blk{0:D2}.bin' -f $i)
}
