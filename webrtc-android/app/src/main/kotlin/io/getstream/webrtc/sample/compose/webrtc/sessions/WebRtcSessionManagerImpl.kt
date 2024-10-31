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
  private val calleeId : String,
  private val currentUserId : String,
  override val signalingClient: SignalingClient,
  override val peerConnectionFactory: StreamPeerConnectionFactory
) : WebRtcSessionManager {
  private val logger by taggedLogger("Call:LocalWebRtcSessionManager")

  private val sessionManagerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // used to send local video track to the fragment
  private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()

//  private var _sessionStateFlow = MutableStateFlow(WebRTCSessionState.Ready)
//  override val sessionStateFlow: StateFlow<WebRTCSessionState>
//    get() = _sessionStateFlow

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
  private var remoteUserId: String? = null

  private val peerConnection: StreamPeerConnection by lazy {
    peerConnectionFactory.makePeerConnection(
      coroutineScope = sessionManagerScope,
      configuration = peerConnectionFactory.rtcConfig,
      type = StreamPeerType.SUBSCRIBER,
      mediaConstraints = mediaConstraints,
      onIceCandidateRequest = { iceCandidate, _ ->

        val targetId = if (offer != null) remoteUserId else calleeId
        val iceMessage = "$targetId$ICE_SEPARATOR${iceCandidate.sdpMid}$ICE_SEPARATOR${iceCandidate.sdpMLineIndex}$ICE_SEPARATOR${iceCandidate.sdp}"
        logger.d { "[ICE] sending ICE to $targetId" }
        signalingClient.sendCommand(
          SignalingCommand.ICE,iceMessage)
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
        .collect { commandToValue ->
          when (commandToValue.first) {
            SignalingCommand.OFFER -> handleOffer(commandToValue.second)
            SignalingCommand.ANSWER -> handleAnswer(commandToValue.second)
            SignalingCommand.ICE -> handleIce(commandToValue.second)
            SignalingCommand.END_CALL -> handleEndCall()
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
      _localVideoTrackFlow.emit(localVideoTrack)

      if (offer != null) {
        sendAnswer()
      } else {
        sendOffer()
      }
    }
  }

  override fun flipCamera() {
    (videoCapturer as? Camera2Capturer)?.switchCamera(null)
  }

  override fun enableMicrophone(enabled: Boolean) {
    audioManager?.isMicrophoneMute = !enabled
  }

  override fun enableLoudspeaker(enabled: Boolean) {
    audioManager?.setSpeakerphoneOn(enabled)
  }

  override fun enableCamera(enabled: Boolean) {
    if (enabled) {
      videoCapturer.startCapture(resolution.width, resolution.height, 30)
    } else {
      videoCapturer.stopCapture()
    }
  }

  override fun disconnect() {
    dispose()
  }

  private fun dispose() {
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

  override fun endCall() {
    val message = "$ICE_SEPARATOR${remoteUserId ?: currentUserId}"
    signalingClient.sendCommand(SignalingCommand.END_CALL,message)
    // After sending call end status we should dispose all signaling
    dispose()
  }

  private suspend fun sendOffer() {
    val offer = peerConnection.createOffer().getOrThrow()
    val result = peerConnection.setLocalDescription(offer)
    result.onSuccess {
      // Include calleeId to offer message
      val message = "$currentUserId$$calleeId$${offer.description}"
      signalingClient.sendCommand(SignalingCommand.OFFER, message)
      //_sessionStateFlow = MutableStateFlow(WebRTCSessionState.Creating)
    }
    //logger.d { "[SDP] send offer: ${offer.stringify()}" }
    logger.d { "[SDP] send offer: " }
  }

  private suspend fun sendAnswer() {
    val offerSdp = offer!!.split(ICE_SEPARATOR)
    //setting offer message exclude userId
    peerConnection.setRemoteDescription(
      SessionDescription(SessionDescription.Type.OFFER, offerSdp[2])
    )
    val answer = peerConnection.createAnswer().getOrThrow()
    val result = peerConnection.setLocalDescription(answer)
    result.onSuccess {
      // sending answer to remoteUser
      remoteUserId = offerSdp[0]
      val message = "$currentUserId$$remoteUserId$${answer.description}"
      logger.d { "[SDP] send answer to: remote userId: $remoteUserId" }
      signalingClient.sendCommand(SignalingCommand.ANSWER, message)
     // _sessionStateFlow = MutableStateFlow(WebRTCSessionState.Active)
    }
    //logger.d { "[SDP] send answer: ${answer.stringify()}" }
  }

  private fun handleOffer(sdp: String) {
    // This offer message contain userId of the callee.
    // we can check is this offer is valid for callee
    logger.d { "[SDP] handle offer: $sdp" }
    offer = sdp
  }

  private suspend fun handleAnswer(sdp: String) {
    val parts = sdp.split(ICE_SEPARATOR)
    // Answer message contain userId of the callee
    logger.d { "[SDP] handle answer: ${parts[0]} :: ${parts[1]}" }
    peerConnection.setRemoteDescription(
      SessionDescription(SessionDescription.Type.ANSWER, parts[2])
    )
  }

  private suspend fun handleIce(iceMessage: String) {
    val iceArray = iceMessage.split(ICE_SEPARATOR)
    logger.d { "[ICE] handle ice target user: ${iceArray[0]}"}
    peerConnection.addIceCandidate(
      IceCandidate(
        iceArray[1],
        iceArray[2].toInt(),
        iceArray[3]
      )
    )
  }

  private fun handleEndCall() {
    // getting response from server that call is ended
    logger.d { "[SDP] handle end call" }
    dispose()
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
