import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import java.time.Duration
import java.util.*

/**
 * Originally written by Artem Bagritsevich.
 *
 * https://github.com/artem-bagritsevich/WebRTCKtorSignalingServerExample
 */
fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Referenced in application.conf
@JvmOverloads
fun Application.module(testing: Boolean = false) {

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {

        get("/") {
            call.respond("Hello from WebRTC signaling server")
        }

        webSocket("/rtc/{userId}") {
            val userId : String = (call.parameters["userId"] ?: 0).toString()
            log.info("connected to server userId $userId")
            when {
                userId.isNullOrBlank() -> {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "userId is required"))
                    return@webSocket
                }

                SessionManager.isUserConnected(userId) -> {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "userId already connected"))
                    return@webSocket
                }
            }

            try {
                SessionManager.onSessionStarted(userId, this)

                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            SessionManager.onMessage(userId, frame.readText())
                        }
                        else -> Unit
                    }
                }
                println("Exiting incoming loop, closing session: $userId")
                SessionManager.onSessionClose(userId)
            } catch (e: ClosedReceiveChannelException) {
                println("onClose $userId")
                SessionManager.onSessionClose(userId)
            } catch (e: Throwable) {
                println("onError $userId $e")
                SessionManager.onSessionClose(userId)
            }
        }
    }
}

