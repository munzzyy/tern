package io.github.munzzyy.tern.install

import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What Tern asks pm to do through Shizuku or su, and what it makes of pm's answers. */
class ShellInstallerTest {
    private val work: File = Files.createTempDirectory("shell-install").toFile().apply { deleteOnExit() }

    private class ScriptedShell(private val answer: (List<String>) -> ShellResult) : PrivilegedShell {
        val commands = ArrayList<List<String>>()
        val inputs = ArrayList<File?>()

        override fun ready(): Boolean = true

        override fun run(command: List<String>, input: File?): ShellResult {
            commands += command
            inputs += input
            return answer(command)
        }
    }

    private fun file(name: String, size: Int): File = File(work, name).apply { writeBytes(ByteArray(size)) }

    private fun pmAnswers(commit: String = "Success", write: String = "Success: streamed 10 bytes"): (List<String>) -> ShellResult = { command ->
        when (command.getOrNull(1)) {
            "install-create" -> ShellResult(0, "Success: created install session [4242]\n", "")
            "install-write" -> ShellResult(0, write, "")
            "install-commit" -> ShellResult(if (commit == "Success") 0 else 1, commit, "")
            "install-abandon" -> ShellResult(0, "Success", "")
            else -> ShellResult(1, "", "Unknown command")
        }
    }

    private fun installer(shell: PrivilegedShell, outcomes: MutableList<Triple<String, Int, String?>> = ArrayList()) =
        ShellInstaller(
            shell = shell,
            installerFor = { "io.github.munzzyy.tern" },
            sessionExists = { true },
            deliver = { appId, _, status, message -> outcomes += Triple(appId, status, message) },
            userId = { 10 },
        )

    @Test
    fun aSessionIsMadeForTheUserWithTernAsInstallerAndEveryPartIsStreamedIn() {
        val shell = ScriptedShell(pmAnswers())
        val base = file("base.apk", 10)
        val split = file("split.apk", 7)
        val session = installer(shell).prepare("org.example.app", listOf(base, split), claimUpdateOwnership = false)
        assertEquals(4242, session)
        val create = shell.commands[0]
        assertEquals(listOf("pm", "install-create"), create.take(2))
        assertEquals("io.github.munzzyy.tern", create[create.indexOf("-i") + 1])
        assertEquals("org.example.app", create[create.indexOf("--pkg") + 1])
        assertEquals("10", create[create.indexOf("--user") + 1])
        assertEquals("17", create[create.indexOf("-S") + 1])
        assertEquals(listOf("pm", "install-write", "-S", "10", "4242", "apk-0.apk", "-"), shell.commands[1])
        assertEquals(listOf("pm", "install-write", "-S", "7", "4242", "apk-1.apk", "-"), shell.commands[2])
        assertEquals(base, shell.inputs[1])
        assertEquals(split, shell.inputs[2])
    }

    @Test
    fun aWriteThatFailsAbandonsTheSession() {
        val shell = ScriptedShell(pmAnswers(write = "Error: unable to open file: Permission denied"))
        try {
            installer(shell).prepare("org.example.app", listOf(file("a.apk", 3)), claimUpdateOwnership = false)
            fail("A failed write must not leave a session to commit")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("Permission denied"))
        }
        assertEquals(listOf("pm", "install-abandon", "4242"), shell.commands.last())
    }

    @Test
    fun noSessionMeansNothingIsWritten() {
        val shell = ScriptedShell { ShellResult(1, "", "Failure [INSTALL_FAILED_USER_RESTRICTED: Install blocked]") }
        try {
            installer(shell).prepare("org.example.app", listOf(file("b.apk", 3)), claimUpdateOwnership = false)
            fail("Without a session there is nothing to install")
        } catch (_: IOException) {
        }
        assertEquals(1, shell.commands.size)
    }

    @Test
    fun theCommitIsAnsweredAsPackageInstallerWouldAnswer() {
        val outcomes = ArrayList<Triple<String, Int, String?>>()
        installer(ScriptedShell(pmAnswers()), outcomes).commit("app-1", 4242)
        installer(ScriptedShell(pmAnswers(commit = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]")), outcomes).commit("app-2", 4242)
        installer(ScriptedShell(pmAnswers(commit = "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")), outcomes).commit("app-3", 4242)
        assertEquals(Triple("app-1", PackageInstaller.STATUS_SUCCESS, null), outcomes[0])
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, outcomes[1].second)
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match", outcomes[1].third)
        assertTrue("A downgrade stays recognisable by its message", outcomes[2].third!!.contains("DOWNGRADE"))
    }

    @Test
    fun aShellThatCannotBeReachedIsAFailureAndNeverASuccess() {
        val outcomes = ArrayList<Triple<String, Int, String?>>()
        val broken = object : PrivilegedShell {
            override fun ready() = false
            override fun run(command: List<String>, input: File?): ShellResult = throw IOException("Shizuku is not running")
        }
        installer(broken, outcomes).commit("app", 1)
        assertEquals(PackageInstaller.STATUS_FAILURE, outcomes.single().second)
    }

    @Test
    fun pmOutputIsReadTheWayAndroidGroupsItsAnswers() {
        assertEquals(4242, PmOutput.sessionId("Success: created install session [4242]"))
        assertNull(PmOutput.sessionId("Error: java.lang.SecurityException"))
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, PmOutput.statusOf("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PmOutput.statusOf("INSTALL_FAILED_NO_MATCHING_ABIS"))
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, PmOutput.statusOf("INSTALL_PARSE_FAILED_NOT_APK"))
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, PmOutput.statusOf("INSTALL_FAILED_ABORTED"))
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, PmOutput.statusOf("INSTALL_FAILED_USER_RESTRICTED"))
        assertEquals(PackageInstaller.STATUS_FAILURE, PmOutput.statusOf("INSTALL_FAILED_SOMETHING_NEW"))
        val (status, message) = PmOutput.outcome(ShellResult(1, "", "Error: Unknown option --pkg"))
        assertEquals(PackageInstaller.STATUS_FAILURE, status)
        assertEquals("Error: Unknown option --pkg", message)
    }

    @Test
    fun suSeesEveryArgumentAsOneWord() {
        assertEquals("'a b'", RootShell.quote("a b"))
        assertEquals("'it'\\''s'", RootShell.quote("it's"))
        assertEquals("'\$(reboot)'", RootShell.quote("\$(reboot)"))
    }
}
