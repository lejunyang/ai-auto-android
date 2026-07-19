package dev.aiauto.android.ui.recording

internal object RecordingTestTags {
    const val START = "recording-start"
    const val PAUSE = "recording-pause"
    const val RESUME = "recording-resume"
    const val FINISH = "recording-finish"
    const val REPLAY = "recording-replay"
    const val REPLAY_RESULT = "recording-replay-result"
    const val REPLAY_INTERVENTION = "recording-replay-intervention"

    fun step(number: Int): String = "recording-step-$number"

    fun secretInput(alias: String): String = "recording-secret-$alias"
}
