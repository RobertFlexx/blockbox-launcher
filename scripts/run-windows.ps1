$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LauncherRoot = Split-Path -Parent $ScriptDir

if (-not [string]::IsNullOrWhiteSpace($env:BLOCKBOX_LAUNCHER_JAVA_HOME)) {
  $env:JAVA_HOME = $env:BLOCKBOX_LAUNCHER_JAVA_HOME
  $env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + $env:Path
}

Set-Location $LauncherRoot
$GradleArgs = @("--no-daemon")
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
  $GradleArgs += "-Dorg.gradle.java.home=$env:JAVA_HOME"
}
$GradleArgs += "run"
gradle @GradleArgs
