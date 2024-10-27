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
    private var sessionState: WebRTCSessionState = WebRTCSessionState.Impossible

    fun onSessionStarted(userId: String, session: DefaultWebSocketServerSession) {
        sessionManagerScope.launch {
            mutex.withLock {
                if (clients.size > 1) {
                    sessionManagerScope.launch(NonCancellable) {
                        session.send(Frame.Close()) // only two peers are supported
                    }
                    return@launch
                }
                clients[userId] = session
                session.send("Added as a client: $userId")
                if (clients.size > 1) {
                    sessionState = WebRTCSessionState.Ready
                }
                notifyAboutStateUpdate()
            }
        }
    }

    fun onMessage(sessionId: String, message: String) {
        when {
            message.startsWith(MessageType.STATE.toString(), true) -> handleState(sessionId)
            message.startsWith(MessageType.OFFER.toString(), true) -> handleOffer(sessionId, message)
            message.startsWith(MessageType.ANSWER.toString(), true) -> handleAnswer(sessionId, message)
            message.startsWith(MessageType.ICE.toString(), true) -> handleIce(sessionId, message)
        }
    }

    private fun handleState(sessionId: String) {
        sessionManagerScope.launch {
            println("handling state from $sessionId")
            clients[sessionId]?.send("${MessageType.STATE} $sessionState")
        }
    }


    private fun handleOffer(sessionId: String, message: String) {
        if (sessionState != WebRTCSessionState.Ready) {
            error("Session should be in Ready state to handle offer")
        }
        sessionState = WebRTCSessionState.Creating
        println("handling remote offer from user $sessionId message $message")
        notifyAboutStateUpdate()
        val clientToSendOffer = clients.filterKeys { it != sessionId }.values.first()
        clientToSendOffer.send(message)

//        val targetUserId = extractTargetUserIdFromMessage(message)
//        sessionState = WebRTCSessionState.Creating
//        notifyAboutStateUpdate()
//        println("Handling offer from user $sessionId to $targetUserId")
//        clients[targetUserId]?.send(message) ?: run {
//            println("Target user $targetUserId not connected")
//        }
    }


    private fun handleAnswer(sessionId: String, message: String) {
        if (sessionState != WebRTCSessionState.Creating) {
            error("Session should be in Creating state to handle answer")
        }
        println("handling remote answer from $sessionId ")
        val clientToSendAnswer = clients.filterKeys { it != sessionId }.values.first()
        clientToSendAnswer.send(message)
        sessionState = WebRTCSessionState.Active
        notifyAboutStateUpdate()
//        val targetUserId = extractTargetUserIdFromMessage(message)
//        println("Handling answer from user $sessionId to $targetUserId")
//        clients[targetUserId]?.send(message) ?: run {
//            println("Target user $targetUserId not connected")
//        }
//        sessionState = WebRTCSessionState.Active
//        notifyAboutStateUpdate()
    }

    private fun handleIce(sessionId: String, message: String) {
        println("handling remote ice from $sessionId")
        val clientToSendIce = clients.filterKeys { it != sessionId }.values.first()
        clientToSendIce.send(message)
//        val targetUserId = extractTargetUserIdFromMessage(message)
//        println("Handling ICE from user $sessionId to $targetUserId")
//        clients[targetUserId]?.send(message) ?: run {
//            println("Target user $targetUserId not connected")
//        }
    }


    fun onSessionClose(sessionId: String) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients.remove(sessionId)
                sessionState = WebRTCSessionState.Impossible
                notifyAboutStateUpdate()
            }
        }
    }

    private fun extractTargetUserIdFromMessage(message: String): String {
        return message.substringAfter("callee:").trim()
    }

    enum class WebRTCSessionState {
        Active, // Offer and Answer messages has been sent
        Creating, // Creating session, offer has been sent
        Ready, // Both clients available and ready to initiate session
        Impossible // We have less than two clients
    }

    enum class MessageType {
        STATE,
        OFFER,
        ANSWER,
        ICE
    }

    private fun notifyAboutStateUpdate() {
        clients.forEach { (_, client) ->
            client.send("${MessageType.STATE} $sessionState")
        }
    }

    private fun DefaultWebSocketServerSession.send(message: String) {
        sessionManagerScope.launch {
            this@send.send(Frame.Text(message))
        }
    }
}
