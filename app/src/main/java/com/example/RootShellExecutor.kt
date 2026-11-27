package com.example

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ShellExecutor {
    suspend fun isRootAvailable(): Boolean
    suspend fun executeCommand(command: String): ShellResult
}

object RootShellExecutor : ShellExecutor {
    private const val TAG = "RootShellExecutor"

    fun initialize() {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )
    }

    /**
     * Safely quotes a string for POSIX sh using single-quote escaping,
     * preventing shell expansion of $, `, ", \, and spaces.
     */
    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun redactForLog(command: String): String {
        return if (command.startsWith("cmd wifi connect-network ") && !command.endsWith(" open")) {
            val parts = command.split(" ", limit = 5)
            if (parts.size >= 5) "${parts[0]} ${parts[1]} ${parts[2]} ${parts[3]} <REDACTED>" else command
        } else {
            command
        }
    }

    override suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val hasRoot = Shell.getShell().isRoot
            Log.d(TAG, "isRootAvailable: $hasRoot")
            hasRoot
        } catch (e: Exception) {
            Log.e(TAG, "Root availability check failed", e)
            false
        }
    }

    override suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        val safeLogCmd = redactForLog(command)
        Log.d(TAG, "Executing: $safeLogCmd")
        try {
            val result = Shell.cmd(command).exec()
            val output = result.out.joinToString("\n")
            val isSuccess = result.isSuccess
            Log.d(TAG, "Result (Success: $isSuccess, Code: ${result.code}): $output")
            ShellResult(isSuccess, output, result.code)
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $safeLogCmd", e)
            ShellResult(false, e.message ?: "Unknown error", -1)
        }
    }
}

data class ShellResult(
    val isSuccess: Boolean,
    val output: String,
    val code: Int
)
