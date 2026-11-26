package com.snaix.pikpak

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.appcompat.widget.SeslSeekBar
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal class PlaybackSeekBar(context: Context, attrs: AttributeSet?) : SeslSeekBar(context, attrs), TimeBar {
    private val scrubListeners = linkedSetOf<TimeBar.OnScrubListener>()
    private var mediaDuration = C.TIME_UNSET
    private var playhead = 0L
    private var buffered = 0L
    private var dragging = false
    private var cancelled = false
    private var keyTimeMs = C.TIME_UNSET
    private var keySteps = 20

    init {
        max = 10_000
        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeslSeekBar) {
                if (mediaDuration <= 0) return
                dragging = true
                scrubListeners.forEach { it.onScrubStart(this@PlaybackSeekBar, positionAt(progress)) }
            }

            override fun onProgressChanged(seekBar: SeslSeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || mediaDuration <= 0) return
                val position = positionAt(progress)
                if (dragging) {
                    scrubListeners.forEach { it.onScrubMove(this@PlaybackSeekBar, position) }
                } else {
                    // Keyboard and accessibility changes have no touch start/stop callbacks.
                    scrubListeners.forEach {
                        it.onScrubStart(this@PlaybackSeekBar, position)
                        it.onScrubStop(this@PlaybackSeekBar, position, false)
                    }
                }
            }

            override fun onStopTrackingTouch(seekBar: SeslSeekBar) {
                if (!dragging) return
                val position = positionAt(progress)
                dragging = false
                scrubListeners.forEach { it.onScrubStop(this@PlaybackSeekBar, position, cancelled) }
                if (cancelled) progress = levelAt(playhead)
            }
        })
        updateKeyStep()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
        return super.onTouchEvent(event)
    }

    override fun addListener(listener: TimeBar.OnScrubListener) { scrubListeners.add(listener) }
    override fun removeListener(listener: TimeBar.OnScrubListener) { scrubListeners.remove(listener) }

    override fun setPosition(position: Long) {
        playhead = position
        if (!dragging) progress = levelAt(position)
    }

    override fun setBufferedPosition(bufferedPosition: Long) {
        buffered = bufferedPosition
        secondaryProgress = levelAt(bufferedPosition)
    }

    override fun setDuration(duration: Long) {
        if (mediaDuration == duration) return
        mediaDuration = duration
        setPosition(playhead)
        setBufferedPosition(buffered)
        updateKeyStep()
    }

    override fun setKeyTimeIncrement(time: Long) {
        require(time > 0L)
        keyTimeMs = time
        updateKeyStep()
    }

    override fun setKeyCountIncrement(count: Int) {
        require(count > 0)
        keyTimeMs = C.TIME_UNSET
        keySteps = count
        updateKeyStep()
    }

    override fun getPreferredUpdateDelay(): Long {
        val trackWidth = width - paddingLeft - paddingRight
        return if (trackWidth > 0 && mediaDuration > 0) (mediaDuration / trackWidth).coerceAtLeast(1)
        else Long.MAX_VALUE
    }

    // Cloud files do not supply ad schedules.
    override fun setAdGroupTimesMs(timesMs: LongArray?, played: BooleanArray?, count: Int) = Unit

    private fun levelAt(position: Long): Int =
        if (mediaDuration > 0) ((position.coerceIn(0, mediaDuration).toDouble() / mediaDuration) * max).roundToInt()
        else 0

    private fun positionAt(level: Int): Long =
        if (mediaDuration > 0) (level.toDouble() / max * mediaDuration).toLong() else 0L

    private fun updateKeyStep() {
        keyProgressIncrement = (if (keyTimeMs == C.TIME_UNSET) max / keySteps else levelAt(keyTimeMs)).coerceAtLeast(1)
    }
}
