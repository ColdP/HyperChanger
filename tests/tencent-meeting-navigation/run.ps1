$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\tencent-meeting-navigation-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$compiler = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\wrapper\dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1
if (!$compiler) { throw 'The local Kotlin compiler bundled with Gradle was not found.' }
$gradleLib = $compiler.DirectoryName
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$source = Join-Path $project 'app\src\main\java\btm\m\liquidglass\hook'
$testSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'stubs') -Filter '*.kt' | Select-Object -ExpandProperty FullName)
$testSources += @("$source\TencentMeetingNavigation.kt", "$PSScriptRoot\TencentMeetingNavigationTest.kt")
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib;$annotations" -d "$output\classes" @testSources
if ($LASTEXITCODE -ne 0) { throw 'Tencent Meeting navigation regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$stdlib" btm.m.liquidglass.hook.TencentMeetingNavigationTestKt
if ($LASTEXITCODE -ne 0) { throw 'Tencent Meeting navigation regression checks failed.' }
