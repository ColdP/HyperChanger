// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import java.nio.file.Files
import java.nio.file.Path

/** Host test using Git Bash; ownership and SELinux operations are mocked. */
fun main(args: Array<String>) {
    val root = Files.createTempDirectory(Path.of(args[1]), "transport-")
    val launcher = Files.createDirectory(root.resolve("launcher's data"))
    val target = launcher.resolve("files/hyperpatch.bin")
    val log = root.resolve("permissions.log")
    fun shellPath(path: Path): String {
        val value = path.toAbsolutePath().toString().replace('\\', '/')
        return "/${value[0].lowercaseChar()}${value.substring(2)}"
    }
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    val mocks = """
        id() { printf '0\n'; }
        stat() {
            if [ "${'$'}2" = '%u:%g' ]; then printf '10123:10123\n';
            else command stat "${'$'}@"; fi
        }
        ls() { printf 'u:object_r:privapp_data_file:s0:c123,c456 %s\n' "${'$'}2"; }
        chown() { printf 'owner %s %s\n' "${'$'}1" "${'$'}2" >> ${quote(shellPath(log))}; }
        chcon() { printf 'label %s %s\n' "${'$'}1" "${'$'}2" >> ${quote(shellPath(log))}; }
        settings() { test "${'$'}*" = 'put secure miui_multiple_split_resolved_max_stages 3'; }
    """.trimIndent()
    fun run(prefix: String, blob: ByteArray, expectedSize: Int = blob.size, safetyFlags: Int? = null): Pair<Int, String> {
        val command = buildHomeLauncherStageCommand(shellPath(launcher), expectedSize, safetyFlags = safetyFlags)
        Files.writeString(root.resolve("command.sh"), prefix + "\n" + command)
        val process = ProcessBuilder(args[0], "--noprofile", "--norc", shellPath(root.resolve("command.sh")))
            .redirectErrorStream(true).start()
        runCatching { process.outputStream.use { it.write(blob) } }
        val output = process.inputStream.bufferedReader().readText()
        val result = process.waitFor()
        return result to output
    }
    val first = buildHomeLauncherBlob(1, 0)
    check(run(mocks, first) == (0 to ""))
    check(Files.readAllBytes(target).contentEquals(first))
    val permissions = Files.readString(log)
    check(permissions.contains("owner 10123:10123 ${shellPath(target)}."))
    check(permissions.contains("label u:object_r:privapp_data_file:s0:c123,c456 ${shellPath(target)}."))
    check(permissions.contains("label u:object_r:privapp_data_file:s0:c123,c456 ${shellPath(launcher)}/files\n"))
    // A later folder change must retain the selected clear-button mode.
    val updated = buildHomeLauncherBlob(1, 6)
    check(run(mocks, updated) == (0 to ""))
    val previous = Files.readAllBytes(target)
    check(previous.contentEquals(updated))
    fun assertNoTemporaryFiles() {
        Files.list(target.parent).use { files -> check(files.count() == 1L) }
    }
    assertNoTemporaryFiles()
    // Failed labeling must not publish an unreadable configuration over the old one.
    val next = buildHomeLauncherBlob(2, 4)
    val failure = run(mocks + "\nchcon() { return 9; }", next)
    check(failure.first == 9 && failure.second.contains("file-label failed (exit 9)"))
    check(Files.readAllBytes(target).contentEquals(previous))
    assertNoTemporaryFiles()
    val truncated = run(mocks, next.copyOf(next.size - 1), next.size)
    check(truncated.first != 0 && truncated.second.contains("length failed"))
    check(Files.readAllBytes(target).contentEquals(previous))
    assertNoTemporaryFiles()
    val denied = run(mocks + "\nid() { printf '10101\\n'; }", next)
    check(denied.first != 0 && denied.second.contains("root failed"))
    check(Files.readAllBytes(target).contentEquals(previous))
    val contextFailure = run(mocks + "\nls() { return 8; }", next)
    check(contextFailure.first == 8 && contextFailure.second.contains("context failed"))
    val missingContext = run(mocks + "\nls() { printf '?\\n'; }", next)
    check(missingContext.first != 0 && missingContext.second.contains("missing SELinux context"))
    val safety = target.parent.resolve("hyperchanger-safety")
    repeat(8) { flags ->
        check(run(mocks, updated, safetyFlags = flags) == (0 to ""))
        check(Files.readString(safety) == flags.toString())
        val permissions = Files.readString(log)
        check(permissions.contains("owner 10123:10123 ${shellPath(safety)}."))
        check(permissions.contains("label u:object_r:privapp_data_file:s0:c123,c456 ${shellPath(safety)}."))
    }
    val safetyFailure = run(mocks + """

        chcon() { case "${'$'}2" in *hyperchanger-safety.*) return 12;; esac; }
    """.trimIndent(), updated, safetyFlags = 0)
    check(safetyFailure.first == 12 && safetyFailure.second.contains("safety-label failed"))
    check(Files.readString(safety) == "7")
    Files.list(target.parent).use { files -> check(files.count() == 2L) }
    check(runCatching { buildHomeLauncherStageCommand("unused", 1, safetyFlags = 8) }.isFailure)
    println("Passed launcher staging: binary stdin, quoted paths, SELinux/ownership, flags 0..7, atomic updates, failed-label cleanup, truncation, and diagnostics")
}
