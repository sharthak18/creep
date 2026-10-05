package com.screenkit.screenrecorder

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
    const val EXTRA_AUDIO_MODE = "screenkit.audio_mode"
    const val EXTRA_AUDIO_MUTED = "screenkit.audio_muted"
    const val EXTRA_AUDIO_CAPABLE = "screenkit.audio_capable"
    const val EXTRA_CAMERA_FRONT = "screenkit.camera_front"
    const val EXTRA_SHOW_CAMERA_OVERLAY = "screenkit.show_camera_overlay"
    const val EXTRA_OVERLAY_COMMAND = "screenkit.overlay_command"

    const val ACTION_SCREEN_STATE = "com.screenkit.screenrecorder.SCREEN_STATE"
    const val ACTION_SCREEN_FINISHED = "com.screenkit.screenrecorder.SCREEN_FINISHED"
    const val ACTION_CAMERA_STATE = "com.screenkit.screenrecorder.CAMERA_STATE"
    const val ACTION_CAMERA_FINISHED = "com.screenkit.screenrecorder.CAMERA_FINISHED"

    /** Overlay bubble actions. Each one runs without bringing the main app to the front. */
    const val COMMAND_SCREENSHOT = "screenshot"
    const val COMMAND_TOGGLE_SCREEN_RECORDING = "screen_recording"
    const val COMMAND_TOGGLE_CAMERA_RECORDING = "camera_recording"
    const val COMMAND_TOGGLE_CAMERA_OVERLAY = "camera_overlay"
    const val COMMAND_FLIP_CAMERA = "flip_camera"
    const val COMMAND_CYCLE_AUDIO = "cycle_audio"
    const val COMMAND_TOGGLE_AUDIO = "toggle_audio"
    const val COMMAND_OPEN_APP = "open_app"
    const val COMMAND_HIDE_OVERLAY = "hide_overlay"
    const val COMMAND_PARTIAL_SCREENSHOT = "partial_screenshot"
    const val COMMAND_PARTIAL_SCREEN_RECORDING = "partial_screen_recording"
    const val COMMAND_OPEN_BRUSH = "open_brush"

    /** Broadcast actions to coordinate overlay visibility during screenshot capture */
    const val ACTION_TEMPORARY_HIDE_OVERLAY = "com.screenkit.screenrecorder.overlay.TEMP_HIDE"
    const val ACTION_RESTORE_OVERLAY = "com.screenkit.screenrecorder.overlay.RESTORE"

    /** Capture modes handled by [CaptureRequestActivity]. */
    const val MODE_SCREENSHOT = "screenshot"
    const val MODE_SCREEN_RECORDING = "screen_recording"
    const val MODE_CAMERA_RECORDING = "camera_recording"
    const val MODE_CAMERA_PREVIEW = "camera_preview"
    const val MODE_MICROPHONE = "microphone_on"
    const val MODE_PARTIAL_SCREENSHOT = "partial_screenshot"
    const val MODE_PARTIAL_SCREEN_RECORDING = "partial_screen_recording"
    const val MODE_BRUSH = "brush_drawing"

    const val EXTRA_CROP_LEFT = "screenkit.crop_left"
    const val EXTRA_CROP_TOP = "screenkit.crop_top"
    const val EXTRA_CROP_RIGHT = "screenkit.crop_right"
    const val EXTRA_CROP_BOTTOM = "screenkit.crop_bottom"

    const val PREF_SHOW_TOUCHES = "show_touches"
    const val PREFS_NAME = "screenkit_settings"
    const val PREF_MICROPHONE_ENABLED = "microphone_enabled"
    const val PREF_AUDIO_MODE = "audio_mode"
    const val PREF_CAMERA_FRONT = "camera_front"
    const val PREF_OVERLAY_ENABLED = "overlay_enabled"
    const val PREF_OVERLAY_INTRO_SEEN = "overlay_intro_seen"
    const val PREF_CAMERA_OVERLAY_ENABLED = "camera_overlay_enabled"
    const val PREF_PROJECTION_SCOPE = "projection_scope"
    const val PREF_SAVE_MODE = "save_mode"
    const val PREF_SAVE_VOLUME = "save_volume"
    const val PREF_SAVE_TREE_URI = "save_tree_uri"
    const val PREF_SAVE_FOLDER_LABEL = "save_folder_label"
}
