$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$target = Join-Path $projectRoot 'android/app/src/main/assets/pose_landmarker_lite.task'
$expected = '59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a'
if ((Test-Path -LiteralPath $target) -and (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expected) {
    Write-Output 'Model verified'; exit 0
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
$temporary = "$target.download"
Invoke-WebRequest 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task' -OutFile $temporary
if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'Model checksum mismatch; refusing installation'
}
Move-Item -LiteralPath $temporary -Destination $target -Force
Write-Output 'Model downloaded and verified'
