param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$StageRoot = $env:TEMP
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'Set JAVA_HOME to a Java 25 JDK or pass -JavaHome.' }
$env:JAVA_HOME = $JavaHome
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) { throw 'Java 25 JDK not found' }
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'libs\carpet.jar'))) {
    throw 'Copy your Minecraft 26.2 Carpet mod to native-bot/libs/carpet.jar first.'
}
# Use a fresh source tree so removed files cannot survive from an earlier build.
$stage = Join-Path $StageRoot ('eun-courier-' + [Guid]::NewGuid().ToString('N'))
if ($stage -match '[^\x00-\x7F]') { throw 'Pass -StageRoot with an ASCII-only path for Gradle test workers.' }
New-Item -ItemType Directory -Force $stage | Out-Null
Write-Host "Build directory: $stage"
foreach ($name in @('build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','src','gradle','libs')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $stage -Recurse -Force
}
& (Join-Path $stage 'gradlew.bat') -p $stage build --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed' }
$dest = Join-Path $PSScriptRoot 'build\libs'
New-Item -ItemType Directory -Force $dest | Out-Null
Get-ChildItem -LiteralPath (Join-Path $stage 'build\libs') -Filter '*.jar' |
    Copy-Item -Destination $dest -Force
Get-FileHash -LiteralPath (Join-Path $dest 'eun-native-courier-0.1.0.jar') -Algorithm SHA256
