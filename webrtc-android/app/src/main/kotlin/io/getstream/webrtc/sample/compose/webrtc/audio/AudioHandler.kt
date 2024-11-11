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

package io.getstream.webrtc.sample.compose.webrtc.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.getstream.log.StreamLog
import io.getstream.log.taggedLogger

interface AudioHandler {
  /**
   * Called when a room is started.
   */
  fun start()

  /**
   * Called when a room is disconnected.
   */
  fun stop()

  fun enablePhoneSpeaker(enable: Boolean)
}



/**
 This code working on android 9 to 15 for earphone and Earpiece.Loud speaker is not working to all android versions.
 */

class AudioSwitchHandler constructor(private val context: Context) : AudioHandler {
  private var audioSwitch: AudioSwitch? = null
  private val handler = Handler(Looper.getMainLooper())
  private val logger by taggedLogger("Call:AudioSwitchHandler")
  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var isCallActive = false
  private var isSpeakerEnabled = false
  private var isHeadsetPlugged = false

  // Check Android version
  private val isAndroid13Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  private val audioDeviceChangeListener: AudioDeviceChangeListener = { audioDevices, selectedDevice ->
    logger.d { "[audioDeviceChangeListener] devices: $audioDevices, selected: $selectedDevice" }
    if (isCallActive) {
      handler.post {
        when (selectedDevice) {
          is AudioDevice.WiredHeadset -> {
            isHeadsetPlugged = true
            // Handle headset for all Android versions
            forceAudioMode()
            audioManager.isSpeakerphoneOn = false
            setAppropriateVolume(false)
            // Force wired headset routing
            if (isAndroid13Plus) {
              audioManager.apply {
                mode = AudioManager.MODE_IN_COMMUNICATION
                isSpeakerphoneOn = false
                // Force routing to headset
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  val devices = availableCommunicationDevices
                  devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let { device ->
                    setCommunicationDevice(device)
                  }
                }
              }
            }
          }
          is AudioDevice.Speakerphone -> {
            if (isAndroid13Plus && !isHeadsetPlugged) {
              // Only enable speaker if no headset is connected
              forceAudioMode()
              audioManager.isSpeakerphoneOn = true
              setAppropriateVolume(true)
            }
          }
          is AudioDevice.Earpiece -> {
            isHeadsetPlugged = false
            forceAudioMode()
            audioManager.isSpeakerphoneOn = false
            setAppropriateVolume(false)
          }
          else -> {
            if (!isHeadsetPlugged) {
              forceAudioMode()
              audioManager.isSpeakerphoneOn = isSpeakerEnabled
              setAppropriateVolume(isSpeakerEnabled)
            }
          }
        }
      }
    }
  }

  override fun start() {
    logger.d { "[start] Starting audio handler, Android 13+: $isAndroid13Plus" }
    isCallActive = true
    if (audioSwitch == null) {
      handler.removeCallbacksAndMessages(null)
      handler.post {
        initializeAudio()
      }
    }
  }

  private fun initializeAudio() {
    audioManager.apply {
      // Set initial audio mode
      mode = AudioManager.MODE_IN_COMMUNICATION

      // Set initial speaker state based on Android version
      if (isAndroid13Plus) {
        isSpeakerphoneOn = true  // Default to speaker for Android 13+
      } else {
        isSpeakerphoneOn = false // Default to earpiece/headphone for lower versions
      }

      // Set initial volume
      setAppropriateVolume(isSpeakerphoneOn)
    }

    // Register headset receiver and setup audio switch
    registerHeadsetReceiver()
    setupAudioSwitch()
  }

  private fun setupAudioSwitch() {
    val switch = AudioSwitch(
      context = context,
      audioFocusChangeListener = createAudioFocusChangeListener(),
      preferredDeviceList = getPreferredDeviceList()
    )
    audioSwitch = switch

    // Start audio switch with device change listener
    switch.start(audioDeviceChangeListener)
    switch.activate()
  }

