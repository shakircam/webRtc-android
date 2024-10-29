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
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SignalingClient(callerId: String, calleeId: String ) {
  private val logger by taggedLogger("Call:SignalingClient")
  private val signalingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val client = OkHttpClient()
  private val request = Request
    .Builder()
    .url("${BuildConfig.SIGNALING_SERVER_IP_ADDRESS}/$callerId/$calleeId")
    .build()

  // opening web socket with signaling server
  private val ws = client.newWebSocket(request, SignalingWebSocketListener())


  private val _signalingCommandFlow = MutableSharedFlow<Pair<SignalingCommand, String>>()
  val signalingCommandFlow: SharedFlow<Pair<SignalingCommand, String>> = _signalingCommandFlow

  fun sendCommand(signalingCommand: SignalingCommand, message: String) {
    logger.d { "[sendCommand] $signalingCommand $message" }
    ws.send("$signalingCommand $message")
  }

  private inner class SignalingWebSocketListener : WebSocketListener() {
    override fun onMessage(webSocket: WebSocket, text: String) {
      when {
//        text.startsWith(SignalingCommand.STATE.toString(), true) ->
//          handleStateMessage(text)
//        text.startsWith(SignalingCommand.OFFER.toString(), true) ->
//          handleSignalingCommand(SignalingCommand.OFFER, text)
//        text.startsWith(SignalingCommand.ANSWER.toString(), true) ->
//          handleSignalingCommand(SignalingCommand.ANSWER, text)
//        text.startsWith(SignalingCommand.ICE.toString(), true) ->
//          handleSignalingCommand(SignalingCommand.ICE, text)
        text.startsWith(SignalingCommand.STATE.toString(), true) -> {
          val content = text.substringAfter(' ')
          handleStateMessage(content)
        }
        text.startsWith(SignalingCommand.OFFER.toString(), true) -> {
          val content = text.substringAfter(' ')
          handleSignalingCommand(SignalingCommand.OFFER, content)
        }
        text.startsWith(SignalingCommand.ANSWER.toString(), true) -> {
          val content = text.substringAfter(' ')
          handleSignalingCommand(SignalingCommand.ANSWER, content)
        }
        text.startsWith(SignalingCommand.ICE.toString(), true) -> {
          val content = text.substringAfter(' ')
          handleSignalingCommand(SignalingCommand.ICE, content)
        }
      }
    }
  }

  private fun handleSignalingCommand(command: SignalingCommand, text: String) {
   // val value = getSeparatedMessage(text)
    logger.d { "[handleSignalingCommand] $command message: $text" }
    signalingScope.launch {
      _signalingCommandFlow.emit(command to text)
    }
  }

  private fun handleStateMessage(message: String) {
    val state = getSeparatedMessage(message)
    logger.d { "received state message: $state" }
  }

  private fun getSeparatedMessage(text: String) = text.substringAfter(' ')

  fun dispose() {
    signalingScope.cancel()
    ws.cancel()
  }
}

enum class WebRTCSessionState {
  Calling,
  Answer
}

enum class SignalingCommand {
  STATE, // Command for WebRTCSessionState
  OFFER, // to send or receive offer
  ANSWER, // to send or receive answer
  ICE // to send and receive ice candidates
}
