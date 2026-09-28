$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$signingFile = Join-Path $env:USERPROFILE '.android\yybpoints-ahdaolma-signing.json'
if (-not (Test-Path -LiteralPath $signingFile)) {
    throw "Missing release signing configuration: $signingFile"
}
$signing = Get-Content -LiteralPath $signingFile -Raw | ConvertFrom-Json
foreach ($field in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $signing.$field) { throw "Missing release signing field: $field" }
}
$env:YYB_RELEASE_STORE_FILE = $signing.storeFile
$env:YYB_RELEASE_STORE_PASSWORD = $signing.storePassword
$env:YYB_RELEASE_KEY_ALIAS = $signing.keyAlias
$env:YYB_RELEASE_KEY_PASSWORD = $signing.keyPassword
try {
    & (Join-Path $projectRoot 'gradlew.bat') assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
} finally {
    Remove-Item Env:YYB_RELEASE_STORE_FILE -ErrorAction SilentlyContinue
    Remove-Item Env:YYB_RELEASE_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:YYB_RELEASE_KEY_ALIAS -ErrorAction SilentlyContinue
    Remove-Item Env:YYB_RELEASE_KEY_PASSWORD -ErrorAction SilentlyContinue
}
