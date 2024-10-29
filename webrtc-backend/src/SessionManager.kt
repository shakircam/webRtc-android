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
data class UserSession(
    val userId: String,
    val session: DefaultWebSocketServerSession
)

// Modified SessionManager for targeted calls
object SessionManager {
    private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // Map of userId to UserSession
    private val clients = mutableMapOf<String, UserSession>()

    // Map to track active calls between users
    private val activeCallSessions = mutableMapOf<String, CallSession>()

    data class CallSession(
        val callerId: String,
        val targetUserId: String,
        var state: WebRTCSessionState
    )

    fun onSessionStarted(userId: String, session: DefaultWebSocketServerSession) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients[userId] = UserSession(userId, session)
                session.send("Connected as: $userId")
                // Notify all users about online status
                broadcastOnlineUsers()
            }
        }
    }

    private fun broadcastOnlineUsers() {
        val onlineUsers = clients.keys.toList()
        clients.values.forEach { userSession ->
            sessionManagerScope.launch {
                userSession.session.send("${MessageType.ONLINE_USERS} ${onlineUsers.joinToString(",")}")
            }
        }
    }

    fun isUserConnected(userId: String): Boolean {
        return clients.containsKey(userId)
    }

    fun onMessage(userId: String, message: String) {
        when {
            message.startsWith(MessageType.STATE.toString(), true) -> handleState(userId)
            message.startsWith(MessageType.CALL_REQUEST.toString(), true) -> handleCallRequest(userId, message)
            message.startsWith(MessageType.CALL_RESPONSE.toString(), true) -> handleCallResponse(userId, message)
            message.startsWith(MessageType.OFFER.toString(), true) -> handleOffer(userId, message)
            message.startsWith(MessageType.ANSWER.toString(), true) -> handleAnswer(userId, message)
            message.startsWith(MessageType.ICE.toString(), true) -> handleIce(userId, message)
        }
    }

    private fun handleState(userId: String) {
        sessionManagerScope.launch {
            val state = when {
                // Check if user is in an active call
                activeCallSessions.values.any {
                    (it.callerId == userId || it.targetUserId == userId) &&
                            it.state == WebRTCSessionState.Active
                } -> WebRTCSessionState.Active

                // Check if user is in call creation process
                activeCallSessions.values.any {
                    (it.callerId == userId || it.targetUserId == userId) &&
                            (it.state == WebRTCSessionState.Creating || it.state == WebRTCSessionState.Ready)
                } -> WebRTCSessionState.Creating

                // Check if user has pending call request
                activeCallSessions.values.any {
                    (it.callerId == userId || it.targetUserId == userId) &&
                            it.state == WebRTCSessionState.Initiating
                } -> WebRTCSessionState.Initiating

                // If user is online but not in any call
                clients.containsKey(userId) -> WebRTCSessionState.Ready

                // If user is not online
                else -> WebRTCSessionState.Impossible
            }
            clients[userId]?.session?.send("${MessageType.STATE} $state")
        }
    }

    private fun handleCallRequest(callerId: String, message: String) {
        val targetUserId = message.substringAfter("${MessageType.CALL_REQUEST} ")

        if (clients.containsKey(targetUserId)) {
            activeCallSessions[callerId] = CallSession(
                callerId = callerId,
                targetUserId = targetUserId,
                state = WebRTCSessionState.Initiating
            )

            // Notify target user about incoming call
            clients[targetUserId]?.session?.send("${MessageType.INCOMING_CALL} $callerId")
        } else {
            clients[callerId]?.session?.send("${MessageType.ERROR} User $targetUserId is not online")
        }
    }

    private fun handleCallResponse(userId: String, message: String) {
        val response = message.substringAfter("${MessageType.CALL_RESPONSE} ")
        val callerId = activeCallSessions.entries.find { it.value.targetUserId == userId }?.key ?: return
        val callSession = activeCallSessions[callerId] ?: return

        if (response == "accept") {
            callSession.state = WebRTCSessionState.Ready
            clients[callerId]?.session?.send("${MessageType.CALL_ACCEPTED} $userId")
        } else {
            callSession.state = WebRTCSessionState.Impossible
            clients[callerId]?.session?.send("${MessageType.CALL_REJECTED} $userId")
            activeCallSessions.remove(callerId)
        }
    }

    private fun handleOffer(callerId: String, message: String) {
        val callSession = activeCallSessions[callerId] ?: return
        if (callSession.state != WebRTCSessionState.Ready) {
            return
        }

        callSession.state = WebRTCSessionState.Creating
        val targetUserId = callSession.targetUserId
        clients[targetUserId]?.session?.send(message)
    }

    private fun handleAnswer(userId: String, message: String) {
        val callSession = activeCallSessions.values.find { it.targetUserId == userId } ?: return
        if (callSession.state != WebRTCSessionState.Creating) {
            return
        }

        clients[callSession.callerId]?.session?.send(message)
        callSession.state = WebRTCSessionState.Active
    }

    private fun handleIce(userId: String, message: String) {
        val callSession = activeCallSessions[userId]
            ?: activeCallSessions.values.find { it.targetUserId == userId }
            ?: return

        val targetUserId = if (userId == callSession.callerId) {
            callSession.targetUserId
        } else {
            callSession.callerId
        }

        clients[targetUserId]?.session?.send(message)
    }

    fun onSessionClose(userId: String) {
        sessionManagerScope.launch {
            mutex.withLock {
                clients.remove(userId)
                // Clean up any active calls involving this user
                activeCallSessions.entries.removeIf {
                    it.value.callerId == userId || it.value.targetUserId == userId
                }
                broadcastOnlineUsers()
            }
        }
    }

    enum class WebRTCSessionState {
        Active,     // Call is ongoing
        Creating,   // Creating session, offer has been sent
        Ready,      // Call accepted, ready for offer
        Initiating, // Initial call request sent
        Impossible  // Call ended or rejected
    }

    enum class MessageType {
        STATE,
        CALL_REQUEST,
        CALL_RESPONSE,
        INCOMING_CALL,
        CALL_ACCEPTED,
        CALL_REJECTED,
        ONLINE_USERS,
        OFFER,
        ANSWER,
        ICE,
        ERROR
    }

    private fun DefaultWebSocketServerSession.send(message: String) {
        sessionManagerScope.launch {
            this@send.send(Frame.Text(message))
        }
    }
}

