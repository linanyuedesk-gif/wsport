package com.cl.wordtosport;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class ToneGenerator {
    private static final int SAMPLE_RATE = 8000;
    private static final double DEFAULT_FREQ = 800.0; // Hz

    public static void playTone(int durationMs) {
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
        AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, 
            AudioFormat.ENCODING_PCM_16BIT, generatedSound.length, 
            AudioTrack.MODE_STATIC);
        
        audioTrack.write(generatedSound, 0, generatedSound.length);
        audioTrack.play();
    }
}