package com.llfbandit.record.record.recorder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.llfbandit.record.record.RecordConfig
import com.llfbandit.record.record.RecordState
import com.llfbandit.record.record.stream.RecorderRecordStreamHandler
import com.llfbandit.record.record.stream.RecorderStateStreamHandler

interface OnAudioRecordListener {
    fun onRecord()
    fun onPause()
    fun onStop()
    fun onFailure(ex: Exception)
    fun onAudioChunk(chunk: ByteArray)
}

class AudioRecorder(
    // Recorder streams
    private val recorderStateStreamHandler: RecorderStateStreamHandler,
    private val recorderRecordStreamHandler: RecorderRecordStreamHandler,
    private val appContext: Context
) : IRecorder, OnAudioRecordListener {
    companion object {
        private val TAG = AudioRecorder::class.java.simpleName
    }

    // Recorder thread with which we will interact
    private var recorderThread: RecordThread? = null

    // Amplitude
    private var maxAmplitude = -160.0

    // Recording config
    private var config: RecordConfig? = null

    // Stop callback to be synchronized between stop method return & record stop
    private var stopCb: ((path: String?) -> Unit)? = null

    private var muteSettings = HashMap<Int, Int>()
    private val muteStreams = arrayOf(
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_DTMF,
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_VOICE_CALL,
    )

    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        initMuteSettings()
    }

    /**
     * Starts the recording with the given config.
     */
    @Throws(Exception::class)
    override fun start(config: RecordConfig) {
        this.config = config

        recorderThread = RecordThread(config, this)
        recorderThread!!.startRecording()

        if (config.muteAudio) {
            muteAudio(true)
        }
        if (config.audioManagerMode != AudioManager.MODE_NORMAL) {
            setupAudioManagerMode()
        }
        if (config.setSpeakerphoneOn) {
            setSpeakerphoneOn()
        }
    }

    override fun stop(stopCb: ((path: String?) -> Unit)?) {
        this.stopCb = stopCb

        recorderThread?.stopRecording()
    }

    override fun cancel() {
        recorderThread?.cancelRecording()
    }

    override fun pause() {
        recorderThread?.pauseRecording()
    }

    override fun resume() {
        recorderThread?.resumeRecording()
    }

    override val isRecording: Boolean
        get() = recorderThread?.isRecording() == true

    override val isPaused: Boolean
        get() = recorderThread?.isPaused() == true

    override fun getAmplitude(): List<Double> {
        val amplitude = recorderThread?.getAmplitude() ?: -160.0
        val amps: MutableList<Double> = ArrayList()
        amps.add(amplitude)
        amps.add(maxAmplitude)
        return amps
    }

    override fun dispose() {
        setSpeakerphoneOff()
        releaseAudioFocus()
        stop(null)
    }

    // OnAudioRecordListener
    override fun onRecord() {
        recorderStateStreamHandler.sendStateEvent(RecordState.RECORD.id)
    }

    override fun onPause() {
        recorderStateStreamHandler.sendStateEvent(RecordState.PAUSE.id)
    }

    override fun onStop() {
        if (config?.muteAudio == true) {
            muteAudio(false)
        }

        stopCb?.invoke(config?.path)
        stopCb = null

        recorderStateStreamHandler.sendStateEvent(RecordState.STOP.id)
    }

    override fun onFailure(ex: Exception) {
        Log.e(TAG, ex.message, ex)
        recorderStateStreamHandler.sendStateErrorEvent(ex)
    }

    override fun onAudioChunk(chunk: ByteArray) {
        recorderRecordStreamHandler.sendRecordChunkEvent(chunk)
    }

    private fun muteAudio(mute: Boolean) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val muteValue = AudioManager.ADJUST_MUTE
        val unmuteValue = AudioManager.ADJUST_UNMUTE

        muteStreams.forEach { stream ->
            val volumeLevel = if (mute) muteValue else (muteSettings[stream] ?: unmuteValue)
            audioManager.setStreamVolume(stream, volumeLevel, 0)
        }
    }

    private fun initMuteSettings() {
        muteSettings.clear()

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        muteStreams.forEach { stream ->
            muteSettings[stream] = audioManager.getStreamVolume(stream)
        }
    }

    private fun setupAudioManagerMode() {
        val config = this.config ?: return
        if (config.audioManagerMode == null) {
            return
        }
        if (config.audioManagerMode == AudioManager.MODE_NORMAL) {
            return
        }
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setMode(config.audioManagerMode ?: AudioManager.MODE_NORMAL)
    }

    private fun requestAudioFocus() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26+
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun releaseAudioFocus() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun setSpeakerphoneOn() {
        val config = this.config
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (config == null) {
            return
        }
        if (config.setSpeakerphoneOn == false) {
            return
        }
        // Request audio focus
        requestAudioFocus()

        // Set audio mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audioManager.setCommunicationDevice(device)
                    break
                }
            }
            audioManager.clearCommunicationDevice()

        } else {
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun setSpeakerphoneOff() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isSpeakerphoneOn = true
        }
    }
}
