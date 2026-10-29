$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\portal-ocr-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\wrapper\dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$json = Join-Path $project '.reverse-tmp\soft-glass-tests\json-20240303.jar'
$appClasses = Join-Path $project 'app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes'
$testClasspath = "$appClasses;$stdlib;$annotations;$json"
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "-Xfriend-paths=$appClasses" -classpath $testClasspath -d "$output\classes" "$PSScriptRoot\PortalOcrTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Portal OCR regression checks failed to compile.' }
$model = Join-Path $project '.reverse-tmp\portal-ocr-analysis\portal-ocr-eng-chi_sim-jpn.zip'
$modelArgs = @()
if (Test-Path -LiteralPath $model) { $modelArgs = @($model) }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$testClasspath" btm.m.os4.systemuihook.PortalOcrTestKt @modelArgs
if ($LASTEXITCODE -ne 0) { throw 'Portal OCR regression checks failed.' }
