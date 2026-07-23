param(
    [string]$Target = (Join-Path $PSScriptRoot "..\vendor\LibreChat")
)

$ErrorActionPreference = "Stop"
$baseline = "8e5ef1fb31e9d63b735c089b21cbc82c50acce46"
$resolvedTarget = [System.IO.Path]::GetFullPath($Target)
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))

if (-not $resolvedTarget.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Target must stay inside the current workspace."
}

if (Test-Path -LiteralPath $resolvedTarget) {
    throw "Target already exists: $resolvedTarget"
}

git clone --filter=blob:none --no-checkout https://github.com/danny-avila/LibreChat.git $resolvedTarget
git -C $resolvedTarget fetch --depth=1 origin $baseline
git -C $resolvedTarget checkout --detach $baseline

Write-Host "LibreChat baseline checked out: $baseline"
