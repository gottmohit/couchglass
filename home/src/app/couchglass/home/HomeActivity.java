package app.couchglass.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Glass TV Home: the launcher. Layout follows the Glass TV Home design 1:1 (design px / 2 = dp). */
public class HomeActivity extends Activity {
    private static final String TVCARE_PKG = "app.couchglass.care";
    private static final String TVCARE_SERVICE = "app.couchglass.care.CleanerService";
    private static final int[] SLEEP_STEPS = {0, 30, 60, 90};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private Apps repo;
    private List<Apps.App> apps = new ArrayList<>();
    private Apps.App featured;
    private SharedPreferences prefs;
    private Weather weather;
    private WeatherIconView weatherIcon;
    private TextView weatherTemp, weatherSub;
    private View weatherPill;

    private FrameLayout root;
    private TextView clock, date, heroTitle, heroSub, memBig, memSub, cleanBtn, playBtn, cleanPlayBtn, inputBtnLabel;
    private ImageView heroArt;
    private TextView heroKicker;
    private FrameLayout heroBar;
    private TextView heroLeft;
    private Apps.Continue heroWatch; // the show the hero offers to resume; null = Game night
    private List<Apps.Continue> lastContinue = new ArrayList<>();
    private Runnable afterClean;
    private RingView ring;
    private LinearLayout continueRow, appsRow, inputMenu, tabsBar;
    private View inputBtn, castBtn, wifiBtn, settingsBtn, searchBtn;
    private final View[] controlBtns = new View[4];
    private View homeContent, gridPanel;
    private GridLayout grid;
    private TextView gridTitle, sleepValue, powerValue;
    private String tab = "Home";
    private boolean powerArmed, cleaning;

