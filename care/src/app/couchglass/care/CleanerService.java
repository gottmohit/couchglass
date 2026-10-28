package app.couchglass.care;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * Keeps auto-clean running: cleans when the TV goes to sleep and when free
 * memory drops below the user's limit. Costs one small process.
 */
public class CleanerService extends Service {
    private static final String TAG = "Care";
    private static final String CHANNEL = "autoclean";
    private static final long CHECK_EVERY_MS = 60_000;
    private static final long LOW_MEMORY_COOLDOWN_MS = 3 * 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLowClean;
    private boolean screenOn = true;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenOn = false;
                handler.removeCallbacks(check);
                if (Care.get(c).autoOnSleep()) cleanInBackground("TV went to sleep", true);
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                screenOn = true;
                handler.removeCallbacks(check);
                handler.postDelayed(check, CHECK_EVERY_MS);
            }
        }
    };

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            Care tv = Care.get(CleanerService.this);
            if (screenOn && tv.autoOnLow()) {
                long freeMb = Cleaner.readMemory().freeKb / 1024;
                long now = System.currentTimeMillis();
                if (freeMb < tv.lowMb() && now - lastLowClean > LOW_MEMORY_COOLDOWN_MS) {
                    lastLowClean = now;
                    cleanInBackground("Memory below " + tv.lowMb() + " MB", false);
                }
            }
            handler.postDelayed(this, CHECK_EVERY_MS);
        }
    };

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, CleanerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Auto-clean", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Couchglass Care auto-clean is on")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        startForeground(1, n);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, f);
        handler.postDelayed(check, CHECK_EVERY_MS);
    }

    // Commands other apps signed with our key (the Couchglass launcher) can send.
    public static final String ACTION_CLEAN = "app.couchglass.care.action.CLEAN";
    public static final String ACTION_POWER_OFF = "app.couchglass.care.action.POWER_OFF";
    public static final String ACTION_RESTART = "app.couchglass.care.action.RESTART";
    public static final String ACTION_SLEEP_TIMER = "app.couchglass.care.action.SLEEP_TIMER";
    public static final String EXTRA_MINUTES = "minutes";
    public static final String EXTRA_REPLY_TO = "reply_to";
    /** Sent back to EXTRA_REPLY_TO's package after a clean, with "closed" and "freed_kb". */
    public static final String ACTION_CLEANED = "app.couchglass.care.action.CLEANED";

    private final Runnable sleepShutdown = () -> runPower(true);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CLEAN.equals(action)) {
            String replyTo = intent.getStringExtra(EXTRA_REPLY_TO);
            Care tv = Care.get(this);
            tv.worker.execute(() -> {
                Intent reply = new Intent(ACTION_CLEANED);
                try {
                    tv.ensureConnected(60_000);
                    Cleaner.Result r = tv.cleaner.clean();
                    tv.cleaner.logClean("Home screen", r);
                    reply.putExtra("closed", r.closed).putExtra("freed_kb", r.freedKb);
                } catch (Exception e) {
                    reply.putExtra("error", e.getMessage());
                }
                if (replyTo != null) sendBroadcast(reply.setPackage(replyTo));
            });
        } else if (ACTION_POWER_OFF.equals(action)) {
            runPower(true);
        } else if (ACTION_RESTART.equals(action)) {
            runPower(false);
        } else if (ACTION_SLEEP_TIMER.equals(action)) {
            int minutes = intent.getIntExtra(EXTRA_MINUTES, 0);
            handler.removeCallbacks(sleepShutdown);
            if (minutes > 0) handler.postDelayed(sleepShutdown, minutes * 60_000L);
        }
        return START_STICKY;
    }

    private void runPower(boolean shutdown) {
        Care tv = Care.get(this);
        tv.worker.execute(() -> {
            try {
                tv.ensureConnected(60_000);
                if (shutdown) tv.cleaner.powerOff(); else tv.cleaner.restart();
            } catch (Exception e) {
                Log.w(TAG, "Power command failed: " + e.getMessage());
            }
        });
    }

    private void cleanInBackground(String reason, boolean holdWake) {
        PowerManager.WakeLock wl = null;
        if (holdWake) {
            // Keep the CPU up long enough to finish before the TV sleeps.
            wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "couchglass:clean");
            wl.acquire(20_000);
        }
        final PowerManager.WakeLock lock = wl;
        Care tv = Care.get(this);
        tv.worker.execute(() -> {
            try {
                tv.ensureConnected(0);
                Cleaner.Result r = tv.cleaner.clean();
                tv.cleaner.logClean(reason, r);
            } catch (Exception e) {
                Log.w(TAG, "Auto-clean failed: " + e.getMessage());
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
            }
        });
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(screenReceiver);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
