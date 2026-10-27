[CmdletBinding()]
param(
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$version = "4.12.0"
$expectedSha256 = "f71846a313388d9da667a59be1e921669fcf792d1ba264b3898253ca789bc3f0"
$downloadUri = "https://repo1.maven.org/maven2/org/opencv/opencv/$version/opencv-$version.aar"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$destinationDirectory = Join-Path $repositoryRoot "app/libs"
$destination = Join-Path $destinationDirectory "opencv-$version.aar"

if (Test-Path -LiteralPath $destination) {
    $currentSha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($currentSha256 -eq $expectedSha256) {
        Write-Host "OpenCV $version is already available at $destination"
        exit 0
    }

    if (-not $Force) {
        throw "The existing OpenCV AAR has an unexpected checksum. Re-run with -Force to replace it."
    }
}

New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
$temporaryFile = Join-Path ([System.IO.Path]::GetTempPath()) "opencv-$version-$([guid]::NewGuid()).aar"

try {
    Write-Host "Downloading OpenCV $version..."
    Invoke-WebRequest -Uri $downloadUri -OutFile $temporaryFile -UseBasicParsing

    $downloadedSha256 = (Get-FileHash -LiteralPath $temporaryFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($downloadedSha256 -ne $expectedSha256) {
        throw "OpenCV checksum verification failed. Expected $expectedSha256, got $downloadedSha256."
    }

    Move-Item -LiteralPath $temporaryFile -Destination $destination -Force
    Write-Host "OpenCV $version installed at $destination"
}
finally {
    if (Test-Path -LiteralPath $temporaryFile) {
        Remove-Item -LiteralPath $temporaryFile -Force
    }
}
