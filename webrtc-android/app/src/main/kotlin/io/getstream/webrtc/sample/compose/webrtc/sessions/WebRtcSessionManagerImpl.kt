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
import org.webrtc.RtpTransceiver
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
  private var isTracksAdded = false
  private var isCallSetupComplete = false
  private var peerConnection: StreamPeerConnection? = null

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

  private fun createPeerConnection() {
    logger.d { "[createPeerConnection] Creating new peer connection" }
    peerConnection = peerConnectionFactory.makePeerConnection(
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
        logger.d { "[onVideoTrack] Received video track, transceiver: $rtpTransceiver" }
        val track = rtpTransceiver?.receiver?.track() ?: return@makePeerConnection
        if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
          val videoTrack = track as VideoTrack
          logger.d { "[onVideoTrack] Emitting remote video track" }
          videoTrack.setEnabled(true)
          sessionManagerScope.launch {
            _remoteVideoTrackFlow.emit(videoTrack)
          }
        }
      },
      onStreamAdded = { stream ->
        logger.d { "[onStreamAdded] Stream added: ${stream.videoTracks.size} video tracks" }
        stream.videoTracks.firstOrNull()?.let { videoTrack ->
          videoTrack.setEnabled(true)
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
          val isOutgoingCall = _callStateFlow.value is CallState.OutgoingCall
          setupCall()
          if (isOutgoingCall) {
            sendOffer()
          }
        } else {
          _callStateFlow.value = CallState.Rejected(remoteUserId)
          cleanupCall()
        }
      }
    }

    // Monitor signaling commands
    sessionManagerScope.launch {
      signalingClient.signalingCommandFlow.collect { (command, value) ->
        logger.d { "[SignalingCommand] Received: $command" }
        when (command) {
          SignalingClient.SignalingCommand.OFFER -> {
            if (_callStateFlow.value is CallState.Connected) {
              handleOffer(value)
            }
          }
          SignalingClient.SignalingCommand.ANSWER -> {
            if (_callStateFlow.value is CallState.Connected) {
              handleAnswer(value)
            }
          }
          SignalingClient.SignalingCommand.ICE -> handleIce(value)
          else -> Unit
        }
      }
    }
  }

  override fun startCall(targetUserId: String) {
    cleanupCall()
    _callStateFlow.value = CallState.OutgoingCall(targetUserId)
    signalingClient.initiateCall(targetUserId)
  }

  override fun acceptIncomingCall() {
    val currentState = _callStateFlow.value as? CallState.IncomingCall ?: return
    cleanupCall()
    signalingClient.acceptCall(currentState.remoteUserId)
    _callStateFlow.value = CallState.Connected(currentState.remoteUserId)
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
    if (isCallSetupComplete) {
      logger.d { "[setupCall] Call setup already complete" }
      return
    }

    logger.d { "[setupCall] Setting up call" }

    try {
      // Create new peer connection if needed
      if (peerConnection == null) {
        createPeerConnection()
      }

      // Setup audio first
      setupAudio()

      peerConnection?.connection?.let { connection ->
        // Add transceivers for sending and receiving media
        connection.addTransceiver(
          localVideoTrack,
          RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )
        connection.addTransceiver(
          localAudioTrack,
          RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        )

        // Store senders
        localVideoSender = connection.getSenders().find { it.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND }
        localAudioSender = connection.getSenders().find { it.track()?.kind() == MediaStreamTrack.AUDIO_TRACK_KIND }
      }

      // Emit local video track
      sessionManagerScope.launch {
        _localVideoTrackFlow.emit(localVideoTrack)
      }

      isCallSetupComplete = true
      logger.d { "[setupCall] Call setup completed successfully" }
    } catch (e: Exception) {
      logger.e { "[setupCall] Error setting up call: ${e.message}" }
      _callStateFlow.value = CallState.Error("Failed to setup call: ${e.message}")
      cleanupCall()
    }
  }

  private fun cleanupCall() {
    logger.d { "[cleanupCall] Cleaning up call resources" }

    try {
      // Clean up video tracks
      remoteVideoTrackFlow.replayCache.forEach { it.dispose() }

      // Remove tracks from peer connection
      peerConnection?.connection?.let { connection ->
        localVideoSender?.let { sender ->
          connection.removeTrack(sender)
        }
        localAudioSender?.let { sender ->
          connection.removeTrack(sender)
        }
      }

      // Clear senders
      localVideoSender = null
      localAudioSender = null

      // Close and clear peer connection
      peerConnection?.connection?.close()
      peerConnection = null

      // Reset state
      isCallSetupComplete = false

    } catch (e: Exception) {
      logger.e { "[cleanupCall] Error during cleanup: ${e.message}" }
    }
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
    cleanupCall()
    signalingClient.dispose()
    sessionManagerScope.cancel()
  }

  private suspend fun sendOffer() {
    logger.d { "[sendOffer] Creating and sending offer" }
    try {
      val offer = peerConnection?.createOffer()?.getOrThrow()
      offer?.let { offerDes->
        peerConnection?.setLocalDescription(offerDes)?.onSuccess {
          signalingClient.sendCommand(SignalingClient.SignalingCommand.OFFER, offerDes.description)
        }
      }
    } catch (e: Exception) {
      _callStateFlow.value = CallState.Error("Failed to create offer: ${e.message}")
      disconnect()
    }
  }

  private suspend fun sendAnswer() {
    logger.d { "[sendAnswer] Creating and sending answer" }
    try {
      val answer = peerConnection?.createAnswer()?.getOrThrow()
      answer?.let { answerDescription ->
        peerConnection?.setLocalDescription(answerDescription)?.onSuccess {
          signalingClient.sendCommand(SignalingClient.SignalingCommand.ANSWER, answerDescription.description)
        }
      }
    } catch (e: Exception) {
      logger.e { "[sendAnswer] Error: ${e.message}" }
      _callStateFlow.value = CallState.Error("Failed to create answer: ${e.message}")
      disconnect()
    }
  }

  private suspend fun handleOffer(sdp: String) {
    logger.d { "[SDP] handle offer: $sdp" }
    try {
      peerConnection?.setRemoteDescription(
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
      peerConnection?.setRemoteDescription(
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
      peerConnection?.addIceCandidate(
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