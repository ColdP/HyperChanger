$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\native-bose-support-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$env:TEMP = $output
$env:TMP = $output
$gradleLib = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\wrapper\dists') -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
if (!$gradleLib) { throw 'The existing Kotlin compiler is required.' }
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$coroutines = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlinx-coroutines-core-jvm-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$sdkLine = Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
$sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
$android = Get-ChildItem -LiteralPath (Join-Path $sdk 'platforms') -Filter 'android.jar' -Recurse | Select-Object -Last 1 -ExpandProperty FullName
$json = Join-Path $project '.reverse-tmp\device-info-preset-tests\json-20240303.jar'
if (!(Test-Path -LiteralPath $json)) { throw 'The existing JSON test dependency is required.' }
$appClasses = Join-Path $project 'app\build\intermediates\built_in_kotlinc\debug\compileDebugKotlin\classes'
$stubClasses = Join-Path $output 'stubs'
$stubs = @(Get-ChildItem -LiteralPath "$PSScriptRoot\src" -Filter '*.java' -Recurse | ForEach-Object FullName)
& javac "-J-Djava.io.tmpdir=$output" -encoding UTF-8 -cp $android -d $stubClasses @stubs
if ($LASTEXITCODE -ne 0) { throw 'Native BOSE JVM stubs failed to compile.' }
$testClasspath = "$stubClasses;$appClasses;$stdlib;$annotations;$coroutines;$json;$android"
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "-Xfriend-paths=$appClasses" -classpath $testClasspath -d "$output\classes" "$PSScriptRoot\NativeBoseSupportTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Native BOSE regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$testClasspath" btm.m.os4.systemuihook.NativeBoseSupportTestKt
if ($LASTEXITCODE -ne 0) { throw 'Native BOSE regression checks failed.' }
foreach ($brand in @('xiaomi', 'XIAOMI', 'unknown', '')) {
    & java "-Djava.io.tmpdir=$output" "-Dnativebose.test.brand=$brand" -cp "$output\classes;$testClasspath" btm.m.os4.systemuihook.NativeBoseSupportTestKt --brand-filter
    if ($LASTEXITCODE -ne 0) { throw "Native BOSE brand checks failed for '$brand'." }
}
