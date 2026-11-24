param(
    [string]$Sdk = $env:ANDROID_SDK_ROOT,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$BuildToolsVersion = '35.0.0',
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable exited with code $LASTEXITCODE"
    }
}

if (!$Sdk) { $Sdk = $env:ANDROID_HOME }
if (!$Sdk -or !$Jdk) {
    throw 'Set ANDROID_SDK_ROOT and JAVA_HOME, or pass -Sdk and -Jdk.'
}

$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
$tools = Join-Path $Sdk "build-tools/$BuildToolsVersion"
$platform = Join-Path $Sdk 'platforms/android-35/android.jar'
$aapt2 = Join-Path $tools 'aapt2.exe'
$d8 = Join-Path $tools 'd8.bat'
$zipalign = Join-Path $tools 'zipalign.exe'
$apksigner = Join-Path $tools 'apksigner.bat'
foreach ($requiredPath in @($platform, $aapt2, $d8, $zipalign, $apksigner)) {
    if (!(Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw "Missing $requiredPath" }
}

$source = Join-Path $PSScriptRoot 'app/src/main'
$manifest = Join-Path $source 'AndroidManifest.xml'
if (!$Destination) {
    $version = ([xml](Get-Content -LiteralPath $manifest -Raw)).DocumentElement.GetAttribute(
        'versionName', 'http://schemas.android.com/apk/res/android')
    if (!$version) { throw 'Missing versionName in AndroidManifest.xml.' }
    $Destination = Join-Path $PSScriptRoot "out/SplashSkip-$version.apk"
}
$local = Join-Path $PSScriptRoot '.local'
$work = Join-Path $local ([Guid]::NewGuid().ToString('N'))
$generated = Join-Path $work 'generated'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
$platformClasses = Join-Path $work 'platform-classes'
New-Item -ItemType Directory -Path $generated, $classes, $dex, $platformClasses -Force | Out-Null
$resources = Join-Path $work 'compiled-resources.zip'
$unsigned = Join-Path $work 'unsigned.apk'
$aligned = Join-Path $work 'aligned.apk'
$classArchive = Join-Path $work 'classes.jar'

Invoke-Checked $aapt2 @('compile', '--dir', (Join-Path $source 'res'), '-o', $resources)
Invoke-Checked $aapt2 @('link', '-I', $platform, '--manifest', $manifest,
    '--java', $generated, '-o', $unsigned, $resources)

# Compile against unpacked stubs so javac does not need to resolve the platform jar's real path on Windows.
Invoke-Checked 'tar.exe' @('-xf', $platform, '-C', $platformClasses)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $source 'java'), $generated -Recurse -Filter '*.java' -File |
    Select-Object -ExpandProperty FullName)
Invoke-Checked 'javac.exe' (@('-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '-source', '8', '-target', '8',
    '-Xlint:-options', '-encoding', 'UTF-8',
    '-classpath', $platformClasses, '-d', $classes) + $sources)

$testSource = Join-Path $PSScriptRoot 'app/src/test/java/app/splashskip/android/RuleEngineTest.java'
if (Test-Path -LiteralPath $testSource) {
    $testClasses = Join-Path $work 'tests'
    New-Item -ItemType Directory -Path $testClasses | Out-Null
    Invoke-Checked 'javac.exe' @('-source', '8', '-target', '8', '-Xlint:-options', '-encoding', 'UTF-8',
        '-classpath', $classes, '-d', $testClasses, $testSource)
    Invoke-Checked 'java.exe' @('-ea', '-classpath', "$testClasses;$classes", 'app.splashskip.android.RuleEngineTest')
}

Invoke-Checked 'jar.exe' @('--create', '--file', $classArchive, '-C', $classes, '.')
Invoke-Checked $d8 @('--lib', $platform, '--min-api', '26', '--output', $dex, $classArchive)
Invoke-Checked 'jar.exe' @('--update', '--file', $unsigned, '-C', $dex, 'classes.dex')
Invoke-Checked $zipalign @('-f', '4', $unsigned, $aligned)

$keystore = Join-Path $local 'debug.keystore'
if (!(Test-Path -LiteralPath $keystore)) {
    Invoke-Checked 'keytool.exe' @('-genkeypair', '-keystore', $keystore, '-storetype', 'JKS',
        '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey',
        '-dname', 'CN=SplashSkip Debug,O=Local Development,C=US', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000')
}

$apkPath = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path (Split-Path -Parent $apkPath) -Force | Out-Null
Invoke-Checked $apksigner @('sign', '--ks', $keystore, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', $apkPath, $aligned)
Invoke-Checked $apksigner @('verify', '--verbose', $apkPath)
Invoke-Checked $zipalign @('-c', '4', $apkPath)
Get-FileHash -Algorithm SHA256 -LiteralPath $apkPath
