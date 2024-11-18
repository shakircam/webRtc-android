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
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat.getSystemService
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
            if (!isAndroid13Plus && !isHeadsetPlugged) {
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

      // initially earpiece mode
      isSpeakerphoneOn = false

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


  // working on android 12+ version (speaker,earpiece,headphone)
  // working on android 8 version (speaker,earpiece,headphone)
  // not working on android 9,11 version (speaker)

  override fun enablePhoneSpeaker(enable: Boolean) {
    logger.d { "[enablePhoneSpeaker] enable: $enable, isHeadsetPlugged: $isHeadsetPlugged" }
    if (!isCallActive) return

    isSpeakerEnabled = enable
    handler.post {
      try {
        // Set audio mode first
        forceAudioMode()

        // Set appropriate volume
        setAppropriateVolume(enable)

        // Update AudioSwitch (if used)
        audioSwitch?.apply {
          selectDevice(
            if (enable) AudioDevice.Speakerphone()
            else AudioDevice.Earpiece())
          activate()
        }

        // Additional check for Android 12+ (API level 31)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val devices = audioManager.availableCommunicationDevices
          val deviceType = if (enable) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
          devices.firstOrNull { it.type == deviceType
          }?.let { device ->
            audioManager.setCommunicationDevice(device)
          }
          logger.d { "[enablePhoneSpeaker] for upper versions" }
        } else {
          // Fallback for older Android versions
          audioManager.mode = AudioManager.MODE_IN_COMMUNICATION // Ensure in-call mode
          audioManager.isSpeakerphoneOn = enable

          logger.d { "[enablePhoneSpeaker] for older versions" }
        }
      } catch (e: Exception) {
        logger.e { "[enablePhoneSpeaker] Error: ${e.message}" }
      }
    }
  }


// Last working method
//  override fun enablePhoneSpeaker(enable: Boolean) {
//    logger.d { "[enablePhoneSpeaker] enable: $enable, isHeadsetPlugged: $isHeadsetPlugged" }
//    if (!isCallActive) return
//
//    isSpeakerEnabled = enable
//    handler.post {
//      try {
//        if (isHeadsetPlugged) {
//          // Handle headset routing
//          forceAudioMode()
//          audioManager.isSpeakerphoneOn = false
//          setAppropriateVolume(false)
//          audioSwitch?.apply {
//            selectDevice(AudioDevice.WiredHeadset())
//            activate()
//          }
//          return@post
//        }
//
//        // Set audio mode first
//        forceAudioMode()
//
//        when {
//          // For Android 12+ (API 31+)
//          Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
//            val devices = audioManager.availableCommunicationDevices
//            val deviceType = if (enable) {
//              AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
//            } else {
//              AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
//            }
//            devices.firstOrNull { it.type == deviceType }?.let { device ->
//              audioManager.setCommunicationDevice(device)
//            }
//            audioManager.isSpeakerphoneOn = enable
//          }
//
//          // For Android 9 and 11
//          Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.R -> {
//            // Special handling for Android 9-11
//            if (enable) {
//
//                handler.postDelayed({
//                  try {
//                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//                    audioManager.isSpeakerphoneOn = true
//
//                    // Force maximum volume for speaker
//                    audioManager.setStreamVolume(
//                      AudioManager.STREAM_VOICE_CALL,
//                      audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
//                      0
//                    )
//
//                    // Additional settings for these versions
//                    audioManager.setParameters("speaker_on=1")
//                  } catch (e: Exception) {
//                    logger.e { "[enablePhoneSpeaker] Error on attempt  ${e.message}" }
//                  }
//                },  500)
//
//            } else {
//              audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//              audioManager.isSpeakerphoneOn = false
//              audioManager.setParameters("speaker_on=0")
//              setAppropriateVolume(false)
//            }
//          }
//
//          // For Android 8
//          else -> {
//            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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
//        // Set appropriate volume
//        setAppropriateVolume(enable)
//
//        // Update AudioSwitch
//        handler.postDelayed({
//          audioSwitch?.apply {
//            selectDevice(if (enable) AudioDevice.Speakerphone() else AudioDevice.Earpiece())
//            activate()
//          }
//        }, 200)
//
//        // Final check for Android 9-11
//        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.R && enable) {
//          handler.postDelayed({
//            if (!audioManager.isSpeakerphoneOn) {
//              audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//              audioManager.isSpeakerphoneOn = true
//              audioManager.setStreamVolume(
//                AudioManager.STREAM_VOICE_CALL,
//                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
//                0
//              )
//            }
//          }, 500)
//        }
//
//      } catch (e: Exception) {
//        logger.e { "[enablePhoneSpeaker] Error: ${e.message}" }
//      }
//    }
//  }


  private fun forceAudioMode() {
    try {
      audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

      // For Android 9-11, additional mode enforcement
      if (Build.VERSION.SDK_INT in Build.VERSION_CODES.P..Build.VERSION_CODES.R) {
        handler.postDelayed({
          audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }, 100)
      }
    } catch (e: Exception) {
      logger.e { "[enforceAudioMode] Error: ${e.message}" }
    }

  }

  private fun setAppropriateVolume(isSpeaker: Boolean) {
    val stream = AudioManager.STREAM_VOICE_CALL
    val maxVolume = audioManager.getStreamMaxVolume(stream)

    val targetVolume = when {
      isAndroid13Plus && isSpeaker -> (maxVolume * 0.9).toInt()  // 90% volume for speaker on Android 13+
      isAndroid13Plus && !isSpeaker -> (maxVolume * 0.7).toInt() // 70% volume for earpiece/headset on Android 13+
      !isAndroid13Plus && isSpeaker -> (maxVolume * 0.9).toInt() // 90% volume for speaker on older versions
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


