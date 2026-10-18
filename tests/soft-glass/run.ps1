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
$json = Join-Path $output 'json-20240303.jar'
if (!(Test-Path -LiteralPath $json)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $json
}
if ((Get-FileHash -LiteralPath $json -Algorithm SHA256).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') {
    throw 'Unexpected JSON test dependency checksum.'
}
$zxing = Get-ChildItem -LiteralPath (Join-Path $project '.reverse-tmp\build-env\gradle\caches\modules-2\files-2.1\com.google.zxing\core') -Filter 'core-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
if (!$zxing) { throw 'The existing app ZXing dependency is required for QR checks.' }
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$stdlib;$annotations;$json;$zxing" -d "$output\classes" "$project\app\src\main\java\btm\m\os4\systemuihook\SoftGlassParameters.kt" "$project\app\src\main\java\btm\m\os4\systemuihook\SoftGlassPresets.kt" "$project\app\src\main\java\btm\m\os4\systemuihook\PresetQrPayload.kt" "$PSScriptRoot\SoftGlassParametersTest.kt"
if ($LASTEXITCODE -ne 0) { throw 'Kotlin regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$stdlib;$json;$zxing" btm.m.os4.systemuihook.SoftGlassParametersTestKt
if ($LASTEXITCODE -ne 0) { throw 'Soft glass regression checks failed.' }
