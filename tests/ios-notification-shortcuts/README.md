# iOS notification shortcut launch regression checks

Run `powershell -ExecutionPolicy Bypass -File .\tests\ios-notification-shortcuts\run.ps1`
from the repository with a JDK on PATH. Outputs and temporary files stay under
`.reverse-tmp/ios-notification-shortcut-tests` on drive D.

The suite compiles the production `IosNotificationShortcutLaunch.java` against
deterministic Handler, hook and vendor doubles. It verifies launch identity and
exactly-once behavior, original lockscreen delegation, reentrant restoration,
completion and timeout cleanup, cancellation, native failure feedback, stale task
removal, and cleanup after a vendor reset exception. The intentional exception case
prints a reset warning. This does not exercise Android touch dispatch, surfaces,
real vendor remote animations or visual parity; those require device testing.
