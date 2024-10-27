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
        webSocket("/rtc/{callerId}") {
           // val sessionID = UUID.randomUUID()
            val sessionID : String = (call.parameters["callerId"] ?: 0).toString()
            log.info("onSessionConnect userId = $sessionID sessionID = $sessionID")
            try {
                SessionManager.onSessionStarted(sessionID, this)

                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            log.info("onMessage coming from client $sessionID $frame")
                            SessionManager.onMessage(sessionID, frame.readText())
                        }

                        else -> Unit
                    }
                }
                log.info("Exiting incoming loop, closing session: $sessionID")
                SessionManager.onSessionClose(sessionID)
            } catch (e: ClosedReceiveChannelException) {
                log.error("onClose $sessionID")
                SessionManager.onSessionClose(sessionID)
            } catch (e: Throwable) {
                log.error("onError $sessionID $e")
                SessionManager.onSessionClose(sessionID)
            }
        }
    }
}

