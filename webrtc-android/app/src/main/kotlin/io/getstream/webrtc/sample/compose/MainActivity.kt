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

package io.getstream.webrtc.sample.compose

import android.Manifest
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.getstream.webrtc.sample.compose.ui.components.VideoRenderer
import io.getstream.webrtc.sample.compose.ui.screens.video.CallAction
import io.getstream.webrtc.sample.compose.ui.screens.video.FloatingVideoRenderer
import io.getstream.webrtc.sample.compose.ui.screens.video.VideoCallControls
import io.getstream.webrtc.sample.compose.ui.screens.video.VideoCallScreen
import io.getstream.webrtc.sample.compose.ui.theme.WebrtcSampleComposeTheme
import io.getstream.webrtc.sample.compose.webrtc.SignalingClient
import io.getstream.webrtc.sample.compose.webrtc.peer.StreamPeerConnectionFactory
import io.getstream.webrtc.sample.compose.webrtc.sessions.LocalWebRtcSessionManager
import io.getstream.webrtc.sample.compose.webrtc.sessions.WebRtcSessionManager
import io.getstream.webrtc.sample.compose.webrtc.sessions.WebRtcSessionManagerImpl
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 0)

    val sessionManager: WebRtcSessionManager = WebRtcSessionManagerImpl(
      context = this,
      signalingClient = SignalingClient(userId = "2"),
      peerConnectionFactory = StreamPeerConnectionFactory(applicationContext),
      userId = "2"
    )

    setContent {
      WebrtcSampleComposeTheme {
        CompositionLocalProvider(LocalWebRtcSessionManager provides sessionManager) {
          // A surface container using the 'background' color from the theme
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
          ) {
            MainScreen()
          }
        }
      }
    }

  }

  @Composable
  fun MainScreen() {
    val sessionManager = LocalWebRtcSessionManager.current
    val callState by sessionManager.callStateFlow.collectAsState()

    when (callState) {
      is WebRtcSessionManagerImpl.CallState.Connected -> {
        VideoCallScreen()
      }
      else -> {
        TestCallScreen()
      }
    }
  }

  @Composable
  fun TestCallScreen() {
    val sessionManager = LocalWebRtcSessionManager.current
    val callState by sessionManager.callStateFlow.collectAsState()
    val onlineUsers by sessionManager.availableUsersFlow.collectAsState()
    var targetUserId by remember { mutableStateOf("") }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
    ) {
      // Status Section
      Text("Your ID: ${sessionManager.signalingClient.userId}")
      Text("Call State: ${callState::class.simpleName}")
      Text("Online Users: ${onlineUsers.joinToString()}")

      SpacerHeight(height = 16.dp)

      // Call Controls
      when (callState) {
        is WebRtcSessionManagerImpl.CallState.Idle -> {
          OutlinedTextField(
            value = targetUserId,
            onValueChange = { targetUserId = it },
            label = { Text("Enter Target User ID") }
          )

          Button(
            onClick = { sessionManager.startCall(targetUserId) },
            enabled = targetUserId.isNotBlank()
          ) {
            Text("Call")
          }
        }

        is WebRtcSessionManagerImpl.CallState.IncomingCall -> {
          Text("Incoming call from: ${(callState as WebRtcSessionManagerImpl.CallState.IncomingCall).remoteUserId}")
          Row {
            Button(onClick = { sessionManager.acceptIncomingCall() }) {
              Text("Accept")
            }
            Spacer(width = 8.dp)
            Button(onClick = { sessionManager.rejectIncomingCall() }) {
              Text("Reject")
            }
          }
        }

        is WebRtcSessionManagerImpl.CallState.OutgoingCall -> {
          Text("Calling ${(callState as WebRtcSessionManagerImpl.CallState.OutgoingCall).remoteUserId}...")
          Button(onClick = { sessionManager.disconnect() }) {
            Text("Cancel")
          }
        }

        is WebRtcSessionManagerImpl.CallState.Rejected -> {
          Text("Call rejected")
          Button(onClick = { sessionManager.disconnect() }) {
            Text("OK")
          }
        }

        is WebRtcSessionManagerImpl.CallState.Error -> {
          Text("Error: ${(callState as WebRtcSessionManagerImpl.CallState.Error).message}")
          Button(onClick = { sessionManager.disconnect() }) {
            Text("OK")
          }
        }

        else -> {
          // For other states, show nothing or a loading indicator
        }
      }
    }
  }

  @Composable
   fun Spacer(width: Dp) {
    Spacer(modifier = Modifier.width(width))
  }

  @Composable
   fun SpacerHeight(height: Dp) {
    Spacer(modifier = Modifier.height(height))
  }

}
