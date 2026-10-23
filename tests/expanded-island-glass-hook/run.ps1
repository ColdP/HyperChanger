$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\expanded-island-glass-hook-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$env:TEMP = $output
$env:TMP = $output
$gradleLib = Get-ChildItem -LiteralPath "$project\.reverse-tmp\build-env\gradle\wrapper\dists" -Filter 'kotlin-compiler-embeddable-*.jar' -Recurse | Select-Object -First 1 -ExpandProperty DirectoryName
if (!$gradleLib) { throw 'The existing Kotlin compiler is required.' }
$stdlib = Get-ChildItem -LiteralPath $gradleLib -Filter 'kotlin-stdlib-*.jar' | Select-Object -First 1 -ExpandProperty FullName
$annotations = Get-ChildItem -LiteralPath $gradleLib -Filter 'annotations-*.jar' | Select-Object -First 1 -ExpandProperty FullName
Add-Type -AssemblyName System.IO.Compression.FileSystem
$api = Get-ChildItem -LiteralPath "$project\.reverse-tmp\build-env\gradle\caches\modules-2\files-2.1\io.github.libxposed\api" -Filter '*.aar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
$apiJar = Join-Path $output 'xposed-api.jar'
$archive = [System.IO.Compression.ZipFile]::OpenRead($api)
try { [System.IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $apiJar, $true) } finally { $archive.Dispose() }
$source = Get-Content -LiteralPath "$project\app\src\main\java\btm\m\os4\systemuihook\HyperSystemUiModule.kt" -Raw
$start = $source.IndexOf('    private fun installDynamicIslandGlassBlurHook(')
$end = $source.IndexOf('    private fun installDynamicIslandExpandedMaterialHook(', $start)
if ($start -lt 0 -or $end -le $start) { throw 'Production installer not found.' }
$test = (Get-Content -LiteralPath "$PSScriptRoot\HookContractTest.kt" -Raw).Replace('    // PRODUCTION_INSTALLER', $source.Substring($start, $end - $start))
Set-Content -LiteralPath "$output\HookContractTest.kt" -Value $test -Encoding utf8
Set-Content -LiteralPath "$output\View.kt" -Value 'package android.view; open class View' -Encoding utf8
Set-Content -LiteralPath "$output\Window.kt" -Value 'package miui.systemui.dynamicisland.window; class DynamicIslandWindowView : android.view.View()' -Encoding utf8
Set-Content -LiteralPath "$output\Preferences.kt" -Value 'package android.content; interface SharedPreferences { fun getBoolean(key: String, default: Boolean): Boolean }' -Encoding utf8
Set-Content -LiteralPath "$output\Log.kt" -Value 'package android.util; object Log { const val INFO = 4; const val WARN = 5 }' -Encoding utf8
$testClasspath = "$stdlib;$annotations;$apiJar"
& java "-Djava.io.tmpdir=$output" -cp "$gradleLib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $testClasspath -d "$output\classes" "$output\HookContractTest.kt" "$output\View.kt" "$output\Window.kt" "$output\Preferences.kt" "$output\Log.kt" "$project\app\src\main\java\btm\m\os4\systemuihook\SoftGlassParameters.kt"
if ($LASTEXITCODE -ne 0) { throw 'Hook contract check failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes;$testClasspath" btm.m.os4.systemuihook.HookContractTestKt
if ($LASTEXITCODE -ne 0) { throw 'Hook contract check failed.' }
