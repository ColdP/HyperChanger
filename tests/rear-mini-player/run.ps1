$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\rear-mini-player-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\wrapper\dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
if (!$gradleLib) { throw 'The existing Kotlin compiler is required.' }
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$sdkLine = Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
$sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$android = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Filter 'android.jar' -Recurse | Select-Object -Last 1 -ExpandProperty FullName
Add-Type -AssemblyName System.IO.Compression.FileSystem
$serviceAar = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\caches\modules-2\files-2.1\io.github.libxposed\service') -Filter '*.aar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
$serviceJar = Join-Path $output 'xposed-service.jar'
$archive = [System.IO.Compression.ZipFile]::OpenRead($serviceAar)
try { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $serviceJar, $true) } finally { $archive.Dispose() }
$appClasses = Join-Path $project 'app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes'
$testClasspath = "$appClasses;$stdlib;$annotations;$android;$serviceJar"
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "-Xfriend-paths=$appClasses" -classpath $testClasspath -d "$output\classes" "$PSScriptRoot\RearMiniPlayerSettingsTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Rear mini player regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$testClasspath" btm.m.os4.systemuihook.RearMiniPlayerSettingsTestKt
if ($LASTEXITCODE -ne 0) { throw 'Rear mini player regression checks failed.' }
