package app.couchglass.care;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads memory, lists running apps and closes the ones that are safe to close. */
public class Cleaner {
    public static final String PREFS = "couchglass_care";
    private static final String KEY_PROTECTED = "protected";
    private static final String KEY_LOG = "log";

    /** Never touched: the system, Google services, TV hardware services and keyboards. */
    private static final String[] ALWAYS_KEEP_PREFIXES = {
            "com.android.", "android.", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.inputmethod", "com.google.android.ext.", "com.google.android.tvlauncher",
            "com.google.android.katniss", "com.google.android.apps.mediashell",
            "com.mediatek.", "com.mstar.", "mitv.service", "xiaomi.tvservice",
            "com.xiaomi.mitv.tvmanager", "com.mitv.tvlock", "app.couchglass.care"
    };

    public enum State { ON_SCREEN, PLAYING, PROTECTED, SYSTEM, WILL_CLOSE }

    public static class AppEntry {
        public String pkg;
        public String label;
        public int pssKb;
        public boolean userApp;
        public State state;
    }

    public static class MemInfo {
        public long totalKb, freeKb, swapTotalKb, swapFreeKb;
    }

    public static class Result {
        public int closed;
        public long freedKb;
        public List<String> closedLabels = new ArrayList<>();
    }

    private final Context context;
    private final AdbClient adb;
    private final PackageManager pm;

    public Cleaner(Context context, AdbClient adb) {
        this.context = context.getApplicationContext();
        this.adb = adb;
        this.pm = context.getPackageManager();
    }

    /**
     * This TV's kernel reports a broken MemAvailable (hundreds of GB), so free
     * memory is estimated from the fields that are reliable: free pages, file
     * cache the kernel can drop, and reclaimable slab.
     */
    public static MemInfo readMemory() {
        MemInfo m = new MemInfo();
        Map<String, Long> v = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String num = line.substring(colon + 1).replace("kB", "").trim();
                try {
                    v.put(line.substring(0, colon), Long.parseLong(num));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        m.totalKb = get(v, "MemTotal");
        long estimate = get(v, "MemFree") + get(v, "Inactive(file)")
                + get(v, "Active(file)") / 2 + get(v, "SReclaimable");
        m.freeKb = Math.max(0, Math.min(estimate, m.totalKb));
        m.swapTotalKb = get(v, "SwapTotal");
        m.swapFreeKb = get(v, "SwapFree");
        return m;
    }

    private static long get(Map<String, Long> v, String k) {
        Long x = v.get(k);
        return x == null ? 0 : x;
    }

    // ---- protected list ----

    public Set<String> getProtected() {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(p.getStringSet(KEY_PROTECTED, Collections.<String>emptySet()));
    }

    public void setProtected(String pkg, boolean on) {
        Set<String> s = getProtected();
        if (on) s.add(pkg); else s.remove(pkg);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_PROTECTED, s).apply();
    }

    // ---- running apps ----

    public List<AppEntry> listRunning() throws IOException {
        String meminfo = adb.shell("dumpsys meminfo");
        String foreground = foregroundPackage();
        Set<String> playing = playingPackages();
        Set<String> keep = getProtected();
        String home = homePackage();
        String ime = currentIme();

        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        Pattern row = Pattern.compile("^\\s*([\\d,]+)K: ([^\\s(]+) \\(pid");
        boolean inSection = false;
        for (String line : meminfo.split("\n")) {
            if (line.startsWith("Total PSS by process")) { inSection = true; continue; }
            if (inSection && line.trim().isEmpty()) break;
            if (!inSection) continue;
            Matcher mt = row.matcher(line);
            if (!mt.find()) continue;
            String process = mt.group(2);
            String pkg = process.contains(":") ? process.substring(0, process.indexOf(':')) : process;
            ApplicationInfo ai = appInfo(pkg);
            if (ai == null || ai.uid < 10000) continue; // native daemons and system_server
            int kb = Integer.parseInt(mt.group(1).replace(",", ""));
            AppEntry e = byPkg.get(pkg);
            if (e == null) {
                e = new AppEntry();
                e.pkg = pkg;
                e.label = String.valueOf(pm.getApplicationLabel(ai));
                e.userApp = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                byPkg.put(pkg, e);
            }
            e.pssKb += kb;
        }

        List<AppEntry> list = new ArrayList<>(byPkg.values());
        for (AppEntry e : list) {
            if (e.pkg.equals(foreground)) e.state = State.ON_SCREEN;
            else if (playing.contains(e.pkg)) e.state = State.PLAYING;
            else if (keep.contains(e.pkg)) e.state = State.PROTECTED;
            else if (isAlwaysKept(e.pkg) || e.pkg.equals(home) || e.pkg.equals(ime)) e.state = State.SYSTEM;
            else e.state = State.WILL_CLOSE;
        }
        Collections.sort(list, (a, b) -> b.pssKb - a.pssKb);
        return list;
    }

