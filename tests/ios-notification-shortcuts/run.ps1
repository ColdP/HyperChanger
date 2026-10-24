$ErrorActionPreference = 'Stop'
$project = (Resolve-Path "$PSScriptRoot\..\..").Path
$output = Join-Path $project '.reverse-tmp\ios-notification-shortcut-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$env:TEMP = $output
$env:TMP = $output
$sources = @(Get-ChildItem -LiteralPath "$PSScriptRoot\src" -Filter '*.java' -Recurse | ForEach-Object FullName)
$sources += "$PSScriptRoot\ShortcutLaunchTest.java"
$sources += "$project\app\src\main\java\btm\m\os4\systemuihook\newnotificationcenter\IosNotificationShortcutLaunch.java"
& javac "-J-Djava.io.tmpdir=$output" -encoding UTF-8 -d "$output\classes" @sources
if ($LASTEXITCODE -ne 0) { throw 'Shortcut regression checks failed to compile.' }
& java "-Djava.io.tmpdir=$output" -cp "$output\classes" btm.m.os4.systemuihook.newnotificationcenter.ShortcutLaunchTest
if ($LASTEXITCODE -ne 0) { throw 'Shortcut regression checks failed.' }
