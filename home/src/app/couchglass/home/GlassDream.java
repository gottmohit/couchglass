package app.couchglass.home;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The Couchglass screensaver. It shows one of four scenes (see {@link Scenes}) and moves to the next
 * every few minutes; left and right change scene, OK plays the show on "Up next", any other button
 * wakes the TV. When it starts it asks Couchglass Care to clean memory; shortly before the TV would
 * fall into standby (which some TVs fail to wake from) it counts down and does a full shutdown instead.
 */
public class GlassDream extends DreamService {
    private static final String CARE_PKG = "app.couchglass.care";
    private static final String CARE_SERVICE = "app.couchglass.care.CleanerService";
    private static final long COUNTDOWN_MS = 60_000;
    private static final long MUSIC_RETRY_MS = 10 * 60_000L;
    private static final long SCENE_MS = 6 * 60_000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Weather.Now weather;
    private FrameLayout stage;
    private View scene;
    private int sceneIndex;
    private long sceneSince;
    private LinearLayout pill;
    private TextView pillClock, banner;
    private List<Scenes.Pick> picks = new ArrayList<>();
    private long shutdownAt; // uptime millis; 0 = never
    private long hintUntil;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true); // we handle the remote: left/right change scene, OK plays, the rest wake
        setFullscreen(true);
        setScreenBright(true);

        Ui.init(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        weather = new Weather(this).cached();
        Ui.apply(Ui.resolve(prefs.getString("theme", "Auto"), weather == null ? 0 : weather.sunrise, weather == null ? 0 : weather.sunset));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        // A small clock for scenes that don't draw their own (Drift).
        pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setPadding(dp(26), dp(14), dp(26), dp(16));
        pill.setBackground(new Soft(Soft.Kind.GLASS, dp(24), dp(12), Ui.GLASS));
        pillClock = Ui.text(this, "", 40, Ui.INK, Ui.DISPLAY);
        pill.addView(pillClock);
        TextView sub = Ui.text(this, android.text.format.DateFormat.format("EEEE, d MMMM", new Date())
                + (weather == null ? "" : "  ·  " + weather.temp + "°"), 13, Ui.INK_SOFT, Ui.BODY);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(4);
        pill.addView(sub, sp);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        pp.leftMargin = dp(56);
        pp.bottomMargin = dp(48);
        root.addView(pill, pp);

        banner = Ui.text(this, "", 13, Ui.INK, Ui.BOLD);
        banner.setPadding(dp(18), dp(9), dp(18), dp(9));
        banner.setBackground(new Soft(Soft.Kind.GLASS, dp(18), dp(8), Ui.POPUP));
        banner.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = dp(28);
        root.addView(banner, bp);
        setContentView(root);

        loadPicks();
        sceneIndex = Math.max(0, Math.min(Scenes.NAMES.length - 1, prefs.getInt("dream_scene", Scenes.SKY)));
        if (sceneIndex == Scenes.UP_NEXT) sceneIndex = Scenes.SKY; // its shows load a moment later
        show(sceneIndex, false);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        hintUntil = SystemClock.uptimeMillis() + 6000;
        tick.run();
        askCare("app.couchglass.care.action.CLEAN");
        planShutdown(false);
    }

    @Override
    public void onDreamingStopped() {
        ui.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (up) {
                step(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                prefs.edit().putInt("dream_scene", sceneIndex).apply();
                planShutdown(true); // a button press restarts the TV's own sleep countdown too
                hintUntil = SystemClock.uptimeMillis() + 3000;
                tick.run();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            Scenes.Pick pick = scene instanceof Scenes.UpNext ? ((Scenes.UpNext) scene).current() : null;
            if (pick != null && pick.intent != null) {
                if (up) {
                    try {
                        startActivity(new Intent(pick.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                    }
                    wakeUp();
                }
                return true;
            }
        }
        if (up) wakeUp(); // any other button: back to the TV
        return true;
    }

    /** The shows Android TV's Watch Next list knows about, with their artwork, for the "Up next" scene. */
    private void loadPicks() {
        new Thread(() -> {
            List<Scenes.Pick> out = new ArrayList<>();
            Weather source = new Weather(this);
            if (source.stale()) {
                Weather.Now fresh = source.refresh(); // scenes made from now on get it
                if (fresh != null) ui.post(() -> weather = fresh);
            }
            try {
                Apps repo = new Apps(this);
                PackageManager pm = getPackageManager();
                List<Apps.Continue> all = repo.continueWatching(new ArrayList<>());
                // Shows from streaming apps first; live channels (often just a logo) last.
                java.util.Collections.sort(all, (a, b) -> Boolean.compare(Apps.LIVE_TV_PKG.equals(a.pkg), Apps.LIVE_TV_PKG.equals(b.pkg)));
                for (Apps.Continue w : all) {
                    if (out.size() >= 4) break;
                    if (w.posterUri == null) continue;
                    Bitmap b = repo.loadPoster(w.posterUri, dp(440));
                    if (b == null) continue;
                    Scenes.Pick k = new Scenes.Pick();
                    k.title = w.title;
                    k.meta = w.meta == null ? "" : w.meta;
                    k.progressPct = w.progressPct;
                    k.intent = w.intent;
                    k.poster = b;
                    // A soft blur without RenderScript: shrink to a few pixels, then grow back in one smooth step.
                    Bitmap tiny = Bitmap.createScaledBitmap(b, 12, Math.max(1, 12 * b.getHeight() / b.getWidth()), true);
                    k.blur = Bitmap.createScaledBitmap(tiny, 192, Math.max(1, 192 * tiny.getHeight() / tiny.getWidth()), true);
                    try {
                        k.app = pm.getApplicationLabel(pm.getApplicationInfo(w.pkg, 0)).toString();
                    } catch (Exception e) {
                        k.app = "";
                    }
                    out.add(k);
                }
            } catch (Exception ignored) {
            }
            ui.post(() -> picks = out);
        }, "dream-picks").start();
    }

    private boolean available(int i) {
        return i != Scenes.UP_NEXT || !picks.isEmpty();
    }

    private void step(int dir) {
        int n = Scenes.NAMES.length, i = sceneIndex;
        do {
            i = (i + dir + n) % n;
        } while (!available(i));
        show(i, true);
    }

    private void show(int i, boolean fade) {
        sceneIndex = i;
        sceneSince = SystemClock.uptimeMillis();
        View next;
        switch (i) {
            case Scenes.DRIFT:
                next = new Scenes.Drift(this);
                break;
            case Scenes.FLIP:
                next = new Scenes.Flip(this, weather);
                break;
            case Scenes.UP_NEXT:
                next = new Scenes.UpNext(this, picks);
                break;
            default:
                next = new Scenes.Sky(this, weather);
        }
        View old = scene;
        scene = next;
        stage.addView(next, new FrameLayout.LayoutParams(-1, -1));
        pill.animate().alpha(i == Scenes.DRIFT ? 1 : 0).setDuration(800).start();
        if (old != null && fade) {
            next.setAlpha(0);
            next.animate().alpha(1).setDuration(1200).withEndAction(() -> stage.removeView(old)).start();
        } else if (old != null) {
            stage.removeView(old);
        }
    }

    /**
     * Shut down one minute before the TV's own sleep would kick in. The TV counts its sleep
     * timeout from the last button press, and the screensaver started screen_off_timeout after it.
     */
    private void planShutdown(boolean afterPress) {
        long sleep = Settings.Secure.getLong(getContentResolver(), "sleep_timeout", -1);
        long dreamAfter = Settings.System.getLong(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 300_000);
        if (sleep <= 0) {
            shutdownAt = 0; // the TV is set to never sleep: leave it alone
            return;
        }
        long inDream = afterPress ? sleep - 60_000 : sleep - dreamAfter - 60_000; // after a press, the full timeout again
        shutdownAt = SystemClock.uptimeMillis() + Math.max(COUNTDOWN_MS + 30_000, inDream);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long up = SystemClock.uptimeMillis();
            pillClock.setText(Scenes.time(GlassDream.this, new Date()));
            pill.setTranslationX((float) Math.cos(System.currentTimeMillis() / 60_000 % 12 / 12.0 * 2 * Math.PI) * dp(20));

            if (up - sceneSince > SCENE_MS) step(1);

            boolean counting = false;
            if (shutdownAt > 0) {
                long left = shutdownAt - up;
                AudioManager am = getSystemService(AudioManager.class);
                if (left <= COUNTDOWN_MS && am != null && am.isMusicActive()) {
                    shutdownAt += MUSIC_RETRY_MS; // something is playing: don't cut it off
                } else if (left <= 0) {
                    banner.setText("Turning off…");
                    counting = true;
                    askCare("app.couchglass.care.action.POWER_OFF");
                    shutdownAt = 0;
                } else if (left <= COUNTDOWN_MS) {
                    banner.setText("Turning off in " + (left / 1000) + " s  ·  press any button to stay on");
                    counting = true;
                }
            }
            if (!counting && up < hintUntil) banner.setText("◀  ▶   change scene   ·   " + Scenes.NAMES[sceneIndex]);
            banner.setVisibility(counting || up < hintUntil || "Turning off…".contentEquals(banner.getText()) ? View.VISIBLE : View.GONE);
            ui.removeCallbacks(this);
            ui.postDelayed(this, 1000);
        }
    };

    private void askCare(String action) {
        try {
            startForegroundService(new Intent(action).setComponent(new ComponentName(CARE_PKG, CARE_SERVICE)));
        } catch (Exception ignored) {
            // Care isn't installed: the screensaver still shows; the TV's own sleep takes over.
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
