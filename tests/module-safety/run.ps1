$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\module-safety-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\wrapper\dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$sdkLine = Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
$sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$android = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Filter 'android.jar' -Recurse | Select-Object -Last 1 -ExpandProperty FullName
$appClasses = Join-Path $project 'app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes'
$testClasspath = "$appClasses;$stdlib;$annotations;$android"
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "-Xfriend-paths=$appClasses" -classpath $testClasspath -d "$output\classes" "$PSScriptRoot\ModuleSafetyTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Safety checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$testClasspath" btm.m.os4.systemuihook.ModuleSafetyTestKt $output
if ($LASTEXITCODE -ne 0) { throw 'Safety checks failed.' }
$transportTest = Join-Path $project 'tests\native-home\HomeLauncherNativeTransportTest.kt'
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "-Xfriend-paths=$appClasses" -classpath $testClasspath -d "$output\transport-classes" $transportTest
if ($LASTEXITCODE -ne 0) { throw 'Transport checks failed to compile.' }
$gitCommand = (Get-Command git.exe).Source
$bash = Join-Path (Split-Path (Split-Path $gitCommand)) 'bin\bash.exe'
if (!(Test-Path -LiteralPath $bash)) { throw 'Git Bash is required for transport checks.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\transport-classes;$testClasspath" btm.m.os4.systemuihook.HomeLauncherNativeTransportTestKt $bash $output
if ($LASTEXITCODE -ne 0) { throw 'Transport checks failed.' }
