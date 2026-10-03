package com.creep.screenrecorder

/** Explicit app-private intents shared by the activity, capture services and overlay. */
object CaptureContract {
    const val EXTRA_ACTIVE = "screenkit.active"
    const val EXTRA_RECORDING = "screenkit.recording"
    const val EXTRA_PREVIEW = "screenkit.preview"
    const val EXTRA_MODE = "screenkit.mode"
    const val EXTRA_STARTED_AT = "screenkit.started_at"
    const val EXTRA_SUCCESS = "screenkit.success"
    const val EXTRA_MESSAGE = "screenkit.message"
    const val EXTRA_URI = "screenkit.uri"
    const val EXTRA_RESULT_CODE = "screenkit.result_code"
    const val EXTRA_RESULT_DATA = "screenkit.result_data"
    const val EXTRA_AUDIO_ENABLED = "screenkit.audio_enabled"
    const val EXTRA_AUDIO_MUTED = "screenkit.audio_muted"
    const val EXTRA_AUDIO_CAPABLE = "screenkit.audio_capable"
    const val EXTRA_CAMERA_FRONT = "screenkit.camera_front"
    const val EXTRA_SHOW_CAMERA_OVERLAY = "screenkit.show_camera_overlay"
    const val EXTRA_OVERLAY_COMMAND = "screenkit.overlay_command"

    const val ACTION_SCREEN_STATE = "com.creep.screenrecorder.SCREEN_STATE"
    const val ACTION_SCREEN_FINISHED = "com.creep.screenrecorder.SCREEN_FINISHED"
    const val ACTION_CAMERA_STATE = "com.creep.screenrecorder.CAMERA_STATE"
    const val ACTION_CAMERA_FINISHED = "com.creep.screenrecorder.CAMERA_FINISHED"

    const val COMMAND_SCREENSHOT = "screenshot"
    const val COMMAND_TOGGLE_SCREEN_RECORDING = "screen_recording"
    const val COMMAND_TOGGLE_CAMERA_RECORDING = "camera_recording"
    const val COMMAND_TOGGLE_CAMERA_OVERLAY = "camera_overlay"
    const val COMMAND_TOGGLE_AUDIO = "toggle_audio"
    const val COMMAND_OPEN_APP = "open_app"

    const val PREFS_NAME = "screenkit_settings"
    const val PREF_MICROPHONE_ENABLED = "microphone_enabled"
    const val PREF_CAMERA_FRONT = "camera_front"
    const val PREF_OVERLAY_ENABLED = "overlay_enabled"
}
