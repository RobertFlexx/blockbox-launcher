param([switch]$Rebuild, [Parameter(ValueFromRemainingArguments=$true)][string[]]$LauncherArgs)
$ErrorActionPreference = "Stop"
$LauncherRoot = Split-Path -Parent $PSScriptRoot
if ($env:BLOCKBOX_LAUNCHER_JAVA_HOME) { $env:JAVA_HOME = $env:BLOCKBOX_LAUNCHER_JAVA_HOME }
if ($env:JAVA_HOME) { $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path }
$Native = Join-Path $LauncherRoot 'build\native\Blockbox Launcher\Blockbox Launcher.exe'
if (Test-Path $Native) {
    if ($Rebuild) {
        & (Join-Path $LauncherRoot 'gradlew.bat') --no-daemon -p $LauncherRoot standaloneJar packageApp
        if ($LASTEXITCODE -ne 0) { throw "Launcher build failed ($LASTEXITCODE)." }
    }
    & $Native @LauncherArgs
    exit $LASTEXITCODE
}
$Jar = Join-Path $LauncherRoot 'build\standalone\blockbox-launcher.jar'
if ($Rebuild -or -not (Test-Path $Jar)) {
    & (Join-Path $LauncherRoot 'gradlew.bat') --no-daemon -p $LauncherRoot standaloneJar
    if ($LASTEXITCODE -ne 0) { throw "Launcher build failed ($LASTEXITCODE). Use JDK 21 to build." }
}
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
& $Java '-Xms32m' '-Xmx256m' '-jar' $Jar @LauncherArgs
exit $LASTEXITCODE
