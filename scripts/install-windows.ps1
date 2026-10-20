param([string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'Programs\Blockbox Launcher'), [switch]$Rebuild)
$ErrorActionPreference = "Stop"
$LauncherRoot = Split-Path -Parent $PSScriptRoot
if ($env:BLOCKBOX_LAUNCHER_JAVA_HOME) { $env:JAVA_HOME = $env:BLOCKBOX_LAUNCHER_JAVA_HOME }
if ($env:JAVA_HOME) { $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path }
$Source = Join-Path $LauncherRoot 'build\native\Blockbox Launcher'
if ($Rebuild -or -not (Test-Path (Join-Path $Source 'Blockbox Launcher.exe'))) {
    & (Join-Path $LauncherRoot 'gradlew.bat') --no-daemon -p $LauncherRoot packageApp
    if ($LASTEXITCODE -ne 0) { throw "Launcher packaging failed ($LASTEXITCODE). Install JDK 21 to build." }
}
$Stage = "$InstallDir.staging-$([guid]::NewGuid().ToString('N'))"
$Backup = "$InstallDir.previous"
if (Test-Path $Backup) { throw "An earlier install backup exists at $Backup. Move it aside before retrying." }
try {
    New-Item -ItemType Directory -Force -Path $Stage | Out-Null
    Copy-Item -Path (Join-Path $Source '*') -Destination $Stage -Recurse -Force
    if (Test-Path $InstallDir) { Move-Item -LiteralPath $InstallDir -Destination $Backup }
    try { Move-Item -LiteralPath $Stage -Destination $InstallDir }
    catch { if (Test-Path $Backup) { Move-Item -LiteralPath $Backup -Destination $InstallDir }; throw }
    $StartMenu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Blockbox'
    New-Item -ItemType Directory -Force -Path $StartMenu | Out-Null
    $Shell = New-Object -ComObject WScript.Shell
    $Link = $Shell.CreateShortcut((Join-Path $StartMenu 'Blockbox Launcher.lnk'))
    $Link.TargetPath = Join-Path $InstallDir 'Blockbox Launcher.exe'
    $Link.WorkingDirectory = $InstallDir
    $Link.Description = 'Manage and play Blockbox instances'
    $Link.Save()
    if (Test-Path $Backup) { Remove-Item -LiteralPath $Backup -Recurse -Force }
} finally { if (Test-Path $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force } }
Write-Host "Installed to $InstallDir. Launch from the Start Menu; Java and Gradle are not needed at startup."
