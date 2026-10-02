# OneDrive 원본 → 빌드 폴더(OneDrive 밖) 동기화. Gradle 은 빌드 폴더에서만 돌린다.
#   OneDrive 안에서 빌드하면 동기화 락으로 build/ 삭제 실패·테스트 ClassNotFound 가 간헐적으로 난다(실측).
#   robocopy 는 한글 경로에서 exit 16 으로 실패하므로 Copy-Item 을 쓴다.
#   build/ .gradle/ 캐시는 지우지 않는다(증분 빌드 유지).
param([string]$Build = (Join-Path $env:USERPROFILE "nakinbuild"))

$src = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path $Build)) { New-Item -ItemType Directory -Path $Build | Out-Null }

$dirs = @("common", "fabric\src")
foreach ($d in $dirs) {
    $to = Join-Path $Build $d
    if (Test-Path $to) { Remove-Item -LiteralPath $to -Recurse -Force }
    $parent = Split-Path -Parent $to
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
    Copy-Item -LiteralPath (Join-Path $src $d) -Destination $parent -Recurse
}
# gradle/wrapper 는 지우지 않고 덮어쓴다 — 방금 끝난 Gradle 이 wrapper jar 를 잠시 잡고 있으면
#   폴더 삭제가 실패하고 복사까지 꼬인다(2026-09-28 실제 발생).
$wrapper = Join-Path $Build "gradle\wrapper"
if (-not (Test-Path $wrapper)) { New-Item -ItemType Directory -Path $wrapper -Force | Out-Null }
Copy-Item -Path (Join-Path $src "gradle\wrapper\*") -Destination $wrapper -Force -ErrorAction SilentlyContinue

$files = @("gradlew", "gradlew.bat", "settings.gradle", "gradle.properties", "LICENSE", "fabric\build.gradle")
foreach ($f in $files) { Copy-Item -LiteralPath (Join-Path $src $f) -Destination (Join-Path $Build $f) -Force }
Write-Output "synced -> $Build"
