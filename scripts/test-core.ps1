$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $projectRoot 'build/core-test'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'core/src') -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
& javac --release 11 -encoding UTF-8 -d $outputDir $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& java -cp $outputDir fitness.mobile.core.CoreTest (Join-Path $projectRoot 'core/src/test/fixtures/geometry.tsv')
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
