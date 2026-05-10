package com.example.sleepplayer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * Vollbild-Activity für die 4-7-8 Atemübung.
 * Zeigt animierte Atembewegungen, Phasennamen und einen Countdown.
 */
public class MeditationActivity extends AppCompatActivity
        implements PlaybackService.MeditationUICallback {

    private static final int COLOR_PREPARE = 0xFF78909C;
    private static final int COLOR_INHALE  = 0xFF29B6F6;
    private static final int COLOR_HOLD    = 0xFFCE93D8;
    private static final int COLOR_EXHALE  = 0xFF26C6DA;
    private static final int COLOR_DONE    = 0xFFFFD54F;

    private View viewOuterGlow;
    private View viewBreathCircle;
    private TextView tvPhaseIcon;
    private TextView tvPhaseCountdown;
    private TextView tvPhaseName;
    private TextView tvPhaseDesc;
    private TextView tvRhythm;
    private TextView tvElapsedTime;
    private TextView tvCycleCount;
    private Button btnStop;
    private Button btnResume;
    private Button btnCalendar;

    private PlaybackService playbackService;
    private boolean isBound = false;
    private boolean returningToMain = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AnimatorSet currentAnimator;

    private long startTimeMs;
    private long phaseEndTimeMs;

    private final Runnable elapsedRunnable = new Runnable() {
        @Override public void run() {
            long elapsed = System.currentTimeMillis() - startTimeMs;
            tvElapsedTime.setText(formatMs(elapsed));
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable countdownRunnable = new Runnable() {
        @Override public void run() {
            long remaining = phaseEndTimeMs - System.currentTimeMillis();
            if (remaining < 0) remaining = 0;
            long secs = (remaining + 999) / 1000;
            tvPhaseCountdown.setText(String.valueOf(secs));
            if (remaining > 0) handler.postDelayed(this, 100);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playbackService = ((PlaybackService.LocalBinder) binder).getService();
            isBound = true;
            playbackService.setMeditationUICallback(MeditationActivity.this);
            syncFromService();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_meditation);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        viewOuterGlow    = findViewById(R.id.viewOuterGlow);
        viewBreathCircle = findViewById(R.id.viewBreathCircle);
        tvPhaseIcon      = findViewById(R.id.tvPhaseIcon);
        tvPhaseCountdown = findViewById(R.id.tvPhaseCountdown);
        tvPhaseName      = findViewById(R.id.tvPhaseName);
        tvPhaseDesc      = findViewById(R.id.tvPhaseDesc);
        tvRhythm         = findViewById(R.id.tvRhythm);
        tvElapsedTime    = findViewById(R.id.tvElapsedTime);
        tvCycleCount     = findViewById(R.id.tvCycleCount);
        btnStop      = findViewById(R.id.btnStopMeditation);
        btnResume    = findViewById(R.id.btnResume);
        btnCalendar  = findViewById(R.id.btnCalendar);

        startTimeMs = System.currentTimeMillis();
        btnStop.setOnClickListener(v -> stopAndFinish());

        btnResume.setVisibility(View.GONE);

        btnCalendar.setOnClickListener(v -> {
            startActivity(new Intent(this, CalendarActivity.class));
        });

        showPrepareState();
        handler.post(elapsedRunnable);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, PlaybackService.class);
        // Service als Foreground starten falls er nicht läuft – verhindert
        // ForegroundServiceStartNotAllowedException wenn BIND_AUTO_CREATE ihn erstellt
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        if (isBound) {
            if (playbackService != null) playbackService.setMeditationUICallback(null);
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Elapsed-Time-Ticker neu starten (wurde in onStop() gestoppt)
        handler.removeCallbacks(elapsedRunnable);
        handler.post(elapsedRunnable);
    }

    @Override
    public void onBackPressed() {
        stopAndFinish();
    }

    private void stopAndFinish() {
        if (isBound && playbackService != null) {
            playbackService.stopMeditation();
        }
        finishToMain();
    }

    /**
     * Bringt die MainActivity explizit in den Vordergrund.
     * FLAG_ACTIVITY_REORDER_TO_FRONT holt sie aus dem Back-Stack,
     * ohne einen neuen Task zu erzeugen – die MediaSession bleibt aktiv.
     */
    private void bringMainActivityToFront() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    // ===== MeditationUICallback =====

    @Override
    public void onMeditationPhaseChanged(MeditationController.Phase phase, int cycle) {
        runOnUiThread(() -> {
            int maxCycles = MeditationController.getMaxCycles();
            tvCycleCount.setText("Zyklus " + cycle + " von " + maxCycles);
            switch (phase) {
                case PREPARE:  showPrepareState();   break;
                case INHALE:   showInhale();      break;
                case HOLD:     showHold();        break;
                case EXHALE:   showExhale();      break;
                case FINISHED: showFinishState(); break;
            }
        });
    }

    @Override
    public void onMeditationDone() {
        runOnUiThread(this::showFinishState);
    }

    @Override
    public void onMeditationStopped() {
        runOnUiThread(this::finishToMain);
    }

    // ===== Phasen =====


    private void showPrepareState() {
        cancelAnimators();
        tvCycleCount.setText("Zyklus 0 von " + MeditationController.getMaxCycles());
        tvPhaseIcon.setText("🌙");
        tvPhaseName.setText("VORBEREITUNG");
        tvPhaseDesc.setText("Mach es dir bequem …");
        tvPhaseCountdown.setText("–");
        tvRhythm.setVisibility(View.VISIBLE);
        tintCircle(COLOR_PREPARE);
        viewBreathCircle.setScaleX(0.5f);
        viewBreathCircle.setScaleY(0.5f);
        viewOuterGlow.setScaleX(0.5f);
        viewOuterGlow.setScaleY(0.5f);
        startPulsing(0.45f, 0.60f, 2000);
    }

    private void showInhale() {
        cancelAnimators();
        tvRhythm.setVisibility(View.GONE);
        tvPhaseIcon.setText("🫁");
        tvPhaseName.setText("EINATMEN");
        tvPhaseDesc.setText("Langsam durch die Nase einatmen");
        tintCircle(COLOR_INHALE);
        startCountdown(MeditationController.DUR_INHALE_MS);
        startScaleAnim(0.45f, 1.0f, MeditationController.DUR_INHALE_MS, new DecelerateInterpolator(1.5f));
    }

    private void showHold() {
        cancelAnimators();
        tvPhaseIcon.setText("🔵");
        tvPhaseName.setText("HALTEN");
        tvPhaseDesc.setText("Atem anhalten");
        tintCircle(COLOR_HOLD);
        startCountdown(MeditationController.DUR_HOLD_MS);
        viewBreathCircle.setScaleX(1.0f);
        viewBreathCircle.setScaleY(1.0f);
        viewOuterGlow.setScaleX(0.97f);
        viewOuterGlow.setScaleY(0.97f);
        startPulsing(0.97f, 1.03f, 2200);
    }

    private void showExhale() {
        cancelAnimators();
        tvPhaseIcon.setText("💨");
        tvPhaseName.setText("AUSATMEN");
        tvPhaseDesc.setText("Langsam durch den Mund ausatmen");
        tintCircle(COLOR_EXHALE);
        startCountdown(MeditationController.DUR_EXHALE_MS);
        startScaleAnim(1.0f, 0.45f, MeditationController.DUR_EXHALE_MS, new AccelerateInterpolator(0.7f));
    }

    private void showFinishState() {
        cancelAnimators();
        tvPhaseIcon.setText("✨");
        tvPhaseName.setText("FERTIG");
        tvPhaseDesc.setText("Gut gemacht! Schlaf gut 😴");
        tvPhaseCountdown.setText("–");
        tvRhythm.setVisibility(View.GONE);
        tintCircle(COLOR_DONE);
        btnStop.setText("Schließen");
        startPulsing(0.7f, 0.85f, 1800);
        // Nach 5 Sekunden automatisch zur MainActivity zurückkehren
        handler.postDelayed(() -> {
            finishToMain();
        }, 5000);
    }

    private void syncFromService() {
        if (playbackService == null) return;
        if (!playbackService.isMeditating()) {
            finishToMain();
            return;
        }

        MeditationController.Phase phase = playbackService.getCurrentMeditationPhase();
        int cycle = playbackService.getCurrentMeditationCycle();
        onMeditationPhaseChanged(phase, cycle);
    }

    private void finishToMain() {
        if (returningToMain) return;
        returningToMain = true;
        handler.removeCallbacksAndMessages(null);
        bringMainActivityToFront();
        finish();
    }

    // ===== Animations-Helfer =====

    private void startScaleAnim(float from, float to, long durationMs,
                                android.view.animation.Interpolator interp) {
        float outerFrom = from * 0.85f + 0.15f;
        float outerTo   = to   * 0.85f + 0.15f;
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(viewBreathCircle, "scaleX", from, to),
                ObjectAnimator.ofFloat(viewBreathCircle, "scaleY", from, to),
                ObjectAnimator.ofFloat(viewOuterGlow,    "scaleX", outerFrom, outerTo),
                ObjectAnimator.ofFloat(viewOuterGlow,    "scaleY", outerFrom, outerTo)
        );
        set.setDuration(durationMs);
        set.setInterpolator(interp);
        currentAnimator = set;
        set.start();
    }

    private void startPulsing(float min, float max, long halfPeriodMs) {
        pingPong(min, max, halfPeriodMs, false);
    }

    private void pingPong(float min, float max, long halfPeriodMs, boolean reversed) {
        float from  = reversed ? max : min;
        float to    = reversed ? min : max;
        float oFrom = from * 0.9f + 0.1f;
        float oTo   = to   * 0.9f + 0.1f;
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(viewBreathCircle, "scaleX", from, to),
                ObjectAnimator.ofFloat(viewBreathCircle, "scaleY", from, to),
                ObjectAnimator.ofFloat(viewOuterGlow,    "scaleX", oFrom, oTo),
                ObjectAnimator.ofFloat(viewOuterGlow,    "scaleY", oFrom, oTo)
        );
        set.setDuration(halfPeriodMs);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (currentAnimator == set) {
                    pingPong(min, max, halfPeriodMs, !reversed);
                }
            }
        });
        currentAnimator = set;
        set.start();
    }

    private void tintCircle(int color) {
        viewBreathCircle.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        viewOuterGlow.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        tvPhaseName.setTextColor(color);
    }

    private void cancelAnimators() {
        if (currentAnimator != null) {
            currentAnimator.cancel();
            currentAnimator = null;
        }
        handler.removeCallbacks(countdownRunnable);
    }

    private void startCountdown(long durationMs) {
        phaseEndTimeMs = System.currentTimeMillis() + durationMs;
        handler.removeCallbacks(countdownRunnable);
        handler.post(countdownRunnable);
    }

    private String formatMs(long ms) {
        long s = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
    }
}









