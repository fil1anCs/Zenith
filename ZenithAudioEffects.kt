package com.zenith.player.playback

import android.media.audiofx.Equalizer

/** Device-side equalizer attached to the active Media3 audio session. */
object ZenithAudioEffects {
    @Volatile private var equalizer: Equalizer? = null

    @Synchronized
    fun attach(audioSessionId: Int): Int {
        if (audioSessionId <= 0) return 0
        return try {
            equalizer?.release()
            Equalizer(0, audioSessionId).also { eq ->
                eq.enabled = true
                equalizer = eq
            }
            equalizer?.numberOfBands?.toInt() ?: 0
        } catch (_: Exception) { 0 }
    }

    @Synchronized fun bandCount(): Int = try { equalizer?.numberOfBands?.toInt() ?: 0 } catch (_: Exception) { 0 }
    @Synchronized fun bandLevel(index: Int): Int = try { equalizer?.getBandLevel(index.toShort())?.toInt() ?: 0 } catch (_: Exception) { 0 }
    @Synchronized fun bandRange(): IntArray = try { equalizer?.bandLevelRange?.let { intArrayOf(it[0].toInt(), it[1].toInt()) } ?: intArrayOf(-1500, 1500) } catch (_: Exception) { intArrayOf(-1500, 1500) }
    @Synchronized fun bandFrequency(index: Int): Int = try { equalizer?.getCenterFreq(index.toShort())?.div(1000) ?: 0 } catch (_: Exception) { 0 }
    @Synchronized fun setBand(index: Int, level: Int) { try { equalizer?.setBandLevel(index.toShort(), level.toShort()) } catch (_: Exception) { } }
    @Synchronized fun setEnabled(enabled: Boolean) { try { equalizer?.enabled = enabled } catch (_: Exception) { } }
    @Synchronized fun isEnabled(): Boolean = try { equalizer?.enabled == true } catch (_: Exception) { false }
    @Synchronized fun release() { try { equalizer?.release() } catch (_: Exception) { }; equalizer = null }
}
