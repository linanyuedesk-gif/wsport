package com.cl.wordtosport;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

public class ToneGenerator {
    private static final int SAMPLE_RATE = 8000;
    private static final double DEFAULT_FREQ = 800.0; // Hz

    @SuppressWarnings("deprecation")
    public static void playTone(int durationMs) {
        AudioTrack audioTrack = null;
        try {
            if (durationMs <= 0) {
                return;
            }
            
            // Calculate samples required for the duration
            int numSamples = durationMs * SAMPLE_RATE / 1000;
            double[] samples = new double[numSamples];
            byte[] generatedSound = new byte[2 * numSamples];

            // Generate sine wave
            for (int i = 0; i < numSamples; ++i) {
                samples[i] = Math.sin(2 * Math.PI * DEFAULT_FREQ * i / SAMPLE_RATE);
            }

            // Convert to 16 bit PCM sound array
            int idx = 0;
            for (double dVal : samples) {
                short shortVal = (short) (dVal * 32767);
                generatedSound[idx++] = (byte) (shortVal & 0x00ff);
                generatedSound[idx++] = (byte) ((shortVal & 0xff00) >>> 8);
            }

            // Play the sound
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
            AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
            audioTrack = new AudioTrack(audioAttributes, audioFormat, 
                generatedSound.length, AudioTrack.MODE_STATIC, 
                AudioManager.AUDIO_SESSION_ID_GENERATE);
            
            if (audioTrack != null && audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                audioTrack.write(generatedSound, 0, generatedSound.length);
                audioTrack.play();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Note: AudioTrack will be released after playback completes
            // or it can be explicitly released if needed
        }
    }
}