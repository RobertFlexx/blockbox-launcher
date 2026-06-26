$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LauncherRoot = Split-Path -Parent $ScriptDir
$StartMenu = Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs\Blockbox"
$LauncherCmd = Join-Path $LauncherRoot "blockbox-launcher.cmd"
$Shortcut = Join-Path $StartMenu "Blockbox Launcher.lnk"

New-Item -ItemType Directory -Force -Path $StartMenu | Out-Null

@"
@echo off
setlocal
cd /d "$LauncherRoot"
gradle run
"@ | Set-Content -Path $LauncherCmd -Encoding ASCII

$Shell = New-Object -ComObject WScript.Shell
$Link = $Shell.CreateShortcut($Shortcut)
$Link.TargetPath = $LauncherCmd
$Link.WorkingDirectory = $LauncherRoot
$Link.Description = "Launch and manage Blockbox instances"
$Link.Save()

Write-Host "Blockbox Launcher installed. Start Menu shortcut created:"
Write-Host "  $Shortcut"
