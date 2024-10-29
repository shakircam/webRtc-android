
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

                clients[userId] = session
                session.send("Added as a client: $userId")

            }
        }
    }

    fun onMessage(userId: String, message: String) {
        when {
            message.startsWith(MessageType.OFFER.toString(), true) ->
                handleOffer(userId, message.substringAfter(' '))
            message.startsWith(MessageType.ANSWER.toString(), true) ->
                handleAnswer(userId, message.substringAfter(' '))
            message.startsWith(MessageType.ICE.toString(), true) ->
                handleIce(userId, message.substringAfter(' '))
        }
    }


    private fun handleOffer(senderId: String, message: String) {
        try {
            // Split message into components: targetId sdpDescription
            val components = message.split(" ", limit = 2)
            if (components.size != 2) {
                println("Invalid offer format from $senderId: $message")
                return
            }

            val targetId = components[0]
            val offerDescription = components[1]

            println("Handling offer from $senderId to $targetId")

            // Forward offer to target client
            clients[targetId]?.send(MessageType.OFFER.toString() + " " + offerDescription)
        } catch (e: Exception) {
            println("Error handling offer from $senderId: ${e.message}")
        }
    }

    private fun handleAnswer(senderId: String, message: String) {
        try {
            // Split message into components: targetId sdpDescription
            val components = message.split(" ", limit = 2)
            if (components.size != 2) {
                println("Invalid answer format from $senderId: $message")
                return
            }

            val targetId = components[0]
            val answerDescription = components[1]

            println("Handling answer from $senderId to $targetId")

            // Forward answer to target client
            clients[targetId]?.send(MessageType.ANSWER.toString() + " " + answerDescription)
        } catch (e: Exception) {
            println("Error handling answer from $senderId: ${e.message}")
        }
    }

    private fun handleIce(senderId: String, message: String) {
        try {
            // Split ICE message: targetId$sdpMid$sdpMLineIndex$sdp
            val iceParts = message.split('$')
            if (iceParts.size != 4) {
                println("Invalid ICE format from $senderId: $message")
                return
            }

            val targetId = iceParts[0]

            println("Handling ICE from $senderId to $targetId")

            // Forward ICE to target client with sender ID
            clients[targetId]?.send(MessageType.ICE.toString() + " " + message)
        } catch (e: Exception) {
            println("Error handling ICE from $senderId: ${e.message}")
        }
    }

    fun onSessionClose(userId: String) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients.remove(userId)
                sessionState = WebRTCSessionState.Impossible
                notifyAboutStateUpdate()
            }
        }
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

    enum class WebRTCSessionState {
        Active,     // Offer and Answer messages have been sent
        Creating,   // Creating session, offer has been sent
        Ready,      // Both clients available and ready to initiate session
        Impossible  // We have less than two clients
    }

    enum class MessageType {
        STATE,  // Command for WebRTCSessionState
        OFFER,  // to send or receive offer
        ANSWER, // to send or receive answer
        ICE     // to send and receive ice candidates
    }
}

//object SessionManager {
//
//    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
//    private val mutex = Mutex()
//
//    private val clients = mutableMapOf<String, DefaultWebSocketServerSession>()
//    private var sessionState: WebRTCSessionState = WebRTCSessionState.Calling
//
//    fun onSessionStarted(userId: String, session: DefaultWebSocketServerSession) {
//        sessionManagerScope.launch {
//            mutex.withLock {
//                clients[userId] = session
//                session.send("Added as a client: $userId")
//            }
//        }
//    }
//
//    fun onMessage( message: String) {
//
//        val components = message.split(" ", limit = 3)
//        val targetUserId = components[1]
//        when {
//           // message.startsWith(MessageType.STATE.toString(), true) -> handleState(userId,receiverId)
//            message.startsWith(MessageType.OFFER.toString(), true) -> handleOffer( targetUserId, message)
//            message.startsWith(MessageType.ANSWER.toString(), true) -> handleAnswer( targetUserId, message)
//            message.startsWith(MessageType.ICE.toString(), true) -> handleIce( message)
//        }
//    }
//
//    private fun handleState(userId: String,targetUserId: String) {
//        sessionManagerScope.launch {
//            println("handling state from $userId")
//            clients[userId]?.send("${MessageType.STATE} $sessionState")
//            clients[targetUserId]?.send("${MessageType.STATE} $sessionState")
//        }
//    }
//
//
//    private fun handleOffer(targetUserId: String, message: String) {
//
//        sessionManagerScope.launch {
//            println("send offer to $targetUserId")
//            clients[targetUserId]?.send(message)
//        }
//    }
//
//
//    private fun handleAnswer(targetUserId: String, message: String) {
//        sessionManagerScope.launch {
//            println("send answer to $targetUserId")
//            clients[targetUserId]?.send(message)
//
//        }
//    }
//
//    private fun handleIce( message: String) {
//        val parts = message.split('$')
//        val targetUserId = parts[0]
//        println("send ice to $targetUserId message $message")
//       // val clientToSendIce = clients.filterKeys { it != userId }.values.first()
//        clients[targetUserId]?.send(message)
//    }
//
//
//    fun onSessionClose(userId: String, receiverId: String) {
//        sessionManagerScope.launch {
//            mutex.withLock {
//                clients.remove(userId)
//               // sessionState = WebRTCSessionState.Impossible
//            }
//        }
//    }
//
//    enum class WebRTCSessionState {
//        Calling,
//        Answer
//    }
//
//    enum class MessageType {
//        STATE,
//        OFFER,
//        ANSWER,
//        ICE
//    }
//
//    private fun DefaultWebSocketServerSession.send(message: String) {
//        sessionManagerScope.launch {
//            this@send.send(Frame.Text(message))
//        }
//    }
//}
