/*
 * Copyright 2023 Stream.IO, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.getstream.webrtc.sample.compose.webrtc


import io.getstream.log.taggedLogger
import io.getstream.webrtc.sample.compose.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SignalingClient(
  val userId: String
) {
  private val logger by taggedLogger("Call:SignalingClient")
  private val signalingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val client = OkHttpClient()
  private val request = Request
    .Builder()
    .url("${BuildConfig.SIGNALING_SERVER_IP_ADDRESS}/$userId")
    .build()

  private val ws = client.newWebSocket(request, SignalingWebSocketListener())

  private val _sessionStateFlow = MutableStateFlow(WebRTCSessionState.Offline)
  val sessionStateFlow: StateFlow<WebRTCSessionState> = _sessionStateFlow

  private val _onlineUsersFlow = MutableStateFlow<List<String>>(emptyList())
  val onlineUsersFlow: StateFlow<List<String>> = _onlineUsersFlow

  private val _incomingCallFlow = MutableSharedFlow<String>()
  val incomingCallFlow: SharedFlow<String> = _incomingCallFlow

  private val _callResponseFlow = MutableSharedFlow<Pair<String, Boolean>>()
  val callResponseFlow: SharedFlow<Pair<String, Boolean>> = _callResponseFlow

  private val _signalingCommandFlow = MutableSharedFlow<Pair<SignalingCommand, String>>()
  val signalingCommandFlow: SharedFlow<Pair<SignalingCommand, String>> = _signalingCommandFlow

  fun initiateCall(targetUserId: String) {
    sendCommand(SignalingCommand.CALL_REQUEST, targetUserId)
  }

  fun acceptCall(callerId: String) {
    sendCommand(SignalingCommand.CALL_RESPONSE, "accept $callerId")
  }

  fun rejectCall(callerId: String) {
    sendCommand(SignalingCommand.CALL_RESPONSE, "reject $callerId")
  }

  fun sendCommand(signalingCommand: SignalingCommand, message: String) {
    logger.d { "[sendCommand] $signalingCommand $message" }
    ws.send("$signalingCommand $message")
  }

  private inner class SignalingWebSocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
      logger.d { "[onOpen] WebSocket connection established" }
      _sessionStateFlow.value = WebRTCSessionState.Online
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
      logger.d { "[onMessage] Received: $text" }
      when {
        text.startsWith(SignalingCommand.STATE.toString(), true) ->
          handleStateMessage(text)
        text.startsWith(SignalingCommand.ONLINE_USERS.toString(), true) ->
          handleOnlineUsers(text)
        text.startsWith(SignalingCommand.INCOMING_CALL.toString(), true) ->
          handleIncomingCall(text)
        text.startsWith(SignalingCommand.CALL_ACCEPTED.toString(), true) ->
          handleCallResponse(text, true)
        text.startsWith(SignalingCommand.CALL_REJECTED.toString(), true) ->
          handleCallResponse(text, false)
        text.startsWith(SignalingCommand.OFFER.toString(), true) ->
          handleSignalingCommand(SignalingCommand.OFFER, text)
        text.startsWith(SignalingCommand.ANSWER.toString(), true) ->
          handleSignalingCommand(SignalingCommand.ANSWER, text)
        text.startsWith(SignalingCommand.ICE.toString(), true) ->
          handleSignalingCommand(SignalingCommand.ICE, text)
      }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
      logger.d { "[onClosing] Connection closing: $code $reason" }
      _sessionStateFlow.value = WebRTCSessionState.Offline
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
      logger.e { "[onFailure] Connection failed: ${t.message}" }
      _sessionStateFlow.value = WebRTCSessionState.Error
    }
  }

  private fun handleStateMessage(message: String) {
    val state = message.substringAfter("${SignalingCommand.STATE} ")
    _sessionStateFlow.value = WebRTCSessionState.valueOf(state)
  }

  private fun handleOnlineUsers(message: String) {
    val users = message.substringAfter("${SignalingCommand.ONLINE_USERS} ")
      .split(",")
      .filter { it.isNotBlank() && it != userId }
    signalingScope.launch {
      _onlineUsersFlow.emit(users)
    }
  }

  private fun handleIncomingCall(message: String) {
    val callerId = message.substringAfter("${SignalingCommand.INCOMING_CALL} ")
    signalingScope.launch {
      _incomingCallFlow.emit(callerId)
    }
  }

  private fun handleCallResponse(message: String, accepted: Boolean) {
    val remoteUserId = message.substringAfter(
      if (accepted) "${SignalingCommand.CALL_ACCEPTED} "
      else "${SignalingCommand.CALL_REJECTED} "
    )
    signalingScope.launch {
      _callResponseFlow.emit(remoteUserId to accepted)
    }
  }

  private fun handleSignalingCommand(command: SignalingCommand, text: String) {
    val value = text.substringAfter("$command ")
    logger.d { "[handleSignalingCommand] $command $value" }
    signalingScope.launch {
      _signalingCommandFlow.emit(command to value)
    }
  }

  fun dispose() {
    ws.cancel()
    signalingScope.cancel()
  }

  enum class SignalingCommand {
    STATE,
    CALL_REQUEST,
    CALL_RESPONSE,
    INCOMING_CALL,
    CALL_ACCEPTED,
    CALL_REJECTED,
    ONLINE_USERS,
    OFFER,
    ANSWER,
    ICE
  }

  enum class WebRTCSessionState {
    Online,
    Offline,
    Error
  }

  companion object {
    const val ICE_SEPARATOR = ";;;;"
  }
}