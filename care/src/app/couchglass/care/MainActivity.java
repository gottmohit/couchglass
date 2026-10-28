package app.couchglass.care;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    // Palette: deep green-black ground, mint accent, amber/red for memory pressure.
    private static final int BG = 0xFF0E1412;
    private static final int SURFACE = 0xFF17201D;
    private static final int SURFACE_FOCUS = 0xFF22302B;
    private static final int LINE = 0xFF2A3632;
    private static final int FG = 0xFFE6EDEA;
    private static final int MUTED = 0xFF94A39D;
    private static final int ACCENT = 0xFF3FC79C;
    private static final int ACCENT_DARK = 0xFF0F3A2D;
    private static final int AMBER = 0xFFF0B75A;
    private static final int RED = 0xFFFF8A80;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Care tv;

    private TextView freeText, freeSub, swapText, statusText, banner;
    private View usedBar;
    private FrameLayout barTrack;
    private LinearLayout appList, logList;
    private TextView cleanBtn, powerBtn, restartBtn, autoSleepRow, autoLowRow, limitRow;
    private String pendingConfirm; // "power" or "restart" after the first press
    private boolean busy;

    private final Runnable memTick = new Runnable() {
        @Override
        public void run() {
            showMemory();
            ui.postDelayed(this, 3000);
        }
    };

    private final Runnable appsTick = new Runnable() {
        @Override
        public void run() {
            refreshApps();
            ui.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tv = Care.get(this);
        setContentView(buildLayout());
        CleanerService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(memTick);
        connectThenRefresh();
        cleanBtn.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(memTick);
        ui.removeCallbacks(appsTick);
    }

    // ---- actions ----

    private void connectThenRefresh() {
        showBanner("Connecting… If the TV asks \"Allow USB debugging?\", tick \"Always allow from this computer\" and press OK.");
        tv.worker.execute(() -> {
            try {
                tv.ensureConnected(60_000);
                ui.post(() -> {
                    banner.setVisibility(View.GONE);
                    ui.removeCallbacks(appsTick);
                    ui.post(appsTick);
                });
            } catch (AdbClient.AuthPendingException e) {
                ui.post(() -> {
                    showBanner("Nothing was allowed in time. Press OK on the popup, or reopen Couchglass Care to try again.");
                    ui.postDelayed(this::connectThenRefresh, 15000);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    showBanner("Can't reach TV debugging (" + e.getMessage() + "). Check that USB debugging is still on in Developer options.");
                    ui.postDelayed(this::connectThenRefresh, 5000);
                });
            }
        });
    }

    private void refreshApps() {
        if (!tv.adb.isConnected()) return;
        tv.worker.execute(() -> {
            try {
                List<Cleaner.AppEntry> apps = tv.cleaner.listRunning();
                ui.post(() -> showApps(apps));
            } catch (Exception e) {
                ui.post(() -> setStatus("Couldn't list apps: " + e.getMessage()));
                ui.post(this::connectThenRefresh);
            }
        });
    }

    private void cleanNow() {
        if (busy) return;
        busy = true;
        setStatus("Cleaning…");
        tv.worker.execute(() -> {
            try {
                tv.ensureConnected(60_000);
                Cleaner.Result r = tv.cleaner.clean();
                tv.cleaner.logClean("You pressed Clean", r);
                ui.post(() -> {
                    setStatus(r.closed == 0 ? "Nothing to close. Memory is already clean."
                            : "Closed " + r.closed + " app" + (r.closed == 1 ? "" : "s") + ", freed " + mb(r.freedKb) + ".");
                    showMemory();
                    showLog();
                    refreshApps();
                });
            } catch (Exception e) {
                ui.post(() -> setStatus("Clean failed: " + e.getMessage()));
            } finally {
                busy = false;
            }
        });
    }

    private void power(String which) {
        if (!which.equals(pendingConfirm)) {
            pendingConfirm = which;
            powerBtn.setText(which.equals("power") ? "Press again to power off" : "Power off");
            restartBtn.setText(which.equals("restart") ? "Press again to restart" : "Restart");
            ui.removeCallbacks(resetConfirm);
            ui.postDelayed(resetConfirm, 4000);
            return;
        }
        resetConfirm.run();
        setStatus(which.equals("power") ? "Powering off…" : "Restarting…");
        tv.worker.execute(() -> {
            try {
                tv.ensureConnected(60_000);
                if (which.equals("power")) tv.cleaner.powerOff(); else tv.cleaner.restart();
            } catch (Exception e) {
                ui.post(() -> setStatus("Couldn't do that: " + e.getMessage()));
            }
        });
    }

    private final Runnable resetConfirm = () -> {
        pendingConfirm = null;
        powerBtn.setText("Power off");
        restartBtn.setText("Restart");
    };

    // ---- rendering ----

    private void showMemory() {
        Cleaner.MemInfo m = Cleaner.readMemory();
        long freeMb = m.freeKb / 1024;
        freeText.setText(freeMb + " MB");
        freeText.setTextColor(freeMb < 200 ? RED : freeMb < 400 ? AMBER : ACCENT);
        freeSub.setText("free of " + gb(m.totalKb) + " · about " + gb(m.totalKb - m.freeKb) + " in use");
        long swapUsed = m.swapTotalKb - m.swapFreeKb;
        swapText.setText("Swap in use: " + mb(swapUsed) + " of " + mb(m.swapTotalKb));
        int w = barTrack.getWidth();
        if (w > 0 && m.totalKb > 0) {
            ViewGroup.LayoutParams lp = usedBar.getLayoutParams();
            lp.width = (int) (w * (m.totalKb - m.freeKb) / (double) m.totalKb);
            usedBar.setLayoutParams(lp);
            ((GradientDrawable) usedBar.getBackground()).setColor(freeMb < 200 ? RED : freeMb < 400 ? AMBER : ACCENT);
        }
    }

    private void showApps(List<Cleaner.AppEntry> apps) {
        View focused = getCurrentFocus();
        String focusedPkg = focused != null && focused.getParent() == appList ? (String) focused.getTag() : null;
        appList.removeAllViews();
        View refocus = null;
        long closable = 0;
        for (Cleaner.AppEntry e : apps) {
            if (e.state == Cleaner.State.WILL_CLOSE) closable += e.pssKb;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(8), dp(14), dp(8));
            row.setBackground(focusable(SURFACE, SURFACE_FOCUS));
            row.setFocusable(true);
            row.setTag(e.pkg);
            boolean canToggle = e.state == Cleaner.State.WILL_CLOSE || e.state == Cleaner.State.PROTECTED;
            row.setOnClickListener(v -> {
                if (!canToggle) {
                    setStatus(e.label + " is " + stateText(e.state).toLowerCase(Locale.ROOT) + ", so it's never closed.");
                    return;
                }
                boolean nowProtected = e.state != Cleaner.State.PROTECTED;
                tv.cleaner.setProtected(e.pkg, nowProtected);
                setStatus(nowProtected ? e.label + " is protected and won't be closed." : e.label + " can be closed again.");
                refreshApps();
            });

            TextView name = text(e.label, 15, FG, false);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView size = text(mb(e.pssKb), 14, MUTED, false);
            size.setGravity(Gravity.END);
            row.addView(size, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView chip = text(stateText(e.state), 12, chipColor(e.state), true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(3), dp(10), dp(3));
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(12));
            cb.setStroke(dp(1), chipColor(e.state));
            chip.setBackground(cb);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(12);
            row.addView(chip, clp);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            appList.addView(row, lp);
            if (e.pkg.equals(focusedPkg)) refocus = row;
        }
        if (refocus != null) refocus.requestFocus();
        cleanBtn.setText(closable > 0 ? "Clean memory now  ·  ~" + mb(closable) : "Clean memory now");
        showLog();
    }

    private void showLog() {
        logList.removeAllViews();
        List<String[]> log = tv.cleaner.getLog();
        if (log.isEmpty()) {
            logList.addView(text("No cleans yet.", 13, MUTED, false));
            return;
        }
        for (int i = 0; i < Math.min(5, log.size()); i++) {
            String[] p = log.get(i);
            String when = DateFormat.format("d MMM, h:mm a", Long.parseLong(p[0])).toString();
            String line = when + "  ·  " + p[1] + "  ·  closed " + p[2] + ", freed " + mb(Long.parseLong(p[3]));
            logList.addView(text(line, 13, MUTED, false));
        }
    }

    private void updateAutoRows() {
        autoSleepRow.setText("Clean when the TV goes to sleep:  " + (tv.autoOnSleep() ? "ON" : "OFF"));
        autoLowRow.setText("Clean when free memory is low:  " + (tv.autoOnLow() ? "ON" : "OFF"));
        limitRow.setText("Low-memory limit:  " + tv.lowMb() + " MB  (press to change)");
    }

    // ---- layout ----

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(40), dp(28), dp(40), dp(24));

        // Left: memory and the three big actions.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("Couchglass Care", 30, FG, true);
        left.addView(title);
        left.addView(text("Memory and power for your Redmi TV", 14, MUTED, false));
        left.addView(space(18));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));
        card.setBackground(round(SURFACE, LINE));
        card.addView(text("FREE MEMORY", 11, MUTED, true));
        freeText = text("— MB", 40, ACCENT, true);
        card.addView(freeText);
        freeSub = text("", 13, MUTED, false);
        card.addView(freeSub);
        card.addView(space(10));
        barTrack = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setColor(LINE);
        track.setCornerRadius(dp(4));
        barTrack.setBackground(track);
        usedBar = new View(this);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(ACCENT);
        fill.setCornerRadius(dp(4));
        usedBar.setBackground(fill);
        barTrack.addView(usedBar, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(barTrack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        card.addView(space(8));
        swapText = text("", 13, MUTED, false);
        card.addView(swapText);
        left.addView(card);
        left.addView(space(16));

        cleanBtn = button("Clean memory now", true);
        cleanBtn.setOnClickListener(v -> cleanNow());
        left.addView(cleanBtn, fullWidth(10));
        powerBtn = button("Power off", false);
        powerBtn.setOnClickListener(v -> power("power"));
        left.addView(powerBtn, fullWidth(10));
        restartBtn = button("Restart", false);
        restartBtn.setOnClickListener(v -> power("restart"));
        left.addView(restartBtn, fullWidth(10));
        statusText = text("Power off here is a full shutdown, the same as holding the power button.", 13, MUTED, false);
        left.addView(statusText);

        // Right: running apps, auto-clean, history.
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        slp.leftMargin = dp(36);
        root.addView(scroll, slp);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(right);

        banner = text("", 14, BG, true);
        banner.setPadding(dp(14), dp(10), dp(14), dp(10));
        banner.setBackground(round(AMBER, AMBER));
        banner.setVisibility(View.GONE);
        right.addView(banner, fullWidth(12));

        right.addView(text("Running apps", 20, FG, true));
        right.addView(text("Select an app to protect it from cleaning, or to allow it again.", 13, MUTED, false));
        right.addView(space(10));
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        appList.addView(text("Connecting to the TV…", 14, MUTED, false));
        right.addView(appList);
        right.addView(space(18));

        right.addView(text("Auto-clean", 20, FG, true));
        right.addView(space(8));
        autoSleepRow = settingRow();
        autoSleepRow.setOnClickListener(v -> { tv.setAutoOnSleep(!tv.autoOnSleep()); updateAutoRows(); });
        right.addView(autoSleepRow, fullWidth(4));
        autoLowRow = settingRow();
        autoLowRow.setOnClickListener(v -> { tv.setAutoOnLow(!tv.autoOnLow()); updateAutoRows(); });
        right.addView(autoLowRow, fullWidth(4));
        limitRow = settingRow();
        limitRow.setOnClickListener(v -> {
            int[] steps = {150, 200, 300, 400};
            int next = steps[0];
            for (int i = 0; i < steps.length; i++) if (steps[i] == tv.lowMb()) next = steps[(i + 1) % steps.length];
            tv.setLowMb(next);
            updateAutoRows();
        });
        right.addView(limitRow, fullWidth(4));
        updateAutoRows();
        right.addView(space(18));

        right.addView(text("Recent cleans", 20, FG, true));
        right.addView(space(6));
        logList = new LinearLayout(this);
        logList.setOrientation(LinearLayout.VERTICAL);
        right.addView(logList);
        showLog();

        return root;
    }

    // ---- small view helpers ----

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private TextView button(String label, boolean primary) {
        TextView b = text(label, 18, primary ? ACCENT : FG, true);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(20), dp(14), dp(20), dp(14));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(focusable(primary ? ACCENT_DARK : SURFACE, SURFACE_FOCUS));
        return b;
    }

    private TextView settingRow() {
        TextView t = text("", 15, FG, false);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusable(SURFACE, SURFACE_FOCUS));
        return t;
    }

    private Drawable focusable(int normal, int focused) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = new GradientDrawable();
        f.setColor(focused);
        f.setCornerRadius(dp(10));
        f.setStroke(dp(2), ACCENT);
        s.addState(new int[]{android.R.attr.state_focused}, f);
        GradientDrawable n = new GradientDrawable();
        n.setColor(normal);
        n.setCornerRadius(dp(10));
        s.addState(new int[]{}, n);
        return s;
    }

    private Drawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private LinearLayout.LayoutParams fullWidth(int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottom);
        return lp;
    }

    private void setStatus(String s) {
        statusText.setText(s);
        statusText.setTextColor(FG);
    }

    private void showBanner(String s) {
        banner.setText(s);
        banner.setVisibility(View.VISIBLE);
    }

    private static String stateText(Cleaner.State s) {
        switch (s) {
            case ON_SCREEN: return "On screen";
            case PLAYING: return "Playing";
            case PROTECTED: return "Protected";
            case SYSTEM: return "System";
            default: return "Will close";
        }
    }

    private static int chipColor(Cleaner.State s) {
        switch (s) {
            case WILL_CLOSE: return AMBER;
            case PROTECTED: return ACCENT;
            case ON_SCREEN:
            case PLAYING: return 0xFF7FB8FF;
            default: return MUTED;
        }
    }

    private static String mb(long kb) {
        return (kb / 1024) + " MB";
    }

    private static String gb(long kb) {
        return String.format(Locale.US, "%.2f GB", kb / 1024.0 / 1024.0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
