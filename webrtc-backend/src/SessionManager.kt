import io.ktor.http.cio.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

/**
 * Originally written by Artem Bagritsevich.
 *
 * https://github.com/artem-bagritsevich/WebRTCKtorSignalingServerExample
 */
object SessionManager {

    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val clients = mutableMapOf<String, DefaultWebSocketServerSession>()
    private var sessionState: WebRTCSessionState = WebRTCSessionState.Calling

    fun onSessionStarted(userId: String, session: DefaultWebSocketServerSession) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients[userId] = session
                session.send("Added as a client: $userId")
            }
        }
    }

    fun onMessage( message: String) {

        val components = message.split(" ", limit = 3)
        val targetUserId = components[1]
        when {
           // message.startsWith(MessageType.STATE.toString(), true) -> handleState(userId,receiverId)
            message.startsWith(MessageType.OFFER.toString(), true) -> handleOffer( targetUserId, message)
            message.startsWith(MessageType.ANSWER.toString(), true) -> handleAnswer( targetUserId, message)
            message.startsWith(MessageType.ICE.toString(), true) -> handleIce( message)
        }
    }

    private fun handleState(userId: String,targetUserId: String) {
        sessionManagerScope.launch {
            println("handling state from $userId")
            clients[userId]?.send("${MessageType.STATE} $sessionState")
            clients[targetUserId]?.send("${MessageType.STATE} $sessionState")
        }
    }


    private fun handleOffer(targetUserId: String, message: String) {

        sessionManagerScope.launch {
            println("send offer to $targetUserId")
            clients[targetUserId]?.send(message)
        }
    }


    private fun handleAnswer(targetUserId: String, message: String) {
        sessionManagerScope.launch {
            println("send answer to $targetUserId")
            clients[targetUserId]?.send(message)

        }
    }

    private fun handleIce( message: String) {
        val parts = message.split('$')
        val targetUserId = parts[0]
        println("send ice to $targetUserId message $message")
       // val clientToSendIce = clients.filterKeys { it != userId }.values.first()
        clients[targetUserId]?.send(message)
    }


    fun onSessionClose(userId: String, receiverId: String) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients.remove(userId)
               // sessionState = WebRTCSessionState.Impossible
            }
        }
    }

    enum class WebRTCSessionState {
        Calling,
        Answer
    }

    enum class MessageType {
        STATE,
        OFFER,
        ANSWER,
        ICE
    }

    private fun DefaultWebSocketServerSession.send(message: String) {
        sessionManagerScope.launch {
            this@send.send(Frame.Text(message))
        }
    }
}
