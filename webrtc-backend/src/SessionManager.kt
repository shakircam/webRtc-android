
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
private const val ICE_SEPARATOR = '$'

object SessionManager {

    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // store all connected clients
    private val clients = mutableMapOf<String, DefaultWebSocketServerSession>()

    // Track active calls (userId to targetId mapping)
    private val activeConnections = mutableMapOf<String, String>()

    private var sessionState: WebRTCSessionState = WebRTCSessionState.Ready

    fun onSessionStarted(userId: String, session: DefaultWebSocketServerSession) {
        sessionManagerScope.launch {
            mutex.withLock {
//                if (clients.size > 1) {
//                    sessionManagerScope.launch(NonCancellable) {
//                        session.send(Frame.Close()) // only two peers are supported
//                    }
//                    return@launch
//                }
                clients[userId] = session
                session.send("Added as a client: $userId")
                sessionState = WebRTCSessionState.Ready
//                if (clients.size > 1) {
//                    sessionState = WebRTCSessionState.Ready
//                }
//                notifyAboutStateUpdate()
            }
        }
    }

    fun onMessage(sessionId: String, message: String) {
        when {
            message.startsWith(MessageType.STATE.toString(), true) -> handleState(sessionId)
            message.startsWith(MessageType.OFFER.toString(), true) -> handleOffer(sessionId, message)
            message.startsWith(MessageType.ANSWER.toString(), true) -> handleAnswer(sessionId, message)
            message.startsWith(MessageType.ICE.toString(), true) -> handleIce(sessionId, message)
            message.startsWith(MessageType.END_CALL.toString(), true) -> handleEndCall(sessionId, message)
        }
    }

    private fun handleState(sessionId: String) {
        sessionManagerScope.launch {
            clients[sessionId]?.send("${MessageType.STATE} $sessionState")
        }
    }

    private fun handleOffer(userId: String, message: String) {
        if (sessionState != WebRTCSessionState.Ready) {
            error("Session should be in Ready state to handle offer")
        }
        sessionState = WebRTCSessionState.Creating
        val offer = message.split(ICE_SEPARATOR)
        val targetId = offer[1]
        println("handling offer from $userId to: $targetId ")
        notifyAboutStateUpdate(userId,targetId)
        //val clientToSendOffer = clients.filterKeys { it != userId }.values.first()
        //clientToSendOffer.send(message)
        clients[targetId]?.send(message)

    }

    private fun handleAnswer(userId: String, message: String) {
        if (sessionState != WebRTCSessionState.Creating) {
            error("Session should be in Creating state to handle answer")
        }
        val answer = message.split(ICE_SEPARATOR)
        val targetId = answer[1]
        println("handling answer from $userId to: $targetId ")
        //val clientToSendAnswer = clients.filterKeys { it != userId }.values.first()
        //clientToSendAnswer.send(message)
        clients[targetId]?.send(message)
        sessionState = WebRTCSessionState.Active
        notifyAboutStateUpdate(userId,targetId)

    }

    private fun handleIce(userId: String, message: String) {
        val ice = message.split(ICE_SEPARATOR)
        val pattern = Regex("ICE\\s(\\d+)")
        val result = pattern.find(ice[0])
        val targetUserID = result?.groups?.get(1)?.value
        println("handling ice from $userId to: $targetUserID ")
        //val clientToSendIce = clients.filterKeys { it != userId }.values.first()
        //clientToSendIce.send(message)
        clients[targetUserID]?.send(message)
    }

    private fun handleEndCall(userId: String, message: String) {
        val targetUserId = message.split(ICE_SEPARATOR)[1]
        println("Handling end call from user $userId to $targetUserId")
        clients[userId]?.send("${MessageType.END_CALL} $targetUserId")
        clients[targetUserId]?.send("${MessageType.END_CALL} $userId")
        onSessionClose(userId)
        onSessionClose(targetUserId)
    }

    fun onSessionClose(userId: String) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients.remove(userId)
                 sessionState = WebRTCSessionState.End
                // notifyAboutStateUpdate(userId,calleeId)
            }
        }
    }

    enum class WebRTCSessionState {
        Active, // Offer and Answer messages has been sent
        Creating, // Creating session, offer has been sent
        Ready, // Both clients available and ready to initiate session
        Impossible,
        End// We have less than two clients
    }

    enum class MessageType {
        STATE,
        OFFER,
        ANSWER,
        ICE,
        END_CALL
    }

    private fun notifyAboutStateUpdate(callerId: String, calleeId : String) {
        clients[callerId]?.send("${MessageType.STATE} $sessionState")
        clients[calleeId]?.send("${MessageType.STATE} $sessionState")
//        clients.forEach { (_, client) ->
//            client.send("${MessageType.STATE} $sessionState")
//        }
    }

    private fun DefaultWebSocketServerSession.send(message: String) {
        sessionManagerScope.launch {
            this@send.send(Frame.Text(message))
        }
    }
}

