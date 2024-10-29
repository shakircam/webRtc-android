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

package io.getstream.webrtc.sample.compose.webrtc.sessions

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.getSystemService
import io.getstream.log.taggedLogger
import io.getstream.webrtc.sample.compose.webrtc.SignalingClient
import io.getstream.webrtc.sample.compose.webrtc.audio.AudioHandler
import io.getstream.webrtc.sample.compose.webrtc.audio.AudioSwitchHandler
import io.getstream.webrtc.sample.compose.webrtc.peer.StreamPeerConnection
import io.getstream.webrtc.sample.compose.webrtc.peer.StreamPeerConnectionFactory
import io.getstream.webrtc.sample.compose.webrtc.peer.StreamPeerType
import io.getstream.webrtc.sample.compose.webrtc.utils.stringify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerationAndroid
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpSender
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import java.util.UUID

private const val ICE_SEPARATOR = '$'

val LocalWebRtcSessionManager: ProvidableCompositionLocal<WebRtcSessionManager> =
  staticCompositionLocalOf { error("WebRtcSessionManager was not initialized!") }

class WebRtcSessionManagerImpl(
  private val context: Context,
  private val userId: String,
  override val signalingClient: SignalingClient,
  override val peerConnectionFactory: StreamPeerConnectionFactory
) : WebRtcSessionManager {
  private val logger by taggedLogger("Call:LocalWebRtcSessionManager")
  private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _availableUsersFlow = MutableStateFlow<List<String>>(emptyList())
  override val availableUsersFlow: StateFlow<List<String>> = _availableUsersFlow

  private val _callStateFlow = MutableStateFlow<CallState>(CallState.Idle)
  override val callStateFlow: StateFlow<CallState> = _callStateFlow

  // Store RtpSenders when adding tracks
  private var localVideoSender: RtpSender? = null
  private var localAudioSender: RtpSender? = null

  private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()
  override val localVideoTrackFlow: SharedFlow<VideoTrack> = _localVideoTrackFlow

  private val _remoteVideoTrackFlow = MutableSharedFlow<VideoTrack>()
  override val remoteVideoTrackFlow: SharedFlow<VideoTrack> = _remoteVideoTrackFlow

  private val mediaConstraints = MediaConstraints().apply {
    mandatory.addAll(
      listOf(
        MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
        MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
      )
    )
  }

  private val videoCapturer: VideoCapturer by lazy { buildCameraCapturer() }
  private val cameraManager by lazy { context.getSystemService<CameraManager>() }
  private val cameraEnumerator: Camera2Enumerator by lazy {
    Camera2Enumerator(context)
  }

  private val resolution: CameraEnumerationAndroid.CaptureFormat
    get() {
      val frontCamera = cameraEnumerator.deviceNames.first { cameraName ->
        cameraEnumerator.isFrontFacing(cameraName)
      }
      val supportedFormats = cameraEnumerator.getSupportedFormats(frontCamera) ?: emptyList()
      return supportedFormats.firstOrNull {
        (it.width == 720 || it.width == 480 || it.width == 360)
      } ?: error("No supported resolution found!")
    }

  private val surfaceTextureHelper = SurfaceTextureHelper.create(
    "SurfaceTextureHelperThread",
    peerConnectionFactory.eglBaseContext
  )

  private val videoSource by lazy {
    peerConnectionFactory.makeVideoSource(videoCapturer.isScreencast).apply {
      videoCapturer.initialize(surfaceTextureHelper, context, this.capturerObserver)
      videoCapturer.startCapture(resolution.width, resolution.height, 30)
    }
  }

  private val localVideoTrack: VideoTrack by lazy {
    peerConnectionFactory.makeVideoTrack(
      source = videoSource,
      trackId = "Video${UUID.randomUUID()}"
    )
  }

  private val audioHandler: AudioHandler by lazy {
    AudioSwitchHandler(context)
  }

  private val audioManager by lazy {
    context.getSystemService<AudioManager>()
  }

  private val audioConstraints: MediaConstraints by lazy {
    buildAudioConstraints()
  }

  private val audioSource by lazy {
    peerConnectionFactory.makeAudioSource(audioConstraints)
  }

  private val localAudioTrack: AudioTrack by lazy {
    peerConnectionFactory.makeAudioTrack(
      source = audioSource,
      trackId = "Audio${UUID.randomUUID()}"
    )
  }

  private val peerConnection: StreamPeerConnection by lazy {
    peerConnectionFactory.makePeerConnection(
      coroutineScope = sessionManagerScope,
      configuration = peerConnectionFactory.rtcConfig,
      type = StreamPeerType.SUBSCRIBER,
      mediaConstraints = mediaConstraints,
      onIceCandidateRequest = { iceCandidate, _ ->
        signalingClient.sendCommand(
          SignalingClient.SignalingCommand.ICE,
          "${iceCandidate.sdpMid}${SignalingClient.ICE_SEPARATOR}${iceCandidate.sdpMLineIndex}${SignalingClient.ICE_SEPARATOR}${iceCandidate.sdp}"
        )
      },
      onVideoTrack = { rtpTransceiver ->
        val track = rtpTransceiver?.receiver?.track() ?: return@makePeerConnection
        if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
          val videoTrack = track as VideoTrack
          sessionManagerScope.launch {
            _remoteVideoTrackFlow.emit(videoTrack)
          }
        }
      }
    )
  }

  init {
    // Monitor online users
    sessionManagerScope.launch {
      signalingClient.onlineUsersFlow.collect { users ->
        _availableUsersFlow.value = users
      }
    }

    // Monitor incoming calls
    sessionManagerScope.launch {
      signalingClient.incomingCallFlow.collect { callerId ->
        _callStateFlow.value = CallState.IncomingCall(callerId)
      }
    }

    // Monitor call responses
    sessionManagerScope.launch {
      signalingClient.callResponseFlow.collect { (remoteUserId, accepted) ->
        if (accepted) {
          _callStateFlow.value = CallState.Connected(remoteUserId)
          setupCall()
        } else {
          _callStateFlow.value = CallState.Rejected(remoteUserId)
          cleanupCall()
        }
      }
    }

    // Monitor signaling commands
    sessionManagerScope.launch {
      signalingClient.signalingCommandFlow
        .collect { commandToValue ->
          when (commandToValue.first) {
            SignalingClient.SignalingCommand.OFFER -> handleOffer(commandToValue.second)
            SignalingClient.SignalingCommand.ANSWER -> handleAnswer(commandToValue.second)
            SignalingClient.SignalingCommand.ICE -> handleIce(commandToValue.second)
            else -> Unit
          }
        }
    }
  }

  override fun startCall(targetUserId: String) {
    _callStateFlow.value = CallState.OutgoingCall(targetUserId)
    signalingClient.initiateCall(targetUserId)
  }

  override fun acceptIncomingCall() {
    val currentState = _callStateFlow.value as? CallState.IncomingCall ?: return
    signalingClient.acceptCall(currentState.remoteUserId)
    _callStateFlow.value = CallState.Connected(currentState.remoteUserId)
    setupCall()
  }

  override fun rejectIncomingCall() {
    val currentState = _callStateFlow.value as? CallState.IncomingCall ?: return
    signalingClient.rejectCall(currentState.remoteUserId)
    _callStateFlow.value = CallState.Idle
    cleanupCall()
  }

  override fun onSessionScreenReady() {
    if (_callStateFlow.value is CallState.Connected) {
      setupCall()
    }
  }

  private fun setupCall() {
    setupAudio()
    // Store the senders when adding tracks
    localVideoSender = peerConnection.connection.addTrack(localVideoTrack)
    localAudioSender = peerConnection.connection.addTrack(localAudioTrack)

    sessionManagerScope.launch {
      _localVideoTrackFlow.emit(localVideoTrack)

      when (val state = _callStateFlow.value) {
        is CallState.Connected -> {
          if (_callStateFlow.value is CallState.OutgoingCall) {
            sendOffer()
          }
        }
        else -> Unit
      }
    }
  }

  private fun cleanupCall() {
    remoteVideoTrackFlow.replayCache.forEach { it.dispose() }

    // Remove senders instead of tracks
    localVideoSender?.let { sender ->
      peerConnection.connection.removeTrack(sender)
    }
    localAudioSender?.let { sender ->
      peerConnection.connection.removeTrack(sender)
    }

    // Clear the senders
    localVideoSender = null
    localAudioSender = null
  }

  override fun flipCamera() {
    (videoCapturer as? Camera2Capturer)?.switchCamera(null)
  }

  override fun enableMicrophone(enabled: Boolean) {
    audioManager?.isMicrophoneMute = !enabled
  }

  override fun enableCamera(enabled: Boolean) {
    if (enabled) {
      videoCapturer.startCapture(resolution.width, resolution.height, 30)
    } else {
      videoCapturer.stopCapture()
    }
  }

  override fun disconnect() {
    _callStateFlow.value = CallState.Idle

    // Clean up tracks
    remoteVideoTrackFlow.replayCache.forEach { videoTrack ->
      videoTrack.dispose()
    }
    localVideoTrackFlow.replayCache.forEach { videoTrack ->
      videoTrack.dispose()
    }
    localAudioTrack.dispose()
    localVideoTrack.dispose()

    // Clean up senders
    cleanupCall()

    // Clean up other resources
    audioHandler.stop()
    videoCapturer.stopCapture()
    videoCapturer.dispose()

    signalingClient.dispose()
    sessionManagerScope.cancel()
  }

  private suspend fun sendOffer() {
    try {
      val offer = peerConnection.createOffer().getOrThrow()
      val result = peerConnection.setLocalDescription(offer)
      result.onSuccess {
        signalingClient.sendCommand(SignalingClient.SignalingCommand.OFFER, offer.description)
      }.onFailure { error ->
        _callStateFlow.value = CallState.Error("Failed to create offer: ${error.message}")
        disconnect()
      }
      logger.d { "[SDP] send offer: ${offer.stringify()}" }
    } catch (e: Exception) {
      _callStateFlow.value = CallState.Error("Failed to create offer: ${e.message}")
      disconnect()
    }
  }

  private suspend fun sendAnswer() {
    try {
      val answer = peerConnection.createAnswer().getOrThrow()
      val result = peerConnection.setLocalDescription(answer)
      result.onSuccess {
        signalingClient.sendCommand(SignalingClient.SignalingCommand.ANSWER, answer.description)
      }.onFailure { error ->
        _callStateFlow.value = CallState.Error("Failed to create answer: ${error.message}")
        disconnect()
      }
      logger.d { "[SDP] send answer: ${answer.stringify()}" }
    } catch (e: Exception) {
      _callStateFlow.value = CallState.Error("Failed to create answer: ${e.message}")
      disconnect()
    }
  }

  private suspend fun handleOffer(sdp: String) {
    logger.d { "[SDP] handle offer: $sdp" }
    try {
      peerConnection.setRemoteDescription(
        SessionDescription(SessionDescription.Type.OFFER, sdp)
      )
      sendAnswer()
    } catch (e: Exception) {
      _callStateFlow.value = CallState.Error("Failed to handle offer: ${e.message}")
      disconnect()
    }
  }

  private suspend fun handleAnswer(sdp: String) {
    logger.d { "[SDP] handle answer: $sdp" }
    try {
      peerConnection.setRemoteDescription(
        SessionDescription(SessionDescription.Type.ANSWER, sdp)
      )
    } catch (e: Exception) {
      _callStateFlow.value = CallState.Error("Failed to handle answer: ${e.message}")
      disconnect()
    }
  }

  private suspend fun handleIce(iceMessage: String) {
    try {
      val iceArray = iceMessage.split(SignalingClient.ICE_SEPARATOR)
      peerConnection.addIceCandidate(
        IceCandidate(
          iceArray[0],
          iceArray[1].toInt(),
          iceArray[2]
        )
      )
    } catch (e: Exception) {
      logger.e { "[ICE] Failed to handle ICE candidate: ${e.message}" }
    }
  }

  private fun buildCameraCapturer(): VideoCapturer {
    val manager = cameraManager ?: throw RuntimeException("CameraManager was not initialized!")

    val ids = manager.cameraIdList
    var foundCamera = false
    var cameraId = ""

    for (id in ids) {
      val characteristics = manager.getCameraCharacteristics(id)
      val cameraLensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)

      if (cameraLensFacing == CameraMetadata.LENS_FACING_FRONT) {
        foundCamera = true
        cameraId = id
      }
    }

    if (!foundCamera && ids.isNotEmpty()) {
      cameraId = ids.first()
    }

    val camera2Capturer = Camera2Capturer(context, cameraId, null)
    return camera2Capturer
  }

  private fun buildAudioConstraints(): MediaConstraints {
    val mediaConstraints = MediaConstraints()
    val items = listOf(
      MediaConstraints.KeyValuePair(
        "googEchoCancellation",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googAutoGainControl",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googHighpassFilter",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googNoiseSuppression",
        true.toString()
      ),
      MediaConstraints.KeyValuePair(
        "googTypingNoiseDetection",
        true.toString()
      )
    )

    return mediaConstraints.apply {
      with(optional) {
        add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        addAll(items)
      }
    }
  }

  private fun setupAudio() {
    logger.d { "[setupAudio] #sfu; no args" }
    audioHandler.start()
    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val devices = audioManager?.availableCommunicationDevices ?: return
      val deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

      val device = devices.firstOrNull { it.type == deviceType } ?: return

      val isCommunicationDeviceSet = audioManager?.setCommunicationDevice(device)
      logger.d { "[setupAudio] #sfu; isCommunicationDeviceSet: $isCommunicationDeviceSet" }
    }
  }

  sealed class CallState {
    object Idle : CallState()
    data class IncomingCall(val remoteUserId: String) : CallState()
    data class OutgoingCall(val remoteUserId: String) : CallState()
    data class Connected(val remoteUserId: String) : CallState()
    data class Rejected(val remoteUserId: String) : CallState()
    data class Error(val message: String) : CallState()
  }
}