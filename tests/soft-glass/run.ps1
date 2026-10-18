$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\soft-glass-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\wrapper\dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
if (!$gradleLib) { throw 'A Kotlin compiler is required in the existing reverse-tmp build environment.' }
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$env:TEMP = $output
$env:TMP = $output
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib;$annotations" -d "$output\classes" "$project\app\src\main\java\btm\m\os4\systemuihook\SoftGlassParameters.kt" "$PSScriptRoot\SoftGlassParametersTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Kotlin regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$stdlib" btm.m.os4.systemuihook.SoftGlassParametersTestKt
if ($LASTEXITCODE -ne 0) { throw 'Soft glass regression checks failed.' }