    /**
     * Closes background apps. Installed apps are force-stopped; preinstalled
     * ones get a gentle kill (Android only kills them if it's safe), then any
     * leftover cached processes are dropped.
     */
    public Result clean() throws IOException {
        long before = readMemory().freeKb;
        Result r = new Result();
        StringBuilder cmd = new StringBuilder();
        for (AppEntry e : listRunning()) {
            if (e.state != State.WILL_CLOSE) continue;
            cmd.append(e.userApp ? "am force-stop " : "am kill ").append(e.pkg).append("; ");
            r.closed++;
            r.closedLabels.add(e.label);
        }
        cmd.append("am kill-all");
        adb.shell(cmd.toString());
        try {
            Thread.sleep(1500); // let the kernel account the freed pages
        } catch (InterruptedException ignored) {
        }
        r.freedKb = Math.max(0, readMemory().freeKb - before);
        return r;
    }

    public void powerOff() throws IOException {
        adb.shell("svc power shutdown");
    }

    public void restart() throws IOException {
        adb.shell("svc power reboot");
    }

    // ---- history ----

    public void logClean(String reason, Result r) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String entry = System.currentTimeMillis() + "|" + reason + "|" + r.closed + "|" + r.freedKb;
        List<String> lines = new ArrayList<>();
        lines.add(entry);
        String old = p.getString(KEY_LOG, "");
        for (String l : old.split("\n")) {
            if (!l.isEmpty() && lines.size() < 15) lines.add(l);
        }
        p.edit().putString(KEY_LOG, android.text.TextUtils.join("\n", lines)).apply();
    }

    public List<String[]> getLog() {
        String s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LOG, "");
        List<String[]> out = new ArrayList<>();
        for (String l : s.split("\n")) {
            String[] parts = l.split("\\|");
            if (parts.length == 4) out.add(parts);
        }
        return out;
    }

    // ---- helpers ----

    /** Preinstalled but safe to close when in the background. */
    private static final String[] CLOSABLE_ANYWAY = { "com.android.vending", "com.android.chrome" };

    private boolean isAlwaysKept(String pkg) {
        for (String p : CLOSABLE_ANYWAY) if (pkg.equals(p)) return false;
        for (String p : ALWAYS_KEEP_PREFIXES) if (pkg.startsWith(p)) return true;
        return false;
    }

    private ApplicationInfo appInfo(String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String foregroundPackage() throws IOException {
        String out = adb.shell("dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'");
        Matcher m = Pattern.compile("u0 ([\\w.]+)/").matcher(out);
        return m.find() ? m.group(1) : "";
    }

    /** Apps with a media session that is playing (state 3), e.g. music in the background. */
    private Set<String> playingPackages() throws IOException {
        String out = adb.shell("dumpsys media_session");
        Set<String> s = new HashSet<>();
        String pkg = null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package=")) pkg = t.substring(8).trim();
            else if (pkg != null && t.startsWith("state=PlaybackState {state=3")) s.add(pkg);
        }
        return s;
    }

    private String homePackage() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
    }

    private String currentIme() {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return id == null ? "" : id.split("/")[0];
    }
}