//  override fun enablePhoneSpeaker(enable: Boolean) {
//    logger.d { "[enablePhoneSpeaker] enable: $enable" }
//    if (!isCallActive) return
//
//    isSpeakerEnabled = enable
//    handler.post {
//      try {
//        if (isHeadsetPlugged) {
//          // If headset is plugged, force audio to headset regardless of Android version
//          forceAudioMode()
//          audioManager.isSpeakerphoneOn = false
//          setAppropriateVolume(false)
//          if (isAndroid13Plus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            val devices = audioManager.availableCommunicationDevices
//            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let { device ->
//              audioManager.setCommunicationDevice(device)
//            }
//          }
//        } else {
//          if (isAndroid13Plus) {
//            // Android 13+ speaker handling (only if no headset)
//            forceAudioMode()
//            audioManager.isSpeakerphoneOn = enable
//            setAppropriateVolume(enable)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//              val devices = audioManager.availableCommunicationDevices
//              val deviceType = if (enable) {
//                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
//              } else {
//                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
//              }
//              devices.firstOrNull { it.type == deviceType }?.let { device ->
//                audioManager.setCommunicationDevice(device)
//              }
//            }
//          } else {
//            // Android 12 and below speaker handling
//            forceAudioMode()
//            audioManager.isSpeakerphoneOn = enable
//            if (enable) {
//              audioManager.setStreamVolume(
//                AudioManager.STREAM_VOICE_CALL,
//                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
//                0
//              )
//            } else {
//              setAppropriateVolume(false)
//            }
//          }
//        }
//
//        // Update AudioSwitch after changing audio routing
//        audioSwitch?.apply {
//          if (isHeadsetPlugged) {
//            selectDevice(AudioDevice.WiredHeadset())
//          } else {
//            selectDevice(if (enable) AudioDevice.Speakerphone() else AudioDevice.Earpiece())
//          }
//          activate()
//        }
//      } catch (e: Exception) {
//        logger.e { "[enablePhoneSpeaker] Error: ${e.message}" }
//      }
//    }
//  }

  override fun enablePhoneSpeaker(enable: Boolean) {
    logger.d { "[enablePhoneSpeaker] enable: $enable, isHeadsetPlugged: $isHeadsetPlugged" }
    if (!isCallActive) return

    isSpeakerEnabled = enable
    handler.post {
      try {
        if (isHeadsetPlugged) {
          // If headset is plugged, always use headset
          forceAudioMode()
          audioManager.isSpeakerphoneOn = false
          setAppropriateVolume(false)
          audioSwitch?.apply {
            selectDevice(AudioDevice.WiredHeadset())
            activate()
          }
          return@post
        }

        // Set audio mode first
        forceAudioMode()

        // Force speaker settings multiple times to ensure it takes effect
        repeat(2) { attempt ->
          handler.postDelayed({
            try {
              if (enable) {
                // Enable speaker
                audioManager.isSpeakerphoneOn = true

                // Set maximum volume for speaker
                audioManager.setStreamVolume(
                  AudioManager.STREAM_VOICE_CALL,
                  audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                  0
                )

                // Additional speaker enforcement for older versions
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                  audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                  audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    0
                  )
                }
              } else {
                // Disable speaker
                audioManager.isSpeakerphoneOn = false
                setAppropriateVolume(false)
              }
            } catch (e: Exception) {
              logger.e { "[enablePhoneSpeaker] Error on attempt $attempt: ${e.message}" }
            }
          }, attempt * 100L) // Delays: 0ms, 100ms
        }

        // Additional routing for Android 12 and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val devices = audioManager.availableCommunicationDevices
          val deviceType = if (enable) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
          } else {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
          }
          devices.firstOrNull { it.type == deviceType }?.let { device ->
            handler.postDelayed({
              try {
                audioManager.setCommunicationDevice(device)
              } catch (e: Exception) {
                logger.e { "[enablePhoneSpeaker] Error setting communication device: ${e.message}" }
              }
            }, 200) // Additional delay for device setting
          }
        }

        // Update AudioSwitch
        audioSwitch?.apply {
          if (enable) {
            handler.postDelayed({
              selectDevice(AudioDevice.Speakerphone())
              activate()
            }, 300)
          } else {
            selectDevice(AudioDevice.Earpiece())
            activate()
          }
        }

        // Final check and enforcement
        handler.postDelayed({
          if (enable && !isHeadsetPlugged) {
            audioManager.apply {
              mode = AudioManager.MODE_IN_COMMUNICATION
              isSpeakerphoneOn = true
              setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
              )
            }
          }
        }, 500)

      } catch (e: Exception) {
        logger.e { "[enablePhoneSpeaker] Error: ${e.message}" }
      }
    }
  }

  // Add this helper method
  private fun enforceAudioRouting(enable: Boolean) {
    audioManager.apply {
      mode = AudioManager.MODE_IN_COMMUNICATION
      isSpeakerphoneOn = enable
      if (enable) {
        setStreamVolume(
          AudioManager.STREAM_VOICE_CALL,
          getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
          0
        )
      }
    }
  }




  private fun forceAudioMode() {
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    handler.postDelayed({
      audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }, 100)
  }

  private fun setAppropriateVolume(isSpeaker: Boolean) {
    val stream = AudioManager.STREAM_VOICE_CALL
    val maxVolume = audioManager.getStreamMaxVolume(stream)

    val targetVolume = when {
      isAndroid13Plus && isSpeaker -> (maxVolume * 0.9).toInt()  // 90% volume for speaker on Android 13+
      isAndroid13Plus && !isSpeaker -> (maxVolume * 0.7).toInt() // 70% volume for earpiece/headset on Android 13+
      !isAndroid13Plus && isSpeaker -> maxVolume                  // Full volume for speaker on older versions
      else -> (maxVolume * 0.6).toInt()                          // 60% volume for earpiece/headset on older versions
    }

    audioManager.setStreamVolume(stream, targetVolume, 0)
  }

  private val headsetReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == Intent.ACTION_HEADSET_PLUG) {
        val state = intent.getIntExtra("state", -1)
        isHeadsetPlugged = state == 1
        logger.d { "[headsetReceiver] Headset plugged: $isHeadsetPlugged" }

        if (isCallActive) {
          handler.post {
            handleHeadsetChange(isHeadsetPlugged)
          }
        }
      }
    }
  }

  private fun handleHeadsetChange(headsetPlugged: Boolean) {
    if (headsetPlugged) {
      // Always handle headset connection the same way regardless of Android version
      forceAudioMode()
      audioManager.isSpeakerphoneOn = false
      setAppropriateVolume(false)

      if (isAndroid13Plus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Force routing to headset on Android 13+
        val devices = audioManager.availableCommunicationDevices
        devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let { device ->
          audioManager.setCommunicationDevice(device)
        }
      }

      audioSwitch?.apply {
        selectDevice(AudioDevice.WiredHeadset())
        activate()
      }
    } else {
      // Headset unplugged - restore previous speaker state
      forceAudioMode()
      audioManager.isSpeakerphoneOn = isSpeakerEnabled
      setAppropriateVolume(isSpeakerEnabled)

      if (isAndroid13Plus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val devices = audioManager.availableCommunicationDevices
        val deviceType = if (isSpeakerEnabled) {
          AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
          AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }
        devices.firstOrNull { it.type == deviceType }?.let { device ->
          audioManager.setCommunicationDevice(device)
        }
      }

      audioSwitch?.apply {
        selectDevice(if (isSpeakerEnabled) AudioDevice.Speakerphone() else AudioDevice.Earpiece())
        activate()
      }
    }
  }

  private fun registerHeadsetReceiver() {
    try {
      context.registerReceiver(
        headsetReceiver,
        IntentFilter(Intent.ACTION_HEADSET_PLUG)
      )
    } catch (e: Exception) {
      logger.e { "[registerHeadsetReceiver] Error: ${e.message}" }
    }
  }

  override fun stop() {
    logger.d { "[stop] Stopping audio handler" }
    isCallActive = false
    isSpeakerEnabled = false

    handler.removeCallbacksAndMessages(null)
    handler.post {
      try {
        context.unregisterReceiver(headsetReceiver)
      } catch (e: Exception) {
        logger.e { "[stop] Error unregistering receiver: ${e.message}" }
      }

      audioSwitch?.stop()
      audioSwitch = null

      // Reset audio state
      audioManager.apply {
        mode = AudioManager.MODE_NORMAL
        isSpeakerphoneOn = false
      }
    }
  }

  private fun getPreferredDeviceList() = if (isAndroid13Plus) {
    listOf(
      AudioDevice.Speakerphone::class.java,  // Prioritize speaker for Android 13+
      AudioDevice.WiredHeadset::class.java,
      AudioDevice.BluetoothHeadset::class.java,
      AudioDevice.Earpiece::class.java
    )
  } else {
    listOf(
      AudioDevice.WiredHeadset::class.java,  // Prioritize headset for older versions
      AudioDevice.BluetoothHeadset::class.java,
      AudioDevice.Earpiece::class.java,
      AudioDevice.Speakerphone::class.java
    )
  }

  private fun createAudioFocusChangeListener() = AudioManager.OnAudioFocusChangeListener { focusChange ->
    when (focusChange) {
      AudioManager.AUDIOFOCUS_GAIN -> {
        logger.d { "[onAudioFocusChange] Gained focus" }
        handler.post {
          if (isCallActive) {
            forceAudioMode()
            if (isAndroid13Plus) {
              audioManager.isSpeakerphoneOn = isSpeakerEnabled
              setAppropriateVolume(isSpeakerEnabled)
            } else {
              if (isHeadsetPlugged) {
                audioManager.isSpeakerphoneOn = false
                setAppropriateVolume(false)
              } else {
                audioManager.isSpeakerphoneOn = isSpeakerEnabled
                setAppropriateVolume(isSpeakerEnabled)
              }
            }
            audioSwitch?.activate()
          }
        }
      }
      AudioManager.AUDIOFOCUS_LOSS -> {
        logger.d { "[onAudioFocusChange] Lost focus" }
      }
    }
  }
}

