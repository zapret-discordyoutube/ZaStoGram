package org.telegram.messenger.video;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Усиление громкости выше 100% для просмотрщика видео. Пики выше порога мягко
// поджимаются, чтобы при усилении звук не хрипел от жёсткого клиппинга.
public class ZaStoAudioGainProcessor extends BaseAudioProcessor {

    private static final float KNEE = 0.85f;

    private volatile float gain = 1f;

    public void setGain(float gain) {
        this.gain = gain;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            return AudioFormat.NOT_SET;
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        final int remaining = inputBuffer.remaining();
        if (remaining == 0) {
            return;
        }
        final ByteBuffer out = replaceOutputBuffer(remaining);
        final float g = gain;
        if (Math.abs(g - 1f) < 0.001f) {
            out.put(inputBuffer);
        } else if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputBuffer.order(ByteOrder.nativeOrder());
            while (inputBuffer.remaining() >= 2) {
                final float s = limit(inputBuffer.getShort() / 32768f * g);
                out.putShort((short) Math.round(s * 32767f));
            }
            out.put(inputBuffer);
        } else {
            inputBuffer.order(ByteOrder.nativeOrder());
            while (inputBuffer.remaining() >= 4) {
                out.putFloat(limit(inputBuffer.getFloat() * g));
            }
            out.put(inputBuffer);
        }
        out.flip();
    }

    private static float limit(float s) {
        final float a = Math.abs(s);
        if (a <= KNEE) {
            return s;
        }
        final float over = (float) Math.tanh((a - KNEE) / (1f - KNEE)) * (1f - KNEE);
        return Math.copySign(KNEE + over, s);
    }
}
