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
//                if (clients.size > 1) {
//                    sessionManagerScope.launch(NonCancellable) {
//                        session.send(Frame.Close()) // only two peers are supported
//                    }
//                    return@launch
//                }
                clients[userId] = session
                session.send("Added as a client: $userId")
//                if (clients.size > 1) {
//                    sessionState = WebRTCSessionState.Ready
//                }
//                notifyAboutStateUpdate()
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
//        if (sessionState != WebRTCSessionState.Ready) {
//            error("Session should be in Ready state to handle offer")
//        }
//        sessionState = WebRTCSessionState.Creating
//        println("handling remote offer from user $userId message $message")
//        notifyAboutStateUpdate()
//        val clientToSendOffer = clients.filterKeys { it != userId }.values.first()
//        clientToSendOffer.send(message)

        sessionManagerScope.launch {
            println("send offer to $targetUserId")
            clients[targetUserId]?.send(message)
        }
    }


    private fun handleAnswer(targetUserId: String, message: String) {
//        if (sessionState != WebRTCSessionState.Creating) {
//            error("Session should be in Creating state to handle answer")
//        }
//        println("handling remote answer from $userId ")
//        val clientToSendAnswer = clients.filterKeys { it != userId }.values.first()
//        clientToSendAnswer.send(message)
//        sessionState = WebRTCSessionState.Active
//        notifyAboutStateUpdate()
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
        Answer,
//        Active, // Offer and Answer messages has been sent
//        Creating, // Creating session, offer has been sent
//        Ready, // Both clients available and ready to initiate session
//        Impossible // We have less than two clients
    }

    enum class MessageType {
        STATE,
        OFFER,
        ANSWER,
        ICE
    }

//    private fun notifyAboutStateUpdate(senderId: String, receiverId: String) {
//        val stateMessage = "${MessageType.STATE} $sessionState"
//
//        // Send state update to the sender
//        clients[senderId]?.send(stateMessage)
//
//        // Send state update to the receiver
//        clients[receiverId]?.send(stateMessage)
////        clients.forEach { (_, client) ->
////            client.send("${MessageType.STATE} $sessionState")
////        }
//    }

    private fun DefaultWebSocketServerSession.send(message: String) {
        sessionManagerScope.launch {
            this@send.send(Frame.Text(message))
        }
    }
}
