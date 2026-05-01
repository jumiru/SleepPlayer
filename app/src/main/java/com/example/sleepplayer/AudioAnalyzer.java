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
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Analysiert die RMS-Lautstärke in Abschnitten einer Audiodatei.
     *
     * @param context         Android-Kontext
     * @param uri             URI der Audiodatei
     * @param sectionLengthMs Sektionslänge in Millisekunden (z.B. 30000 für 30 Sek.)
     * @param progress        Optionaler Fortschritts-Callback
     * @return Liste von float[2]: [sectionStartMs, rmsDb]. Leer bei Fehler.
     */
    public static List<float[]> analyzeSectionsDb(Context context, Uri uri,
            int sectionLengthMs, ProgressCallback progress) {
        long sectionLengthUs = (long) sectionLengthMs * 1000L;
        List<float[]> result = new ArrayList<>();
        MediaExtractor ext = new MediaExtractor();
        MediaCodec cod = null;

        try {
            ext.setDataSource(context, uri, null);

            int audioTrackIdx = -1;
            MediaFormat fmt = null;
            long durationUs = 0;
            for (int i = 0; i < ext.getTrackCount(); i++) {
                MediaFormat f = ext.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    audioTrackIdx = i;
                    fmt = f;
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = f.getLong(MediaFormat.KEY_DURATION);
                    }
                    break;
                }
            }
            if (audioTrackIdx < 0 || fmt == null) return result;

            ext.selectTrack(audioTrackIdx);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            cod = MediaCodec.createDecoderByType(mime);
            cod.configure(fmt, null, null, 0);
            cod.start();

            int maxSec = durationUs > 0 ? (int)(durationUs / sectionLengthUs) + 2 : 500;
            double[] sumSq  = new double[maxSec];
            long[]   counts = new long[maxSec];

            int pcmEnc = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            int retries = 0;

            while (!outDone) {
                if (!inDone) {
                    int ii = cod.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (ii >= 0) {
                        ByteBuffer ib = cod.getInputBuffer(ii);
                        if (ib == null) continue;
                        int sz = ext.readSampleData(ib, 0);
                        long pts = ext.getSampleTime();
                        if (sz < 0) {
                            cod.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            cod.queueInputBuffer(ii, 0, sz, pts < 0 ? 0 : pts, 0);
                            ext.advance();
                            if (progress != null && pts > 0 && durationUs > 0) {
                                progress.onProgress(Math.min(99, (int)(pts * 99L / durationUs)));
                            }
                        }
                    }
                }
                int oi = cod.dequeueOutputBuffer(bi, CODEC_TIMEOUT_US);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = cod.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEnc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    retries = 0;
                } else if (oi >= 0) {
                    retries = 0;
                    ByteBuffer ob = cod.getOutputBuffer(oi);
                    int si = bi.presentationTimeUs < 0 ? 0
                            : (int)(bi.presentationTimeUs / sectionLengthUs);
                    if (si >= maxSec) si = maxSec - 1;
                    if (ob != null && bi.size > 0) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        if (pcmEnc == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer fb = ob.asFloatBuffer();
                            while (fb.hasRemaining()) { double s = fb.get(); sumSq[si] += s*s; counts[si]++; }
                        } else {
                            ShortBuffer sb = ob.asShortBuffer();
                            while (sb.hasRemaining()) { double s = sb.get()/32768.0; sumSq[si] += s*s; counts[si]++; }
                        }
                    }
                    cod.releaseOutputBuffer(oi, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inDone && ++retries > 50) outDone = true;
                }
            }

            for (int i = 0; i < maxSec; i++) {
                if (counts[i] > 0) {
                    double rms = Math.sqrt(sumSq[i] / counts[i]);
                    float db = rms < 1e-10 ? -100f : (float)(20.0 * Math.log10(rms));
                    result.add(new float[]{(float)i * sectionLengthMs, db});
                }
            }
            if (progress != null) progress.onProgress(100);
            Log.d(TAG, "Sektionsanalyse: " + result.size() + " Sektionen für " + uri);
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Sektionsanalyse fehlgeschlagen: " + uri, e);
            return result;
        } finally {
            if (cod != null) {
                try { cod.stop(); } catch (Exception ignored) {}
                try { cod.release(); } catch (Exception ignored) {}
            }
            ext.release();
        }
    }
}
