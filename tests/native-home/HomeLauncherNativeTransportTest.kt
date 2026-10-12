// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import java.nio.file.Files
import java.nio.file.Path

/** Host test using Git Bash; chown/restorecon are recorded, never run against Android. */
fun main(args: Array<String>) {
    val root = Files.createTempDirectory(Path.of(args[1]), "transport-")
    val source = root.resolve("source's config.bin")
    val launcher = Files.createDirectory(root.resolve("launcher's data"))
    val target = launcher.resolve("files/hyperpatch.bin")
    val log = root.resolve("permissions.log")
    fun shellPath(path: Path): String {
        val value = path.toAbsolutePath().toString().replace('\\', '/')
        return "/${value[0].lowercaseChar()}${value.substring(2)}"
    }
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    val command = buildHomeLauncherStageCommand(shellPath(source), shellPath(launcher))
    val mocks = """
        test() {
            if [ "${'$'}#" -eq 2 ] && [ "${'$'}2" = /data/adb ]; then return 1; fi
            builtin test "${'$'}@"
        }
        stat() { printf '10123:10123\n'; }
        chown() { printf 'owner %s %s\n' "${'$'}1" "${'$'}2" >> ${quote(shellPath(log))}; }
        restorecon() { printf 'label %s\n' "${'$'}1" >> ${quote(shellPath(log))}; }
    """.trimIndent()
    fun run(prefix: String): Int {
        Files.writeString(root.resolve("command.sh"), prefix + "\n" + command)
        val process = ProcessBuilder(args[0], "--noprofile", "--norc", shellPath(root.resolve("command.sh")))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val result = process.waitFor()
        check(output.isBlank()) { output }
        return result
    }
    Files.write(source, buildHomeLauncherBlob(1, 0))
    check(run(mocks) == 0)
    check(Files.readAllBytes(target).contentEquals(Files.readAllBytes(source)))
    val permissions = Files.readString(log)
    check(permissions.contains("owner 10123:10123 ${shellPath(target)}.tmp"))
    check(permissions.contains("label ${shellPath(target)}.tmp"))
    check(permissions.contains("label ${shellPath(target)}\n"))
    // A later folder change must retain the selected clear-button mode.
    Files.write(source, buildHomeLauncherBlob(1, 6))
    check(run(mocks) == 0)
    val previous = Files.readAllBytes(target)
    check(previous.contentEquals(Files.readAllBytes(source)))
    check(!Files.exists(launcher.resolve("files/hyperpatch.bin.tmp")))
    // Failed labeling must not publish an unreadable configuration over the old one.
    Files.write(source, buildHomeLauncherBlob(2, 4))
    check(run(mocks + "\nrestorecon() { return 9; }") == 9)
    check(Files.readAllBytes(target).contentEquals(previous))
    println("Passed launcher staging: quoted paths, ownership, labels, atomic updates, and labeling failure")
}
