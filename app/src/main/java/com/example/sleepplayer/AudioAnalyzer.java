package com.example.sleepplayer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Analysiert die RMS-Lautstärke (Effektivwert) einer Audiodatei.
 *
 * Verwendet MediaExtractor + MediaCodec zum Dekodieren in PCM-Samples.
 * Analysiert nur die ersten {@link #MAX_ANALYSIS_US} Mikrosekunden (Performance).
 * Gibt den RMS-Wert in dBFS zurück (0 dBFS = maximaler Pegel).
 *
 * Typische Werte:
 *   - Laut aufgenommene Musik: -10 bis -6 dBFS
 *   - Ruhige Einschlaf-Musik:  -25 bis -18 dBFS
 */
public class AudioAnalyzer {

    private static final String TAG = "AudioAnalyzer";

    /** Nur die ersten 60 Sekunden analysieren (reicht für repräsentativen Lautstärke-Wert). */
    private static final long MAX_ANALYSIS_US = 60_000_000L;

    /** Timeout für MediaCodec-Puffer in Mikrosekunden. */
    private static final long CODEC_TIMEOUT_US = 5_000L;

    /**
     * Callback für Fortschritts-Updates (läuft auf dem Analyse-Thread).
     */
    public interface ProgressCallback {
        /** Wird mit Fortschritt 0–100 aufgerufen. */
        void onProgress(int percent);
    }

    /**
     * Analysiert die RMS-Lautstärke einer Audiodatei.
     *
     * @param context Android-Kontext
     * @param uri     URI der Audiodatei (z.B. MediaStore-URI)
     * @return RMS-Pegel in dBFS, oder {@code Float.NaN} bei Fehler
     */
    public static float analyzeRmsDb(Context context, Uri uri) {
        return analyzeRmsDb(context, uri, null);
    }

    /**
     * Analysiert die RMS-Lautstärke einer Audiodatei mit Fortschritts-Callback.
     *
     * @param context  Android-Kontext
     * @param uri      URI der Audiodatei
     * @param progress Optionaler Fortschritts-Callback (darf null sein)
     * @return RMS-Pegel in dBFS, oder {@code Float.NaN} bei Fehler
     */
    public static float analyzeRmsDb(Context context, Uri uri, ProgressCallback progress) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;

        try {
            extractor.setDataSource(context, uri, null);

            // Audio-Track finden
            int audioTrackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    format = f;
                    break;
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                Log.w(TAG, "Kein Audio-Track gefunden: " + uri);
                return Float.NaN;
            }

            extractor.selectTrack(audioTrackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            double sumSquares = 0.0;
            long sampleCount = 0;
            boolean inputDone = false;
            boolean outputDone = false;
            int noOutputRetries = 0;
            // PCM-Encoding – nach INFO_OUTPUT_FORMAT_CHANGED aktualisiert
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (!outputDone) {
                // --- Input-Seite: Audio-Frames in den Codec einspeisen ---
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuf = codec.getInputBuffer(inputIndex);
                        if (inputBuf == null) continue;

                        int sampleSize = extractor.readSampleData(inputBuf, 0);
                        long pts = extractor.getSampleTime();

                        if (sampleSize < 0 || (pts >= 0 && pts > MAX_ANALYSIS_US)) {
                            // Ende der Datei oder Zeitlimit erreicht
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts < 0 ? 0 : pts, 0);
                            extractor.advance();

                            // Fortschritt melden (0–99 %)
                            if (progress != null && pts > 0) {
                                int pct = (int) (pts * 99L / MAX_ANALYSIS_US);
                                progress.onProgress(Math.min(99, pct));
                            }
                        }
                    }
                }

                // --- Output-Seite: dekodierte PCM-Samples auslesen ---
                int outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // PCM-Encoding des Outputs ermitteln
                    MediaFormat outFormat = codec.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    noOutputRetries = 0;

                } else if (outputIndex >= 0) {
                    noOutputRetries = 0;
                    ByteBuffer outputBuf = codec.getOutputBuffer(outputIndex);
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset);
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size);
                        outputBuf.order(ByteOrder.LITTLE_ENDIAN);

                        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer fb = outputBuf.asFloatBuffer();
                            while (fb.hasRemaining()) {
                                double s = fb.get();
                                sumSquares += s * s;
                                sampleCount++;
                            }
                        } else {
                            // Standard: 16-Bit PCM
                            ShortBuffer sb = outputBuf.asShortBuffer();
                            while (sb.hasRemaining()) {
                                double s = sb.get() / 32768.0;
                                sumSquares += s * s;
                                sampleCount++;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }

                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone) {
                        noOutputRetries++;
                        if (noOutputRetries > 50) {
                            Log.w(TAG, "Codec antwortet nicht mehr – Analyse abbrechen");
                            outputDone = true;
                        }
                    }
                }
            }

            if (sampleCount == 0) {
                Log.w(TAG, "Keine Samples dekodiert für: " + uri);
                return Float.NaN;
            }

            double rms = Math.sqrt(sumSquares / sampleCount);
            if (rms < 1e-10) return -100f; // Quasi-Stille

            float dbFs = (float) (20.0 * Math.log10(rms));
            Log.d(TAG, String.format("RMS: %.1f dBFS  (%d samples)  %s", dbFs, sampleCount, uri));
            return dbFs;

        } catch (Exception e) {
            Log.e(TAG, "Analyse fehlgeschlagen für: " + uri, e);
            return Float.NaN;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
        }
    }
}

