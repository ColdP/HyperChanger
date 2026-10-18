$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\restart-recommendation-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\wrapper\dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$sdkLine = Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
$sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$android = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Filter 'android.jar' -Recurse | Select-Object -Last 1 -ExpandProperty FullName
$json = Join-Path $project '.reverse-tmp\soft-glass-tests\json-20240303.jar'
if (!(Test-Path -LiteralPath $json)) { throw 'Run the soft-glass regression setup first to provide the JSON dependency.' }
$appClasses = Join-Path $project 'app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes'
$testClasspath = "$appClasses;$stdlib;$annotations;$android;$json"
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "-Xfriend-paths=$appClasses" -classpath $testClasspath -d "$output\classes" "$PSScriptRoot\RestartRecommendationTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Restart recommendation regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$testClasspath" btm.m.os4.systemuihook.RestartRecommendationTestKt
if ($LASTEXITCODE -ne 0) { throw 'Restart recommendation regression checks failed.' }
