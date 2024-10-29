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
import io.getstream.webrtc.sample.compose.webrtc.SignalingCommand
import io.getstream.webrtc.sample.compose.webrtc.WebRTCSessionState
import io.getstream.webrtc.sample.compose.webrtc.audio.AudioHandler
import io.getstream.webrtc.sample.compose.webrtc.audio.AudioSwitchHandler
import io.getstream.webrtc.sample.compose.webrtc.peer.StreamPeerConnection
import io.getstream.webrtc.sample.compose.webrtc.peer.StreamPeerConnectionFactory
import io.getstream.webrtc.sample.compose.webrtc.peer.StreamPeerType
import io.getstream.webrtc.sample.compose.webrtc.utils.stringify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
  private val currentUserId : String,
  private val calleeId : String,
  override val signalingClient: SignalingClient,
  override val peerConnectionFactory: StreamPeerConnectionFactory
) : WebRtcSessionManager {
  private val logger by taggedLogger("Call:LocalWebRtcSessionManager")

  private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // session flow to send information about the session state to the subscribers
  private val _sessionStateFlow = MutableStateFlow(WebRTCSessionState.Calling)
 // val sessionStateFlow: StateFlow<WebRTCSessionState> = _sessionStateFlow
 override val sessionStateFlow: StateFlow<WebRTCSessionState>
   get() = _sessionStateFlow

  // used to send local video track to the fragment
  private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()

  override val localVideoTrackFlow: SharedFlow<VideoTrack> = _localVideoTrackFlow

  // used to send remote video track to the sender
  private val _remoteVideoTrackFlow = MutableSharedFlow<VideoTrack>()
  override val remoteVideoTrackFlow: SharedFlow<VideoTrack> = _remoteVideoTrackFlow

  // declaring video constraints and setting OfferToReceiveVideo to true
  // this step is mandatory to create valid offer and answer
  private val mediaConstraints = MediaConstraints().apply {
    mandatory.addAll(
      listOf(
        MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"),
        MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")
      )
    )
  }

  // getting front camera
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
      } ?: error("There is no matched resolution!")
    }

  // we need it to initialize video capturer
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

  /** Audio properties */

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

  private var offer: String? = null

  private val peerConnection: StreamPeerConnection by lazy {
    peerConnectionFactory.makePeerConnection(
      coroutineScope = sessionManagerScope,
      configuration = peerConnectionFactory.rtcConfig,
      type = StreamPeerType.SUBSCRIBER,
      mediaConstraints = mediaConstraints,
      onIceCandidateRequest = { iceCandidate, _ ->
        val iceMessage = "$currentUserId$ICE_SEPARATOR${iceCandidate.sdpMid}$ICE_SEPARATOR${iceCandidate.sdpMLineIndex}$ICE_SEPARATOR${iceCandidate.sdp}"
        signalingClient.sendCommand(
          SignalingCommand.ICE,
          iceMessage)
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
    sessionManagerScope.launch {
      signalingClient.signalingCommandFlow
        .collect { (command, message) ->
          logger.d { "[SignalingFlow] Received $command with message: $message" }
          when (command) {
            SignalingCommand.OFFER -> handleOffer(message)
            SignalingCommand.ANSWER -> handleAnswer(message)
            SignalingCommand.ICE -> handleIce(message)
            else -> Unit
          }
        }
    }
  }

  override fun onSessionScreenReady() {
    setupAudio()
    peerConnection.connection.addTrack(localVideoTrack)
    peerConnection.connection.addTrack(localAudioTrack)
    sessionManagerScope.launch {
      // sending local video track to show local video from start
      try {
        _localVideoTrackFlow.emit(localVideoTrack)

        if (offer != null) {
          logger.d { "[onSessionScreenReady] Received offer, sending answer" }
          sendAnswer()
        } else {
          logger.d { "[onSessionScreenReady] Initiating call to $calleeId" }
          sendOffer(calleeId)
        }
      } catch (e:Exception){
        logger.e { "[onSessionScreenReady] Error: ${e.message}" }
      }
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
    // dispose audio & video tracks.
    remoteVideoTrackFlow.replayCache.forEach { videoTrack ->
      videoTrack.dispose()
    }
    localVideoTrackFlow.replayCache.forEach { videoTrack ->
      videoTrack.dispose()
    }
    localAudioTrack.dispose()
    localVideoTrack.dispose()

    // dispose audio handler and video capturer.
    audioHandler.stop()
    videoCapturer.stopCapture()
    videoCapturer.dispose()

    // dispose signaling clients and socket.
    signalingClient.dispose()
  }

  private suspend fun sendOffer(calleeId: String) {
    try {
      val offer = peerConnection.createOffer().getOrThrow()
      peerConnection.setLocalDescription(offer).onSuccess {
        val message = "$calleeId ${offer.description}"
        signalingClient.sendCommand(SignalingCommand.OFFER, message)
        logger.d { "[sendOffer] Offer sent to $calleeId" }
      }
    } catch (e: Exception) {
      logger.e { "[sendOffer] Failed to send offer: ${e.message}" }
    }
  }

  private suspend fun sendAnswer() {
    try {
      if (offer == null) {
        logger.e { "[sendAnswer] No offer to answer" }
        return
      }

      peerConnection.setRemoteDescription(
        SessionDescription(SessionDescription.Type.OFFER, offer!!)
      ).onSuccess {
        logger.d { "[sendAnswer] Remote description set" }

        peerConnection.createAnswer().getOrThrow().let { answer ->
          peerConnection.setLocalDescription(answer).onSuccess {
            val message = "$currentUserId ${answer.description}"
            signalingClient.sendCommand(SignalingCommand.ANSWER, message)
            logger.d { "[sendAnswer] Answer sent" }
          }
        }
      }
    } catch (e: Exception) {
      logger.e { "[sendAnswer] Failed to send answer: ${e.message}" }
    }
  }

  private fun handleOffer(sdp: String) {
    try {
      val components = sdp.split(" ", limit = 2)
      if (components.size != 2) {
        logger.e { "[handleOffer] Invalid offer format: $sdp" }
        return
      }
      val targetUserId = components[0]
      val offerDescription = components[1]
      logger.d { "[Handle offer] for target userId: $targetUserId" }
      if (targetUserId == currentUserId) {  // `localUserId` should be a unique identifier for the device
        logger.d { "[Handle offer] offer: $offerDescription" }
        offer = offerDescription
        _sessionStateFlow.value = WebRTCSessionState.Answer
      } else {
        logger.d { "[Handle offer] received for different user, ignoring." }
      }
    } catch (e:Exception){
      logger.e { "[Handle offer] error handling offer: ${e.message}" }
    }
  }

  private suspend fun handleAnswer(sdp: String) {
    try {
      val components = sdp.split(" ", limit = 2)
      if (components.size != 2) {
        logger.e { "[handleAnswer] Invalid answer format: $sdp" }
        return
      }

      val sourceUserId = components[0]
      val answerDescription = components[1]

      logger.d { "[handleAnswer] Processing answer from $sourceUserId" }
      peerConnection.setRemoteDescription(
        SessionDescription(SessionDescription.Type.ANSWER, answerDescription)
      )
    } catch (e: Exception) {
      logger.e { "[handleAnswer] Error handling answer: ${e.message}" }
    }
  }

  private suspend fun handleIce(iceMessage: String) {
    try {
      val iceArray = iceMessage.split(ICE_SEPARATOR)
      if (iceArray.size != 4) {
        logger.e { "[handleIce] Invalid ICE format: $iceMessage" }
        return
      }
      val targetUserId = iceArray[0] // Extract the target user ID
      if (targetUserId != currentUserId) {
        logger.d { "[handleIce] Ignoring ICE for user $targetUserId" }
        return
      }
      val sdpMid = iceArray[1]
      val sdpMLineIndex = iceArray[2].toInt()
      val candidate = iceArray[3]

      logger.d { "[handleIce] Adding ICE candidate" }
      peerConnection.addIceCandidate(
        IceCandidate(sdpMid, sdpMLineIndex, candidate)
      )
    } catch (e: Exception) {
      logger.e { "[handleIce] Error handling ICE candidate: ${e.message}" }
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
}