    private final BroadcastReceiver timeTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver cleaned = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            onCleaned(i.getIntExtra("closed", 0), i.getLongExtra("freed_kb", 0), i.getStringExtra("error"));
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    private final Runnable memTick = new Runnable() {
        @Override
        public void run() {
            updateMemory();
            ui.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        repo = new Apps(this);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        weather = new Weather(this);
        takeThemeExtra(getIntent());
        applyTheme();
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.GROUND));
        setContentView(build());
        wireFocus();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packagesChanged, pf);
        registerReceiver(cleaned, new IntentFilter("app.couchglass.care.action.CLEANED"));
        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(timeTick, new IntentFilter(Intent.ACTION_TIME_TICK));
        updateClock();
        ui.post(memTick);
        getSystemService(android.hardware.input.InputManager.class).registerInputDeviceListener(padListener, ui);
        loadContinue();
        updateHeroSub();
        showTab("Home");
        playBtn.requestFocus();
        showWeather(weather.cached());
        if (weather.stale()) refreshWeather();
        if (prefs.getBoolean("reopen_settings", false)) {
            prefs.edit().putBoolean("reopen_settings", false).apply();
            ui.post(this::toggleSettings);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(timeTick);
        ui.removeCallbacks(memTick);
        getSystemService(android.hardware.input.InputManager.class).unregisterInputDeviceListener(padListener);
        disarmPower();
    }

    @Override
    protected void onStop() {
        super.onStop();
        hidePopup();
        continueRow.removeAllViews(); // posters are reloaded fresh in onResume
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Soft.trimAll(); // only when Android is about to reclaim us
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        unregisterReceiver(cleaned);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (inputMenu.getVisibility() == View.VISIBLE) hidePopup();
        else if (!"Home".equals(tab)) showTab("Home");
        // On Home, Back does nothing: this is the home screen.
    }

    /** Lets a theme be picked by command (used to preview themes): --es theme Dusk. */
    private boolean takeThemeExtra(Intent intent) {
        String t = intent == null ? null : intent.getStringExtra("theme");
        if (t == null || !java.util.Arrays.asList(Ui.THEMES).contains(t) || t.equals(themeChoice())) return false;
        prefs.edit().putString("theme", t).apply();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (takeThemeExtra(intent)) {
            recreate();
            return;
        }
        // Home pressed while already here: go back to the top.
        inputMenu.setVisibility(View.GONE);
        showTab("Home");
        if (playBtn != null) playBtn.requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private View build() {
        root = new FrameLayout(this);
        root.setClipChildren(false);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        root.setBackground(new BitmapDrawable(getResources(), Ui.wallpaper(dm.widthPixels, dm.heightPixels)));

        LinearLayout page = column();
        page.setPadding(dp(36), dp(20), dp(36), dp(20));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        page.addView(header(), new LinearLayout.LayoutParams(-1, dp(44)));

        FrameLayout body = new FrameLayout(this);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout home = column();
        homeContent = home;
        body.addView(home, new FrameLayout.LayoutParams(-1, -1));
        home.addView(heroRow(), lp(-1, dp(150), 16));
        home.addView(sectionTitle("Continue watching", "From apps that share what you were watching"), lp(-1, dp(20), 20));
        continueRow = row();
        home.addView(scroller(continueRow), lp(-1, dp(107 + 24), 0));
        home.addView(sectionTitle("Your apps", null), lp(-1, dp(20), 4));
        appsRow = row();
        home.addView(scroller(appsRow), lp(-1, dp(50 + 24), 0));

        gridPanel = gridPanel();
        gridPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -1);
        gp.topMargin = dp(16);
        body.addView(gridPanel, gp);

        inputMenu = inputMenu();
        inputMenu.setVisibility(View.GONE);
        root.addView(inputMenu, new FrameLayout.LayoutParams(dp(220), -2, Gravity.END | Gravity.TOP));
        return root;
    }

    private View header() {
        LinearLayout h = row();
        h.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = row();
        left.setGravity(Gravity.BOTTOM);
        clock = Ui.text(this, "", 28, Ui.INK, Ui.DISPLAY);
        clock.setLetterSpacing(-0.02f);
        date = Ui.text(this, "", 11, Ui.MUTED, Ui.BODY);
        date.setPadding(dp(9), 0, 0, dp(3));
        left.addView(clock);
        left.addView(date);
        weatherPill = buildWeatherPill();
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, dp(32));
        wp.leftMargin = dp(13);
        wp.bottomMargin = -dp(2);
        left.addView(weatherPill, wp);
        h.addView(left, new LinearLayout.LayoutParams(0, -2, 1));

        tabsBar = row();
        tabsBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabsBar.setBackground(new Soft(Soft.Kind.GLASS, dp(20), dp(9), Ui.GLASS));
        for (String t : new String[]{"Home", "Apps", "Games", "Live TV"}) {
            TextView b = Ui.text(this, t, 11, Ui.INK_SOFT, Ui.BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(15), 0, dp(15), 0);
            b.setTag(t);
            b.setFocusable(true);
            b.setOnClickListener(v -> onTab(t));
            b.setOnFocusChangeListener((v, has) -> styleTabs(has ? t : null));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, dp(32));
            tp.leftMargin = dp(2);
            tp.rightMargin = dp(2);
            tabsBar.addView(b, tp);
        }
        h.addView(tabsBar);

        LinearLayout right = row();
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        LinearLayout input = row();
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(dp(11), 0, dp(13), 0);
        input.setBackground(new Soft(Soft.Kind.NEU_UP, dp(16), dp(4), Ui.GROUND));
        input.addView(Icons.view(this, Icons.INPUT, dp(13), Ui.INK_SOFT));
        inputBtnLabel = Ui.text(this, "Input", 9.5f, Ui.INK_SOFT, Ui.BOLD);
        inputBtnLabel.setPadding(dp(6), 0, 0, 0);
        input.addView(inputBtnLabel);
        Ui.focusGrow(input, dp(16), 1.06f);
        input.setOnClickListener(v -> toggleInputs());
        inputBtn = input;
        searchBtn = circleButton(Icons.MIC, "Search", v -> open(new Intent(Intent.ACTION_ASSIST)));
        LinearLayout.LayoutParams sp0 = new LinearLayout.LayoutParams(dp(32), dp(32));
        sp0.rightMargin = dp(9);
        right.addView(searchBtn, sp0);
        right.addView(input, new LinearLayout.LayoutParams(-2, dp(32)));
        castBtn = circleButton(Icons.CAST, "Cast and mirroring", v -> toggleCast());
        right.addView(castBtn, circleLp());
        wifiBtn = circleButton(Icons.WIFI, "Wi-Fi", v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        right.addView(wifiBtn, circleLp());
        settingsBtn = circleButton(Icons.SETTINGS, "Settings", v -> toggleSettings());
        right.addView(settingsBtn, circleLp());
        h.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        return h;
    }

    private View heroRow() {
        LinearLayout r = row();
        r.setClipChildren(false);

        // The billboard: what to do next, with its artwork filling the right side. It is the show you
        // were watching (Resume, focused when Home opens, so one OK resumes it), or Game night when a
        // controller is connected. Always dark, like a screen within the screen, whatever the theme.
        FrameLayout card = new FrameLayout(this);
        card.setClipChildren(false);
        card.setBackground(new Soft(Soft.Kind.PLAIN, dp(18), dp(15), BILLBOARD)); // flat, so the artwork fades in cleanly
        heroArt = new ImageView(this);
        heroArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(heroArt, dp(18));
        card.addView(heroArt, new FrameLayout.LayoutParams(dp(150 * 16 / 9), -1, Gravity.END));
        View scrim = new View(this); // fades the artwork's left edge into the card
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{BILLBOARD, (BILLBOARD & 0xFFFFFF) | 0x99000000, BILLBOARD & 0xFFFFFF}));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(160), -1, Gravity.END);
        slp.rightMargin = dp(150 * 16 / 9 - 154); // starts just before the artwork, covering its edge
        card.addView(scrim, slp);

        LinearLayout text = column();
        text.setClipChildren(false);
        text.setPadding(dp(22), dp(17), dp(18), dp(17));
        heroKicker = Ui.text(this, "GAME NIGHT", 8.5f, 0xB3FFFFFF, Ui.BOLD);
        heroKicker.setLetterSpacing(0.08f);
        text.addView(heroKicker);
        heroTitle = Ui.text(this, "", 28, 0xFFFFFFFF, Ui.DISPLAY);
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setMaxLines(1);
        heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(heroTitle, lp(-2, -2, 5));
        heroSub = Ui.text(this, "", 10.5f, 0xD9FFFFFF, Ui.BODY);
        heroSub.setSingleLine(true);
        heroSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(heroSub, lp(-2, -2, 5));
        LinearLayout progress = row(); // bar, then "10 min left"
        progress.setGravity(Gravity.CENTER_VERTICAL);
        heroBar = new FrameLayout(this);
        progress.addView(heroBar, new LinearLayout.LayoutParams(dp(180), dp(2.5f)));
        heroLeft = Ui.text(this, "", 9, 0xB3FFFFFF, Ui.BODY);
        heroLeft.setPadding(dp(10), 0, 0, 0);
        progress.addView(heroLeft);
        text.addView(progress, lp(-2, -2, 8));
        View spacer = new View(this);
        text.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout buttons = row();
        buttons.setClipChildren(false);
        playBtn = pillButton("Play", true);
        playBtn.setOnClickListener(v -> heroPrimary());
        buttons.addView(playBtn, new LinearLayout.LayoutParams(-2, dp(27)));
        cleanPlayBtn = pillButton("Clean memory first", false);
        cleanPlayBtn.setOnClickListener(v -> heroCleanFirst());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(27));
        cp.leftMargin = dp(8);
        buttons.addView(cleanPlayBtn, cp);
        text.addView(buttons);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -1);
        tlp.rightMargin = dp(150 * 16 / 9 - 40); // text stays clear of the artwork
        card.addView(text, tlp);
        r.addView(card, new LinearLayout.LayoutParams(0, -1, 1));

        // Right column: Couchglass Care memory + quick controls
        LinearLayout side = column();
        side.setClipChildren(false);
        LinearLayout mem = row();
        mem.setGravity(Gravity.CENTER_VERTICAL);
        mem.setClipChildren(false);
        mem.setPadding(dp(13), 0, dp(13), 0);
        mem.setBackground(new Soft(Soft.Kind.GLASS, dp(15), dp(12), Ui.GLASS));
        ring = new RingView(this);
        mem.addView(ring, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout memText = column();
        memText.setPadding(dp(11), 0, dp(8), 0);
        memText.addView(Ui.text(this, "MEMORY", 7.5f, Ui.MUTED, Ui.BOLD));
        memBig = Ui.text(this, "— MB free", 16, Ui.INK, Ui.DISPLAY);
        memText.addView(memBig, lp(-2, -2, 2));
        memSub = Ui.text(this, "", 8.5f, Ui.INK_SOFT, Ui.BODY);
        memSub.setSingleLine(true);
        memText.addView(memSub, lp(-2, -2, 2));
        mem.addView(memText, new LinearLayout.LayoutParams(0, -2, 1));
        cleanBtn = neuButton("Clean now");
        cleanBtn.setTextColor(Ui.ACCENT);
        cleanBtn.setOnClickListener(v -> clean(null));
        mem.addView(cleanBtn, new LinearLayout.LayoutParams(-2, dp(30)));
        side.addView(mem, new LinearLayout.LayoutParams(-1, dp(70)));

        // Four compact tiles in one row: icon, name, and a short state where there is one.
        LinearLayout controls = row();
        sleepValue = control(controls, Icons.MOON, "Sleep", sleepLabel(), v -> cycleSleep(), 0);
        control(controls, Icons.PICTURE, "Picture", null, v -> openPicture(), 1);
        control(controls, Icons.SOUND, "Sound", null, v -> open(new Intent().setComponent(
                new ComponentName("com.android.tv.settings", "com.android.tv.settings.device.sound.SoundActivity"))), 2);
        powerValue = control(controls, Icons.POWER, "Power off", "Shut down", v -> powerPressed(), 3);
        powerValue.setTextColor(Ui.DANGER);
        LinearLayout.LayoutParams cgp = new LinearLayout.LayoutParams(-1, dp(70));
        cgp.topMargin = dp(10);
        side.addView(controls, cgp);

        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(300), -1);
        sp.leftMargin = dp(16);
        r.addView(side, sp);
        return r;
    }

    private TextView control(LinearLayout row, int icon, String label, String value, View.OnClickListener click, int index) {
        LinearLayout b = column();
        b.setGravity(Gravity.CENTER);
        b.setBackground(new Soft(Soft.Kind.NEU_UP, dp(14), dp(4.5f), Ui.GROUND));
        b.addView(Icons.view(this, icon, dp(15), index == 3 ? Ui.DANGER : Ui.INK_SOFT));
        TextView l = Ui.text(this, label, 8.5f, Ui.INK, Ui.BOLD);
        b.addView(l, lp(-2, -2, 5));
        TextView v = Ui.text(this, value == null ? "" : value, 7.5f, Ui.MUTED, Ui.BODY);
        v.setSingleLine(true);
        if (value != null) b.addView(v, lp(-2, -2, 2));
        b.setContentDescription(label);
        Ui.focusLift(b, dp(14), 1.06f);
        controlBtns[index] = b;
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        if (index > 0) p.leftMargin = dp(10);
        row.addView(b, p);
        return v;
    }

    private View sectionTitle(String title, String note) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.addView(Ui.text(this, title, 15, Ui.INK, Ui.DISPLAY), new LinearLayout.LayoutParams(0, -2, 1));
        if (note != null) r.addView(Ui.text(this, note, 9, Ui.MUTED, Ui.BODY));
        return r;
    }

    private View gridPanel() {
        LinearLayout panel = column();
        panel.setPadding(dp(22), dp(16), dp(22), dp(16));
        panel.setBackground(new Soft(Soft.Kind.GLASS, dp(18), dp(15), Ui.GLASS));
        gridTitle = Ui.text(this, "All apps", 15, Ui.INK, Ui.DISPLAY);
        panel.addView(gridTitle, lp(-2, -2, 0));
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setPadding(dp(8), dp(12), dp(8), dp(12));
        sv.setVerticalScrollBarEnabled(false);
        grid = new GridLayout(this);
        grid.setColumnCount(6);
        grid.setClipChildren(false);
        sv.addView(grid);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return panel;
    }

    private LinearLayout inputMenu() {
        LinearLayout m = column();
        m.setClipChildren(false);
        m.setPadding(dp(14), dp(12), dp(14), dp(14));
        m.setElevation(dp(12));
        m.setBackground(new Soft(Soft.Kind.GLASS, dp(16), dp(12), Ui.POPUP)); // nearly opaque: it floats over content
        return m;
    }

    // ---------------------------------------------------------------- data → views

    private void loadApps() {
        bg.execute(() -> {
            List<Apps.App> list = repo.all();
            ui.post(() -> {
                apps = list;
                featured = repo.featuredGame(apps);
                fillAppsRow();
                fillHero();
                if (!"Home".equals(tab)) fillGrid();
                loadContinue();
            });
        });
    }

    private void fillAppsRow() {
        String focused = getCurrentFocus() != null && getCurrentFocus().getParent() == appsRow
                ? (String) getCurrentFocus().getTag() : null;
        appsRow.removeAllViews();
        List<Apps.App> row = homeApps();
        if (row.size() > 8) row = new ArrayList<>(row.subList(0, 8)); // the 9th spot is All apps
        int w = dp(89), h = dp(50), gap = dp(11);
        for (int i = 0; i < row.size(); i++) {
            Apps.App a = row.get(i);
            View tile = bannerTile(a, w, h, dp(10));
            tile.setTag(a.pkg);
            tile.setOnLongClickListener(v -> {
                appMenu(a);
                return true;
            });
            appsRow.addView(tile, tileLp(w, h, i == 0 ? 0 : gap));
            if (a.pkg.equals(focused)) tile.requestFocus();
        }
        View all = allAppsTile();
        all.setNextFocusRightId(id(all));
        appsRow.addView(all, tileLp(w, h, gap));
    }

    private View allAppsTile() {
        LinearLayout t = column();
        t.setGravity(Gravity.CENTER);
        t.setBackground(new Soft(Soft.Kind.NEU_UP, dp(10), dp(5), Ui.GROUND));
        t.addView(new GridGlyph(this), new LinearLayout.LayoutParams(dp(14), dp(14)));
        t.addView(Ui.text(this, "All apps", 9, Ui.INK_SOFT, Ui.BOLD), lp(-2, -2, 4));
        t.setContentDescription("All apps");
        Ui.focusLift(t, dp(10), 1.08f);
        t.setOnClickListener(v -> showTab("Apps"));
        return t;
    }

    /** Non-game apps you haven't hidden, in your order (new apps go at the end). */
    private List<Apps.App> homeApps() {
        java.util.Set<String> hidden = prefs.getStringSet("hidden", new java.util.HashSet<>());
        List<String> order = java.util.Arrays.asList(prefs.getString("order", "").split(","));
        List<Apps.App> out = new ArrayList<>();
        for (Apps.App a : apps) if (!a.game && !hidden.contains(a.pkg)) out.add(a);
        java.util.Collections.sort(out, (x, y) -> {
            int ix = order.indexOf(x.pkg), iy = order.indexOf(y.pkg);
            if (ix < 0 && iy < 0) return 0;
            if (ix < 0) return 1;
            if (iy < 0) return -1;
            return ix - iy;
        });
        return out;
    }

    private void saveOrder(List<Apps.App> row) {
        StringBuilder sb = new StringBuilder();
        for (Apps.App a : row) sb.append(a.pkg).append(',');
        prefs.edit().putString("order", sb.toString()).apply();
    }

    /** Hold OK on a tile: move it or hide it. */
    private void appMenu(Apps.App a) {
        inputMenu.removeAllViews();
        inputMenu.addView(Ui.text(this, a.label, 13, Ui.INK, Ui.DISPLAY));
        List<Apps.App> row = homeApps();
        int idx = row.indexOf(a);
        if (idx > 0) inputMenu.addView(menuItem("Move left", () -> moveApp(a, -1)), lp(-1, dp(30), 10));
        if (idx >= 0 && idx < row.size() - 1) inputMenu.addView(menuItem("Move right", () -> moveApp(a, 1)), lp(-1, dp(30), 7));
        inputMenu.addView(menuItem("Hide from home", () -> setHidden(a, true)), lp(-1, dp(30), 7));
        inputMenu.addView(Ui.text(this, "Hidden apps stay in the Apps tab.", 8, Ui.MUTED, Ui.BODY), lp(-1, -2, 6));
        showPopup(240, Gravity.CENTER, 0, 0);
    }

    private TextView menuItem(String label, Runnable action) {
        TextView b = neuButton(label);
        b.setOnClickListener(v -> {
            action.run();
        });
        return b;
    }

    private void moveApp(Apps.App a, int dir) {
        List<Apps.App> row = homeApps();
        int i = row.indexOf(a), j = i + dir;
        if (i < 0 || j < 0 || j >= row.size()) return;
        java.util.Collections.swap(row, i, j);
        saveOrder(row);
        fillAppsRow();
        appMenu(a); // keep the menu open so you can keep moving it
    }

    private void setHidden(Apps.App a, boolean hide) {
        java.util.Set<String> hidden = new java.util.HashSet<>(prefs.getStringSet("hidden", new java.util.HashSet<>()));
        if (hide) hidden.add(a.pkg); else hidden.remove(a.pkg);
        prefs.edit().putStringSet("hidden", hidden).apply();
        hidePopup();
        fillAppsRow();
        if (!"Home".equals(tab)) fillGrid();
        Toast.makeText(this, hide ? a.label + " hidden. Find it in Apps." : a.label + " is back on Home.", Toast.LENGTH_SHORT).show();
    }

    private View bannerTile(Apps.App a, int w, int h, int radius) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(new Soft(Soft.Kind.NEU_UP, radius, dp(5), Ui.GROUND));
        ImageView img = new ImageView(this);
        img.setImageDrawable(a.banner);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(img, radius);
        tile.addView(img, new FrameLayout.LayoutParams(-1, -1));
        tile.setContentDescription(a.label);
        Ui.focusLift(tile, radius, 1.08f);
        tile.setOnClickListener(v -> open(repo.launchIntent(a)));
        return tile;
    }

    private static final int BILLBOARD = 0xFF0E1119; // opaque, so the fade over the artwork has no seam

    /** Picks the billboard: Game night while a controller is connected, else the show to resume. */
    private void fillHero() {
        heroWatch = null;
        if (Apps.gamepadsConnected() == 0 || featured == null) {
            for (Apps.Continue w : lastContinue) {
                if (w.intent != null && (w.posterUri != null || w.fallbackArt != null)) {
                    heroWatch = w;
                    break;
                }
            }
        }
        if (heroWatch != null) {
            showWatch(heroWatch);
        } else if (featured == null) {
            heroBar.removeAllViews();
            ambient(null, null);
            heroLeft.setText("");
            heroKicker.setText("GAME NIGHT");
            heroTitle.setText("No games yet");
            heroArt.setImageDrawable(null);
            playBtn.setText("Open Play Store");
            cleanPlayBtn.setVisibility(View.GONE);
            updateHeroSub();
        } else {
            heroBar.removeAllViews();
            ambient(null, null);
            heroLeft.setText("");
            heroKicker.setText("GAME NIGHT");
            heroTitle.setText(Apps.FEATURED_GAME.equals(featured.pkg) ? "Beach Buggy Racing" : featured.label);
            heroArt.setImageDrawable(featured.banner);
            playBtn.setText("Play");
            cleanPlayBtn.setText("Clean memory first");
            cleanPlayBtn.setVisibility(View.VISIBLE);
            updateHeroSub();
        }
    }

    private void updateHeroSub() {
        if (heroSub == null || heroWatch != null) return;
        int pads = Apps.gamepadsConnected();
        String pads2 = pads == 0 ? "Connect a controller to play" : pads == 1 ? "1 controller connected" : pads + " controllers connected";
        String extra = featured != null && Apps.FEATURED_GAME.equals(featured.pkg) ? "Split screen · " : "";
        heroSub.setText(extra + pads2);
    }

    private String ambientKey = "";

    /**
     * Ambient colour: the home screen's glows take the colours of the show on the billboard and
     * cross-fade to them; null colours go back to the theme. The screensaver reads the same colours.
     * Safe to call from any thread.
     */
    private void ambient(int[] colors, String key) {
        String k = colors == null ? "" : key;
        if (k.equals(ambientKey)) return;
        ambientKey = k;
        prefs.edit().putString("ambient", colors == null ? "" : colors[0] + "," + colors[1] + "," + colors[2])
                .putLong("ambient_at", System.currentTimeMillis()).apply();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        bg.execute(() -> {
            Bitmap wall;
            synchronized (Ui.class) {
                Ui.tint(colors);
                wall = Ui.wallpaper(dm.widthPixels, dm.heightPixels);
            }
            ui.post(() -> {
                android.graphics.drawable.Drawable was = root.getBackground();
                BitmapDrawable now = new BitmapDrawable(getResources(), wall);
                if (was == null) {
                    root.setBackground(now);
                    return;
                }
                android.graphics.drawable.TransitionDrawable fade =
                        new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{was, now});
                fade.setCrossFadeEnabled(true);
                root.setBackground(fade);
                fade.startTransition(1200);
                ui.postDelayed(() -> {
                    if (root.getBackground() == fade) root.setBackground(now); // let the old picture go
                }, 1400);
            });
        });
    }

    /** Crops black bars that some apps bake into their artwork, so the picture fills the billboard. */
    static Bitmap trimBars(Bitmap b) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight(), left = 0, right = w - 1;
        while (left < w / 3 && darkColumn(b, left, h)) left++;
        while (right > w * 2 / 3 && darkColumn(b, right, h)) right--;
        if (left == 0 && right == w - 1) return b;
        return Bitmap.createBitmap(b, left, 0, right - left + 1, h);
    }

    private static boolean darkColumn(Bitmap b, int x, int h) {
        for (int y = 0; y < h; y += Math.max(1, h / 24)) {
            int p = b.getPixel(x, y);
            if (((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255) > 60) return false;
        }
        return true;
    }

    /** Posters already shown on the billboard, so browsing back and forth doesn't fetch them again. */
    private final android.util.LruCache<String, Bitmap> posters = new android.util.LruCache<>(8);

    /** Puts a show on the billboard: its name, episode, time left, artwork and colours. */
    private void showWatch(Apps.Continue w) {
        heroWatch = w;
        heroBar.removeAllViews();
        Apps.App a = repo.find(apps, w.pkg);
        heroKicker.setText(a == null ? "UP NEXT" : "UP NEXT  ·  " + a.label.toUpperCase(java.util.Locale.ROOT));
        heroTitle.setText(w.title);
        heroSub.setText(heroLine(w));
        heroLeft.setText(w.left == null ? "" : w.left);
        if (w.progressPct > 0) heroBar.addView(new ProgressBarView(this, w.progressPct), new FrameLayout.LayoutParams(-1, -1));
        boolean show = w.posterUri != null || w.session != null; // we know the show, not just the app
        playBtn.setText(show ? "▶  Resume" : "▶  Open " + (a == null ? "app" : a.label));
        cleanPlayBtn.setText("Clean memory first");
        cleanPlayBtn.setVisibility(View.VISIBLE);
        if (w.posterUri == null) {
            heroArt.setImageDrawable(brandGlow(w.fallbackArt)); // no poster: no logo either, a glow in the app's colour
            ambient(null, null);
            return;
        }
        Bitmap cached = posters.get(w.posterUri);
        if (cached != null) {
            setHeroArt(cached);
            bg.execute(() -> ambient(Ui.palette(cached), w.posterUri));
            return;
        }
        bg.execute(() -> {
            Bitmap b = trimBars(repo.loadPoster(w.posterUri, dp(300)));
            if (b != null) {
                posters.put(w.posterUri, b);
                ui.post(() -> {
                    if (heroWatch == w) setHeroArt(b);
                });
            }
            if (heroWatch == w) ambient(b == null ? null : Ui.palette(b), w.posterUri);
        });
    }

    private void setHeroArt(Bitmap b) {
        heroArt.setAlpha(0.35f);
        heroArt.setImageBitmap(b);
        heroArt.animate().alpha(1).setDuration(350).start();
    }

    /** Browsing Continue watching: after a short pause on a card, the billboard becomes that show. */
    private Runnable preview;

    private void previewSoon(Apps.Continue w) {
        if (preview != null) ui.removeCallbacks(preview);
        if (w.intent == null || (w.posterUri == null && w.fallbackArt == null)) return;
        preview = () -> {
            if (heroWatch != w) showWatch(w);
        };
        ui.postDelayed(preview, 350);
    }

    /** "S4 · E105 · The Battlefront of Alabasta!  ·  10 min left", from whatever the app shared. */
    private static String heroLine(Apps.Continue w) {
        List<String> parts = new ArrayList<>();
        if (w.seasonEpisode != null && !w.seasonEpisode.isEmpty()) parts.add(w.seasonEpisode);
        if (w.episode != null) parts.add(w.episode);
        String line = android.text.TextUtils.join(" · ", parts);
        if (line.isEmpty()) line = w.left != null ? "" : w.meta == null || w.meta.isEmpty() ? "Pick up where you left off" : w.meta;
        return line;
    }

    /** A radial glow in the most vivid colour of an app's banner (Netflix red, Prime blue). */
    private android.graphics.drawable.Drawable brandGlow(android.graphics.drawable.Drawable banner) {
        int color = Ui.ACCENT;
        if (banner != null) {
            Bitmap b = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
            android.graphics.Rect was = banner.copyBounds(); // the same drawable is on the app's tile
            banner.setBounds(0, 0, 48, 27);
            banner.draw(new android.graphics.Canvas(b));
            banner.setBounds(was);
            float best = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 27; y++) {
                for (int x = 0; x < 48; x++) {
                    int p = b.getPixel(x, y);
                    if ((p >>> 24) < 200) continue;
                    android.graphics.Color.colorToHSV(p, hsv);
                    float score = hsv[1] * hsv[2];
                    if (score > best) {
                        best = score;
                        color = p | 0xFF000000;
                    }
                }
            }
            b.recycle();
        }
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(170));
        g.setGradientCenter(0.62f, 0.5f);
        g.setColors(new int[]{(color & 0xFFFFFF) | 0x8C000000, (color & 0xFFFFFF) | 0x26000000, color & 0xFFFFFF});
        return g;
    }

    private void heroPrimary() {
        if (heroWatch != null) resume(heroWatch);
        else launchFeatured();
    }

    private void heroCleanFirst() {
        Runnable go;
        String pkg;
        if (heroWatch != null) {
            Apps.Continue w = heroWatch;
            go = () -> resume(w);
            pkg = w.pkg;
        } else if (featured != null) {
            go = this::launchFeatured;
            pkg = featured.pkg;
        } else {
            return;
        }
        if (clean(pkg)) afterClean = go;
        else go.run();
    }

    private void resume(Apps.Continue w) {
        if (w.session != null) {
            try {
                w.session.send();
                return;
            } catch (Exception ignored) {
                // The app closed its player: open the app instead.
            }
        }
        Intent[] steps = repo.resumeIntents(w, apps);
        if (steps.length == 0) return;
        try {
            startActivities(steps);
        } catch (Exception e) {
            open(steps[steps.length - 1]); // the app refused the two-step start: just open the link
        }
    }

    // A controller plugged in (or switched off) changes the billboard straight away.
    private final android.hardware.input.InputManager.InputDeviceListener padListener = new android.hardware.input.InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int id) {
            fillHero();
        }

        @Override
        public void onInputDeviceRemoved(int id) {
            fillHero();
        }

        @Override
        public void onInputDeviceChanged(int id) {
        }
    };

    private void loadContinue() {
        if (continueRow == null) return;
        List<Apps.App> snapshot = apps;
        bg.execute(() -> {
            List<Apps.Continue> items = repo.continueWatching(snapshot);
            ui.post(() -> fillContinue(items));
        });
    }

    private void fillContinue(List<Apps.Continue> all) {
        lastContinue = all;
        fillHero();
        List<Apps.Continue> items = all; // all of them: the billboard previews whichever card has focus
        continueRow.removeAllViews();
        if (items.isEmpty()) {
            TextView t = Ui.text(this, "Shows you start in YouTube, Prime Video and other apps will appear here.", 10, Ui.MUTED, Ui.BODY);
            t.setPadding(dp(4), dp(36), 0, 0);
            continueRow.addView(t);
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            Apps.Continue w = items.get(i);
            View card = continueCard(w);
            card.setNextFocusUpId(id(playBtn));
            if (i == 0) card.setNextFocusLeftId(id(card));
            if (i == items.size() - 1) card.setNextFocusRightId(id(card));
            continueRow.addView(card, tileLp(dp(191), dp(107), i == 0 ? 0 : dp(14)));
        }
        View first = continueRow.getChildAt(0);
        if (first != null && first.isFocusable()) {
            for (View v : new View[]{playBtn, cleanPlayBtn, controlBtns[0], controlBtns[1], controlBtns[2], controlBtns[3]}) v.setNextFocusDownId(id(first));
        }
    }

    private View continueCard(Apps.Continue w) {
        int radius = dp(13);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(new Soft(Soft.Kind.NEU_UP, radius, dp(5), Ui.TILE));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(art, radius); // plain card until the poster arrives; app logos never stand in for it
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));
        if (w.posterUri != null) {
            bg.execute(() -> {
                Bitmap b = repo.loadPoster(w.posterUri, dp(191));
                if (b != null) ui.post(() -> art.setImageBitmap(b));
            });
        }

        LinearLayout strip = column();
        strip.setPadding(dp(8), dp(6), dp(8), dp(7));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(Ui.STRIP);
        sbg.setCornerRadius(dp(9));
        sbg.setStroke(Math.max(1, dp(0.5f)), Ui.DARK ? 0x2EFFFFFF : 0xBFFFFFFF);
        strip.setBackground(sbg);
        TextView title = Ui.text(this, w.title, 9.5f, Ui.INK, Ui.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        strip.addView(title);
        if (w.meta != null && !w.meta.isEmpty()) {
            TextView meta = Ui.text(this, w.meta, 7.5f, Ui.INK_SOFT, Ui.BODY);
            meta.setSingleLine(true);
            strip.addView(meta, lp(-2, -2, 2));
        }
        if (w.progressPct >= 0) strip.addView(new ProgressBarView(this, w.progressPct), lp(-1, dp(2.5f), 4));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.setMargins(dp(5), 0, dp(5), dp(5));
        card.addView(strip, sp);

        card.setContentDescription(w.title);
        Ui.focusLift(card, radius, 1.06f);
        card.setOnClickListener(v -> resume(w));
        View.OnFocusChangeListener lift = card.getOnFocusChangeListener();
        card.setOnFocusChangeListener((v, has) -> {
            lift.onFocusChange(v, has);
            if (has) previewSoon(w);
        });
        return card;
    }

    private void fillGrid() {
        grid.removeAllViews();
        if ("Live TV".equals(tab)) {
            fillChannels();
            return;
        }
        boolean games = "Games".equals(tab);
        gridTitle.setText(games ? "Games" : "All apps  ·  hold OK on a faded app to show it on Home");
        java.util.Set<String> hidden = prefs.getStringSet("hidden", new java.util.HashSet<>());
        int n = 0;
        for (Apps.App a : apps) {
            if (games && !a.game) continue;
            View tile = bannerTile(a, dp(128), dp(72), dp(12));
            if (hidden.contains(a.pkg)) {
                tile.setAlpha(0.45f);
                tile.setOnLongClickListener(v -> {
                    setHidden(a, false);
                    return true;
                });
            }
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = dp(128);
            p.height = dp(72);
            p.setMargins(dp(7), dp(7), dp(7), dp(7));
            grid.addView(tile, p);
            n++;
        }
        if (n == 0) grid.addView(Ui.text(this, games ? "No games installed yet." : "No apps found.", 11, Ui.MUTED, Ui.BODY));
        closeGridEdges();
    }

    /** Keeps the remote inside the grid: edges stay put, Up from the top row goes to the open tab. */
    private void closeGridEdges() {
        int cols = grid.getColumnCount();
        List<View> tiles = new ArrayList<>();
        for (int i = 0; i < grid.getChildCount(); i++) if (grid.getChildAt(i).isFocusable()) tiles.add(grid.getChildAt(i));
        View tabView = null;
        for (int i = 0; i < tabsBar.getChildCount(); i++) if (tab.equals(tabsBar.getChildAt(i).getTag())) tabView = tabsBar.getChildAt(i);
        int lastRow = (tiles.size() - 1) / cols;
        for (int i = 0; i < tiles.size(); i++) {
            View t = tiles.get(i);
            int col = i % cols, rowIdx = i / cols;
            if (col == 0) t.setNextFocusLeftId(id(t));
            if (col == cols - 1 || i == tiles.size() - 1) t.setNextFocusRightId(id(t));
            if (rowIdx == lastRow) t.setNextFocusDownId(id(t));
            else if (i + cols >= tiles.size()) t.setNextFocusDownId(id(tiles.get(tiles.size() - 1))); // short last row
            if (rowIdx == 0 && tabView != null) t.setNextFocusUpId(id(tabView));
        }
        if (!tiles.isEmpty()) for (int i = 0; i < tabsBar.getChildCount(); i++) tabsBar.getChildAt(i).setNextFocusDownId(id(tiles.get(0)));
    }

    // ---------------------------------------------------------------- actions

    private void onTab(String t) {
        showTab(t);
    }

    private void fillChannels() {
        gridTitle.setText("Live TV");
        grid.addView(Ui.text(this, "Loading channels…", 11, Ui.MUTED, Ui.BODY));
        bg.execute(() -> {
            List<Apps.Channel> list = repo.channels(60);
            ui.post(() -> {
                if (!"Live TV".equals(tab)) return;
                grid.removeAllViews();
                Apps.App live = repo.find(apps, Apps.LIVE_TV_PKG);
                gridTitle.setText(list.isEmpty() ? "Live TV" : "Live TV  ·  " + list.size() + " channels");
                if (live != null) addGridTile(bannerTile(live, dp(128), dp(72), dp(12)));
                for (Apps.Channel ch : list) addGridTile(channelTile(ch));
                if (list.isEmpty()) grid.addView(Ui.text(this, live == null
                        ? "No live channels found on this TV." : "Open Xiaomi TV+ once to load its channels here.", 11, Ui.MUTED, Ui.BODY));
                closeGridEdges();
                if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
            });
        });
    }

    private void addGridTile(View tile) {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = dp(128);
        p.height = dp(72);
        p.setMargins(dp(7), dp(7), dp(7), dp(7));
        grid.addView(tile, p);
    }

    private View channelTile(Apps.Channel ch) {
        int radius = dp(12);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(new Soft(Soft.Kind.NEU_UP, radius, dp(5), Ui.DARK ? Ui.TILE : 0xFFF4F7FA));
        ImageView logo = new ImageView(this);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setPadding(dp(18), dp(8), dp(18), dp(22));
        tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        TextView name = Ui.text(this, (ch.number.isEmpty() ? "" : ch.number + "  ") + ch.name, 8, Ui.INK_SOFT, Ui.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(dp(6), 0, dp(6), dp(6));
        tile.addView(name, np);
        bg.execute(() -> {
            Bitmap b = repo.channelLogo(ch.id);
            if (b != null) ui.post(() -> logo.setImageBitmap(b));
        });
        tile.setContentDescription(ch.name);
        Ui.focusLift(tile, radius, 1.08f);
        tile.setOnClickListener(v -> open(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUri(ch.id))));
        return tile;
    }

    private void showTab(String t) {
        tab = t;
        boolean home = "Home".equals(t);
        homeContent.setVisibility(home ? View.VISIBLE : View.GONE);
        gridPanel.setVisibility(home ? View.GONE : View.VISIBLE);
        if (!home) {
            fillGrid();
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        } else if (playBtn != null) {
            for (int i = 0; i < tabsBar.getChildCount(); i++) tabsBar.getChildAt(i).setNextFocusDownId(id(playBtn));
        }
        styleTabs(null);
    }

    private void styleTabs(String focusedTab) {
        for (int i = 0; i < tabsBar.getChildCount(); i++) {
            TextView b = (TextView) tabsBar.getChildAt(i);
            String t = (String) b.getTag();
            boolean active = t.equals(tab);
            boolean focused = t.equals(focusedTab) || b.hasFocus();
            if (active || focused) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(active ? Ui.CHIP : (Ui.DARK ? 0x14FFFFFF : 0x80FFFFFF));
                g.setCornerRadius(dp(16));
                if (focused) g.setStroke(dp(2), Ui.ACCENT);
                b.setBackground(g);
            } else {
                b.setBackground(null);
            }
            b.setTextColor(active ? Ui.INK : Ui.INK_SOFT);
        }
    }

    private void launchFeatured() {
        if (featured == null) {
            open(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=games&c=apps")));
            return;
        }
        open(repo.launchIntent(featured));
    }

    /** Asks Couchglass Care to clean. Returns false if Couchglass Care isn't installed. */
    private boolean clean(String thenLaunch) {
        if (cleaning) return true;
        try {
            Intent i = new Intent("app.couchglass.care.action.CLEAN")
                    .setComponent(new ComponentName(TVCARE_PKG, TVCARE_SERVICE))
                    .putExtra("reply_to", getPackageName());
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Install Couchglass Care to clean memory from here.", Toast.LENGTH_LONG).show();
            return false;
        }
        cleaning = true;
        afterClean = null;
        cleanBtn.setText("Cleaning…");
        if (thenLaunch != null) cleanPlayBtn.setText("Cleaning…");
        ui.postDelayed(cleanTimeout, 70000);
        ui.postDelayed(allowHint, 4000);
        return true;
    }

    private final Runnable cleanTimeout = () -> onCleaned(0, 0, "Couchglass Care didn't answer");
    private final Runnable allowHint = () -> {
        if (cleaning) memSub.setText("If the TV asks to allow debugging, choose OK");
    };

    private void onCleaned(int closed, long freedKb, String error) {
        if (!cleaning) return;
        cleaning = false;
        ui.removeCallbacks(cleanTimeout);
        ui.removeCallbacks(allowHint);
        cleanBtn.setText("Clean now");
        cleanPlayBtn.setText("Clean memory first");
        if (error != null) {
            memSub.setText(error.contains("allow") || error.contains("Waiting")
                    ? "Open Couchglass Care once and allow debugging" : "Couldn't clean: " + error);
        } else {
            memSub.setText(closed == 0 ? "Already clean" : "Closed " + closed + " apps · freed " + (freedKb / 1024) + " MB");
        }
        updateMemory();
        Runnable next = afterClean;
        afterClean = null;
        if (next != null) next.run();
    }

    private void cycleSleep() {
        long end = prefs.getLong("sleep_end", 0);
        int current = end > System.currentTimeMillis() ? prefs.getInt("sleep_step", 0) : 0;
        int next = (current + 1) % SLEEP_STEPS.length;
        int minutes = SLEEP_STEPS[next];
        try {
            startForegroundService(new Intent("app.couchglass.care.action.SLEEP_TIMER")
                    .setComponent(new ComponentName(TVCARE_PKG, TVCARE_SERVICE))
                    .putExtra("minutes", minutes));
        } catch (Exception e) {
            Toast.makeText(this, "Install Couchglass Care to use the sleep timer.", Toast.LENGTH_LONG).show();
            return;
        }
        prefs.edit().putInt("sleep_step", next)
                .putLong("sleep_end", minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L).apply();
        sleepValue.setText(sleepLabel());
    }

    private String sleepLabel() {
        long end = prefs == null ? 0 : prefs.getLong("sleep_end", 0);
        if (end <= System.currentTimeMillis()) return "Off";
        return "Till " + DateFormat.getTimeFormat(this).format(new Date(end));
    }

    private void powerPressed() {
        if (!powerArmed) {
            powerArmed = true;
            powerValue.setText("Press again");
            ui.postDelayed(this::disarmPower, 4000);
            return;
        }
        disarmPower();
        try {
            startForegroundService(new Intent("app.couchglass.care.action.POWER_OFF")
                    .setComponent(new ComponentName(TVCARE_PKG, TVCARE_SERVICE)));
            powerValue.setText("Bye…");
        } catch (Exception e) {
            Toast.makeText(this, "Hold the remote's power button and choose Power off.", Toast.LENGTH_LONG).show();
        }
    }

    private void disarmPower() {
        powerArmed = false;
        if (powerValue != null) powerValue.setText("Shut down");
    }

    private void openPicture() {
        Intent qs = getPackageManager().getLaunchIntentForPackage("com.xiaomi.tvqs.overseas");
        open(qs != null ? qs : new Intent(Settings.ACTION_SETTINGS));
    }

    private void toggleInputs() {
        if (inputMenu.getVisibility() == View.VISIBLE) {
            inputMenu.setVisibility(View.GONE);
            return;
        }
        inputMenu.removeAllViews();
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> list = tim == null ? new ArrayList<>() : tim.getTvInputList();
        int n = 0;
        for (TvInputInfo info : list) {
            if (!info.isPassthroughInput() || info.isHidden(this)) continue;
            CharSequence label = info.loadCustomLabel(this);
            if (label == null) label = info.loadLabel(this);
            String name = String.valueOf(label);
            TextView b = neuButton(name);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setOnClickListener(v -> {
                inputMenu.setVisibility(View.GONE);
                inputBtnLabel.setText(name);
                open(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId())));
            });
            inputMenu.addView(b, lp(-1, dp(30), n++ == 0 ? 0 : 7));
        }
        if (n == 0) inputMenu.addView(Ui.text(this, "No HDMI or AV inputs found.", 10, Ui.MUTED, Ui.BODY));
        showPopup(220, Gravity.END | Gravity.TOP, 70, 36 + 82 + 41);
    }

    /** Places the shared popup and moves focus into it. */
    private void showPopup(int widthDp, int gravity, int topDp, int sideDp) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(widthDp), -2, gravity);
        p.topMargin = dp(topDp);
        p.rightMargin = dp(sideDp);
        inputMenu.setLayoutParams(p);
        inputMenu.setVisibility(View.VISIBLE);
        for (int i = 0; i < inputMenu.getChildCount(); i++) {
            if (inputMenu.getChildAt(i).isFocusable()) {
                inputMenu.getChildAt(i).requestFocus();
                break;
            }
        }
    }

    private void hidePopup() {
        inputMenu.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- cast

    private void toggleCast() {
        if (inputMenu.getVisibility() == View.VISIBLE) {
            hidePopup();
            return;
        }
        inputMenu.removeAllViews();
        String name = Settings.Global.getString(getContentResolver(), "device_name");
        if (name == null || name.isEmpty()) name = "this TV";
        inputMenu.addView(Ui.text(this, "Cast to this TV", 13, Ui.INK, Ui.DISPLAY));

        LinearLayout gc = column();
        gc.setPadding(dp(12), dp(9), dp(12), dp(10));
        gc.setBackground(new Soft(Soft.Kind.NEU_IN, dp(12), dp(5), Ui.GROUND));
        gc.addView(Ui.text(this, "Google Cast · ready", 10, Ui.ACCENT, Ui.BOLD));
        TextView how = Ui.text(this, "In YouTube, Netflix, Prime Video, Chrome or Google Photos, tap the Cast icon and choose \u201C" + name + "\u201D.", 9, Ui.INK_SOFT, Ui.BODY);
        how.setLineSpacing(0, 1.15f);
        gc.addView(how, lp(-1, -2, 3));
        inputMenu.addView(gc, lp(-1, -2, 8));

        TextView mirror = neuButton("Mirror a phone or laptop");
        mirror.setOnClickListener(v -> {
            hidePopup();
            open(new Intent().setComponent(new ComponentName("com.xiaomi.mitv.smartshare",
                    "com.xiaomi.mitv.smartshare.wifidisplay.WfdActivity")));
        });
        inputMenu.addView(mirror, lp(-1, dp(30), 10));
        TextView tip = Ui.text(this, "Android: Smart View / Screen cast · Windows: press Win + K", 8, Ui.MUTED, Ui.BODY);
        inputMenu.addView(tip, lp(-1, -2, 4));

        TextView usb = neuButton("Photos & videos from USB");
        usb.setOnClickListener(v -> {
            hidePopup();
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("com.xiaomi.mitv.mediaexplorer");
            if (i != null) open(i);
        });
        inputMenu.addView(usb, lp(-1, dp(30), 10));
        showPopup(330, Gravity.END | Gravity.TOP, 70, 36 + 82);
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that.", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- live bits

    private void updateClock() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "H:mm" : "h:mm", now));
        date.setText((h24 ? "" : DateFormat.format("a", now) + " · ") + DateFormat.format("EEE, d MMM", now));
        if (sleepValue != null) sleepValue.setText(sleepLabel());
        if (weather != null && weather.stale()) refreshWeather();
        if (weather != null && !resolvedTheme().equals(Ui.current)) recreate(); // Auto: day/night switch
    }

    private void updateMemory() {
        long[] m = readMemory();
        long total = m[0], free = m[1];
        if (total <= 0) return;
        int pct = (int) (free * 100 / total);
        ring.setPercent(pct);
        memBig.setText((free / 1024) + " MB free");
        if (!cleaning && (memSub.getText().length() == 0 || memSub.getText().toString().startsWith("of "))) {
            memSub.setText("of " + String.format(java.util.Locale.US, "%.2f GB", total / 1048576.0));
        }
    }

    /** Same estimate as Couchglass Care: this TV's kernel reports a broken MemAvailable. */
    static long[] readMemory() {
        long total = 0, free = 0, inactiveFile = 0, activeFile = 0, srecl = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 2) continue;
                long v = Long.parseLong(p[1]);
                switch (p[0]) {
                    case "MemTotal:": total = v; break;
                    case "MemFree:": free = v; break;
                    case "Inactive(file):": inactiveFile = v; break;
                    case "Active(file):": activeFile = v; break;
                    case "SReclaimable:": srecl = v; break;
                    default: break;
                }
            }
        } catch (Exception ignored) {
        }
        long est = Math.min(total, free + inactiveFile + activeFile / 2 + srecl);
        return new long[]{total, est};
    }

    // ---------------------------------------------------------------- small builders

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setClipChildren(false);
        l.setClipToPadding(false);
        return l;
    }

    private HorizontalScrollView scroller(LinearLayout content) {
        HorizontalScrollView s = new HorizontalScrollView(this);
        s.setHorizontalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setClipToPadding(false);
        s.setPadding(0, dp(12), 0, dp(12));
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.addView(content, new FrameLayout.LayoutParams(-2, -1));
        return s;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private LinearLayout.LayoutParams tileLp(int w, int h, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = left;
        return p;
    }

    private LinearLayout.LayoutParams circleLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(32), dp(32));
        p.leftMargin = dp(9);
        return p;
    }

    private View circleButton(int icon, String label, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        b.setBackground(new Soft(Soft.Kind.NEU_UP, dp(16), dp(4), Ui.GROUND));
        View i = Icons.view(this, icon, dp(14), Ui.INK_SOFT);
        b.addView(i, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
        b.setContentDescription(label);
        Ui.focusGrow(b, dp(16), 1.06f);
        b.setOnClickListener(click);
        return b;
    }

    private TextView pillButton(String label, boolean primary) {
        // Compact, so the billboard's artwork and title stay the heroes.
        TextView b = Ui.text(this, label, primary ? 10 : 9, primary ? Color.WHITE : Ui.INK, Ui.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(primary ? 15 : 11), 0, dp(primary ? 15 : 11), 0);
        if (primary) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(Ui.ACCENT);
            g.setCornerRadius(dp(13.5f));
            b.setBackground(g);
            b.setElevation(dp(6));
        } else {
            b.setBackground(new Soft(Soft.Kind.GLASS, dp(13.5f), dp(4), Ui.CHIP));
        }
        Ui.focusGrow(b, dp(13.5f), 1.06f, primary ? Ui.INK : Ui.ACCENT);
        return b;
    }

    private TextView neuButton(String label) {
        TextView b = Ui.text(this, label, 9, Ui.INK, Ui.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(new Soft(Soft.Kind.NEU_UP, dp(15), dp(4.5f), Ui.GROUND));
        Ui.focusGrow(b, dp(15), 1.06f);
        return b;
    }

    private static void roundClip(View v, int radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    // ---------------------------------------------------------------- theme, weather, settings

    private String themeChoice() {
        return prefs.getString("theme", "Auto");
    }

    private String resolvedTheme() {
        Weather.Now w = weather.cached();
        return Ui.resolve(themeChoice(), w == null ? 0 : w.sunrise, w == null ? 0 : w.sunset);
    }

    private void applyTheme() {
        Ui.apply(resolvedTheme());
    }

    private View buildWeatherPill() {
        LinearLayout p = row();
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(7), 0, dp(11), 0);
        p.setBackground(new Soft(Soft.Kind.GLASS, dp(16), dp(7), Ui.GLASS));
        weatherIcon = new WeatherIconView(this);
        p.addView(weatherIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout t = column();
        t.setPadding(dp(6), 0, 0, 0);
        weatherTemp = Ui.text(this, "--°", 13, Ui.INK, Ui.DISPLAY);
        weatherSub = Ui.text(this, "Weather", 7.5f, Ui.INK_SOFT, Ui.BODY);
        weatherSub.setSingleLine(true);
        t.addView(weatherTemp);
        t.addView(weatherSub, lp(-2, -2, 1));
        p.addView(t);
        p.setVisibility(View.INVISIBLE);
        return p;
    }

    private void showWeather(Weather.Now w) {
        if (w == null || weatherPill == null) return;
        weatherIcon.set(Weather.kind(w.code), w.day);
        weatherTemp.setText(w.temp + "°");
        weatherSub.setText(w.label() + " · H " + w.high + "° L " + w.low + "°");
        weatherPill.setContentDescription("Weather in " + w.city + ": " + w.temp + " degrees, " + w.label());
        weatherPill.setVisibility(View.VISIBLE);
    }

    private void refreshWeather() {
        bg.execute(() -> {
            Weather.Now w = weather.refresh();
            if (w != null) ui.post(() -> {
                showWeather(w);
                if (!resolvedTheme().equals(Ui.current)) recreate();
            });
        });
    }

    /** Couchglass's own settings: theme, app row and a way into Android settings. */
    private void toggleSettings() {
        if (inputMenu.getVisibility() == View.VISIBLE) {
            hidePopup();
            return;
        }
        inputMenu.removeAllViews();
        inputMenu.addView(Ui.text(this, "Couchglass", 13, Ui.INK, Ui.DISPLAY));
        String t = themeChoice();
        String shown = "Auto".equals(t) ? "Auto (" + Ui.current + " now)" : t;
        inputMenu.addView(settingRow("Theme", shown, () -> {
            int i = java.util.Arrays.asList(Ui.THEMES).indexOf(t);
            prefs.edit().putString("theme", Ui.THEMES[(i + 1) % Ui.THEMES.length]).apply();
            prefs.edit().putBoolean("reopen_settings", true).apply();
            recreate();
        }), lp(-1, dp(30), 10));
        Weather.Now w = weather.cached();
        if (w != null && !w.city.isEmpty()) {
            inputMenu.addView(Ui.text(this, "Weather for " + w.city + ", from your internet connection", 8, Ui.MUTED, Ui.BODY), lp(-1, -2, 8));
        }
        TextView android = neuButton("Android settings");
        android.setOnClickListener(v -> {
            hidePopup();
            open(new Intent(Settings.ACTION_SETTINGS));
        });
        inputMenu.addView(android, lp(-1, dp(30), 10));
        showPopup(280, Gravity.END | Gravity.TOP, 70, 36);
    }

    private View settingRow(String label, String value, Runnable onPress) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(12), 0);
        r.setBackground(new Soft(Soft.Kind.NEU_UP, dp(15), dp(4.5f), Ui.GROUND));
        r.addView(Ui.text(this, label, 9, Ui.MUTED, Ui.BODY), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(Ui.text(this, value, 9.5f, Ui.INK, Ui.BOLD));
        Ui.focusGrow(r, dp(15), 1.04f);
        r.setOnClickListener(v -> onPress.run());
        return r;
    }

    // ---------------------------------------------------------------- remote focus

    private int id(View v) {
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        return v.getId();
    }

    /** Moves the remote's arrows between neighbours you'd expect, instead of the nearest thing on screen. */
    private void link(View v, View left, View up, View right, View down) {
        if (left != null) v.setNextFocusLeftId(id(left));
        if (up != null) v.setNextFocusUpId(id(up));
        if (right != null) v.setNextFocusRightId(id(right));
        if (down != null) v.setNextFocusDownId(id(down));
    }

    private void wireFocus() {
        View homeTab = tabsBar.getChildAt(0);
        View sleep = controlBtns[0], picture = controlBtns[1], sound = controlBtns[2], power = controlBtns[3];
        for (int i = 0; i < tabsBar.getChildCount(); i++) tabsBar.getChildAt(i).setNextFocusDownId(id(playBtn));
        link(homeTab, homeTab, homeTab, null, null);
        for (View v : new View[]{searchBtn, inputBtn, castBtn, wifiBtn, settingsBtn}) link(v, null, v, null, cleanBtn);
        link(settingsBtn, null, null, settingsBtn, null);
        link(playBtn, playBtn, homeTab, cleanPlayBtn, null);
        link(cleanPlayBtn, playBtn, homeTab, sleep, null);
        link(cleanBtn, cleanPlayBtn, inputBtn, cleanBtn, power);
        link(sleep, cleanPlayBtn, cleanBtn, picture, null);
        link(picture, sleep, cleanBtn, sound, null);
        link(sound, picture, cleanBtn, power, null);
        link(power, sound, cleanBtn, power, null);
    }

    /** While a popup is open the arrows stay inside it; Back closes it. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (inputMenu != null && inputMenu.getVisibility() == View.VISIBLE && e.getAction() == KeyEvent.ACTION_DOWN) {
            int dir;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: dir = View.FOCUS_UP; break;
                case KeyEvent.KEYCODE_DPAD_DOWN: dir = View.FOCUS_DOWN; break;
                case KeyEvent.KEYCODE_DPAD_LEFT: dir = View.FOCUS_LEFT; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: dir = View.FOCUS_RIGHT; break;
                default: dir = 0;
            }
            if (dir != 0) {
                View cur = getCurrentFocus();
                boolean inside = false;
                for (View p = cur; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                    if (p == inputMenu) { inside = true; break; }
                }
                View next = inside ? android.view.FocusFinder.getInstance().findNextFocus(inputMenu, cur, dir) : null;
                if (next != null) next.requestFocus();
                else if (!inside) inputMenu.requestFocus(View.FOCUS_DOWN);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- tiny custom views

    /** Four rounded squares: the All apps tile's glyph. */
    static class GridGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridGlyph(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(c, 1.4f));
            p.setColor(Ui.INK_SOFT);
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 24f, r = 1.5f * k;
            for (float[] q : new float[][]{{4, 4}, {14, 4}, {4, 14}, {14, 14}}) {
                c.drawRoundRect(q[0] * k, q[1] * k, (q[0] + 6) * k, (q[1] + 6) * k, r, r, p);
            }
        }
    }

    /** Weather glyphs: sun or moon, partly cloudy, cloud, rain, storm, fog/snow. */
    static class WeatherIconView extends View {
        private int kind = 1;
        private boolean day = true;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);

        WeatherIconView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(int kind, boolean day) {
            this.kind = kind;
            this.day = day;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = getWidth() / 40f;
            c.save();
            c.scale(k, k);
            line.setStrokeWidth(2f);
            int sun = 0xFFF2B53A, moon = 0xFFE6E2C8;
            boolean body = kind <= 1;
            if (body) {
                fill.setColor(day ? sun : moon);
                float cx = kind == 0 ? 20 : 15, cy = kind == 0 ? 20 : 15, r = kind == 0 ? 9 : 7;
                c.drawCircle(cx, cy, r, fill);
                if (day) {
                    line.setColor(sun);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        c.drawLine(cx + (float) Math.cos(a) * (r + 3), cy + (float) Math.sin(a) * (r + 3),
                                cx + (float) Math.cos(a) * (r + 6), cy + (float) Math.sin(a) * (r + 6), line);
                    }
                } else {
                    fill.setColor(Ui.GROUND);
                    c.drawCircle(cx + r * 0.55f, cy - r * 0.45f, r * 0.85f, fill);
                }
            }
            if (kind >= 1) {
                fill.setColor(Ui.CLOUD);
                float ox = kind == 1 ? 4 : 0, oy = kind == 1 ? 4 : 0;
                c.drawCircle(15 + ox, 22 + oy, 6, fill);
                c.drawCircle(23 + ox, 19 + oy, 8, fill);
                c.drawRoundRect(9 + ox, 21 + oy, 33 + ox, 30 + oy, 4.5f, 4.5f, fill);
            }
            if (kind == 3) {
                line.setColor(Ui.ACCENT);
                for (float x : new float[]{14, 21, 28}) c.drawLine(x, 33, x - 2, 38, line);
            } else if (kind == 4) {
                fill.setColor(0xFFF2B53A);
                android.graphics.Path bolt = new android.graphics.Path();
                bolt.moveTo(21, 30); bolt.lineTo(16, 37); bolt.lineTo(20, 37); bolt.lineTo(18, 40); bolt.lineTo(25, 33); bolt.lineTo(21, 33); bolt.close();
                c.drawPath(bolt, fill);
            } else if (kind == 5) {
                line.setColor(Ui.INK_SOFT);
                c.drawLine(10, 34, 30, 34, line);
                c.drawLine(14, 38, 26, 38, line);
            }
            c.restore();
        }
    }

    /** The memory ring in the Couchglass Care widget. */
    static class RingView extends View {
        private int pct;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);

        RingView(Context c) {
            super(c);
            float sw = Ui.dp(c, 5.5f);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(sw);
            track.setColor(Ui.TRACK);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(sw);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(Ui.ACCENT);
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(Ui.INK);
            label.setTextSize(Ui.dp(c, 10));
            label.setFakeBoldText(true);
        }

        void setPercent(int p) {
            pct = p;
            arc.setColor(p < 12 ? Ui.DANGER : Ui.ACCENT);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), pad = track.getStrokeWidth() / 2 + 1;
            RectF r = new RectF(pad, pad, s - pad, s - pad);
            c.drawOval(r, track);
            c.drawArc(r, -90, 360f * pct / 100f, false, arc);
            c.drawText(pct + "%", s / 2, s / 2 - (label.descent() + label.ascent()) / 2, label);
        }
    }

    /** Thin progress bar on Continue watching cards. */
    static class ProgressBarView extends View {
        private final int pct;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), fg = new Paint(Paint.ANTI_ALIAS_FLAG);

        ProgressBarView(Context c, int pct) {
            super(c);
            this.pct = pct;
            bg.setColor(Ui.DARK ? 0x2EFFFFFF : 0x241E2A3A);
            fg.setColor(Ui.ACCENT);
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2;
            c.drawRoundRect(0, 0, getWidth(), h, r, r, bg);
            c.drawRoundRect(0, 0, getWidth() * pct / 100f, h, r, r, fg);
        }
    }
}
