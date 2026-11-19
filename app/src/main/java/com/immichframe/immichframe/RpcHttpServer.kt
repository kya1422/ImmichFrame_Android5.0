package com.immichframe.immichframe

import fi.iki.elonen.NanoHTTPD

class RpcHttpServer(
    private val onFrameActiveCommand: (Boolean) -> Unit,
    private val onNextCommand: () -> Unit,
    private val onPreviousCommand: () -> Unit,
    private val onPauseCommand: () -> Unit,
    private val onSettingsCommand: () -> Unit,
    private val onBrightnessCommand: (Float) -> Unit,
) : NanoHTTPD(LOOPBACK, PORT) {

    override fun serve(session: IHTTPSession): Response {
        // Only accept connections from this device itself. Passing no host name to NanoHTTPD
        // (the old constructor) binds 0.0.0.0, i.e. every Wi-Fi client - and, with a port
        // forward on the router, the whole internet - could reach these commands without any
        // authentication. 127.0.0.1 keeps the commands usable from an on-device automation app
        // while removing the LAN attack surface completely.
        val remote = session.remoteIpAddress
        if (!isLoopback(remote)) {
            FrameLog.w(TAG_LOG, "Rejected RPC '${session.uri}' from $remote (not loopback)")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                MIME_PLAINTEXT,
                "RPC is only available from this device (127.0.0.1)",
            )
        }
        return when (session.uri) {
            // "/dim" and "/undim" are kept as aliases for backwards compatibility
            "/off", "/dim" -> {
                onFrameActiveCommand(false)
                newFixedLengthResponse("Frame off")
            }
            "/on", "/undim" -> {
                onFrameActiveCommand(true)
                newFixedLengthResponse("Frame on")
            }
            "/next" -> {
                onNextCommand()
                newFixedLengthResponse("Next")
            }
            "/previous" -> {
                onPreviousCommand()
                newFixedLengthResponse("Previous")
            }
            "/pause" -> {
                onPauseCommand()
                newFixedLengthResponse("Pause")
            }
            "/settings" -> {
                onSettingsCommand()
                newFixedLengthResponse("Settings")
            }
            "/brightness" -> {
                val brightness = session.parameters["value"]?.firstOrNull()?.toFloatOrNull()
                if (brightness != null && brightness >= -1 && brightness <= 1) {
                    onBrightnessCommand(brightness)
                    newFixedLengthResponse("Brightness set to $brightness")
                } else {
                    newFixedLengthResponse("Brightness parameter missing or invalid (should be a value between 0.00 and 1.00, or -1.00 for system default)")
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown command")
        }
    }

    /** True for 127.0.0.0/8 and ::1; everything else is refused by [serve]. */
    private fun isLoopback(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") return true
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        if (parts.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) return false
        return parts[0] == "127"
    }

    companion object {
        /** Loopback only: the commands must not be reachable from the LAN (or the internet). */
        private const val LOOPBACK = "127.0.0.1"
        const val PORT = 53287
        private const val TAG_LOG = "rpc"
    }
}
