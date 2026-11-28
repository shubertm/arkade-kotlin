package com.arkade.e2e

import java.io.File

object DockerHelper {
    fun exec(
        container: String,
        args: List<String>,
    ): String {
        val command = listOf("docker", "exec", container) + args
        val process =
            ProcessBuilder(command)
                .directory(File("."))
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            RuntimeException("Docker execution failed with exit code $exitCode: $output")
        }

        return output.trim()
    }

    fun createArkNote(amount: Long = 10_000_000): String {
        val output = exec(Container.ARKD, listOf("arkd", "note", "--amount", amount.toString()))
        return output.trim()
    }

    private object Container {
        const val BITCOIN: String = "bitcoin"
        const val BOLTZ: String = "boltz"
        const val LND: String = "lnd"
        const val BOLTZLND: String = "boltz-lnd"
        const val ARKD: String = "arkd"
        const val POSTGRES: String = "postgres"
    }
}
