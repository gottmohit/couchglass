package app.couchglass.home;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.view.InputDevice;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the home screen shows that comes from the TV itself. */
public class Apps {
    public static class App {
        public String pkg, label;
        public ComponentName component;
        public boolean game;
        public Drawable banner;
    }

    public static class Continue {
        public String pkg, title, meta;
        public int progressPct; // -1 = no bar
        public Intent intent;
        public String posterUri;
        public Drawable fallbackArt;
        public long when; // last watched (ms); 0 = unknown
        public String episode, seasonEpisode, left; // "The Battlefront of Alabasta!", "S4 · E105", "10 min left"
        public android.app.PendingIntent session; // the app's own way back to its player, when known
    }

    /** Your usual order; anything else follows alphabetically. */
    private static final String[] PREFERRED = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "in.startv.hotstar",
            "com.amazon.amazonvideo.livingroom", "com.graymatrix.did", "com.sonyliv",
            "com.crunchyroll.crunchyroid", "com.spotify.tv.android", "com.mitv.tvhome.mitvplus"
    };
    /** Never shown as tiles: this launcher, setup and system plumbing. */
    private static final Set<String> HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "app.couchglass.home", "com.mitv.tvhome.atv", "com.xiaomi.mitv.manualhelp",
            "com.mstar.android.tv.disclaimercustomization", "com.android.tv.settings"));
    public static final String LIVE_TV_PKG = "com.mitv.tvhome.mitvplus";
    public static final String FEATURED_GAME = "com.vectorunit.purple.googleplay";

    private final Context context;
    private final PackageManager pm;

    public Apps(Context c) {
        context = c.getApplicationContext();
        pm = c.getPackageManager();
    }

    public List<App> all() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (HIDDEN.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            a.game = isGame(ai.applicationInfo);
            a.banner = banner(ai);
            byPkg.put(a.pkg, a);
        }
        List<App> out = new ArrayList<>();
        for (String p : PREFERRED) {
            App a = byPkg.remove(p);
            if (a != null) out.add(a);
        }
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        out.addAll(rest);
        return out;
    }

    private static boolean isGame(ApplicationInfo ai) {
        //noinspection deprecation
        return ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    /** The app's own 16:9 TV banner; if it has none, its icon centred on a soft tile. */
    private Drawable banner(ActivityInfo ai) {
        Drawable d = ai.loadBanner(pm);
        if (d == null) d = ai.applicationInfo.loadBanner(pm);
        if (d != null) return d;
        Drawable icon = ai.loadIcon(pm);
        int w = 320, h = 180, s = 96;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFFEFF3F7);
        icon.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
        icon.draw(c);
        return new android.graphics.drawable.BitmapDrawable(context.getResources(), b);
    }

    public Intent launchIntent(App a) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i == null) i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    public App find(List<App> apps, String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** The featured game: Beach Buggy Racing if installed, else the most recently played game. */
    public App featuredGame(List<App> apps) {
        App bbr = find(apps, FEATURED_GAME);
        if (bbr != null) return bbr;
        Map<String, Long> last = lastUsed(30);
        App best = null;
        for (App a : apps) {
            if (!a.game) continue;
            if (best == null || get(last, a.pkg) > get(last, best.pkg)) best = a;
        }
        return best;
    }

    public static int gamepadsConnected() {
        int n = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual() || d.getControllerNumber() == 0
                    || d.getName().toLowerCase(java.util.Locale.ROOT).contains("virtual")) continue;
            int src = d.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) n++;
        }
        return n;
    }

    // ---- Continue watching ----

    /**
     * Android TV's "Watch Next" list, which streaming apps fill in as you watch.
     * Falls back to the video apps you opened most recently when no app shares it.
     */
    public List<Continue> continueWatching(List<App> apps) {
        List<Continue> out = new ArrayList<>();
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
        };
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            while (c != null && c.moveToNext() && out.size() < 10) {
                Continue w = new Continue();
                w.pkg = c.getString(0);
                w.title = nz(c.getString(1));
                String ep = nz(c.getString(2));
                if (w.title.isEmpty()) w.title = ep;
                long pos = c.getLong(5), dur = c.getLong(6);
                StringBuilder meta = new StringBuilder();
                if (c.getString(3) != null) meta.append('S').append(c.getString(3)).append(" · ");
                if (c.getString(4) != null) meta.append('E').append(c.getString(4)).append(" · ");
                if (dur > 0 && pos > 0) meta.append(minutes(dur - pos)).append(" left");
                else if (!ep.isEmpty() && !ep.equals(w.title)) meta.append(ep);
                w.meta = meta.toString().replaceAll(" · $", "");
                StringBuilder sxe = new StringBuilder();
                if (c.getString(3) != null) sxe.append('S').append(c.getString(3)).append(" · ");
                if (c.getString(4) != null) sxe.append('E').append(c.getString(4));
                w.seasonEpisode = sxe.toString().replaceAll(" · $", "");
                if (!ep.isEmpty() && !ep.equals(w.title)) w.episode = ep;
                if (dur > 0 && pos > 0) w.left = minutes(dur - pos) + " left";
                w.progressPct = dur > 0 ? (int) Math.min(100, pos * 100 / dur) : -1;
                try {
                    String uri = c.getString(7);
                    if (uri != null) w.intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                } catch (Exception ignored) {
                }
                w.posterUri = c.getString(8) != null ? c.getString(8) : c.getString(9);
                w.when = c.getLong(10);
                App a = find(apps, w.pkg);
                if (a != null) w.fallbackArt = a.banner;
                if (!w.title.isEmpty()) out.add(w);
            }
        } catch (Exception ignored) {
            // No permission or no TV provider: use recent apps below.
        }
        mergeNowPlaying(out, apps);
        if (!out.isEmpty()) return out;

        Map<String, Long> last = lastUsed(14);
        List<App> recent = new ArrayList<>();
        for (App a : apps) if (!a.game && get(last, a.pkg) > 0) recent.add(a);
        Collections.sort(recent, (x, y) -> Long.compare(get(last, y.pkg), get(last, x.pkg)));
        for (App a : recent) {
            if (out.size() >= 6) break;
            Continue w = new Continue();
            w.pkg = a.pkg;
            w.title = "Continue in " + a.label;
            w.meta = "Opened " + ago(get(last, a.pkg));
            w.progressPct = -1;
            w.fallbackArt = a.banner;
            out.add(w);
        }
        return out;
    }

    /**
     * Adds what was last playing in apps that don't share Watch Next (Netflix, for one), then puts
     * everything in the order you watched it, so the most recent show leads.
     */
    private void mergeNowPlaying(List<Continue> out, List<App> apps) {
        for (NowPlaying.Played p : NowPlaying.recent(context)) {
            Continue same = null; // the app's most recent Watch Next card
            for (Continue w : out) if (w.pkg.equals(p.pkg) && (same == null || w.when > same.when)) same = w;
            if (same != null && same.when >= p.when) continue; // the app's own Watch Next entry is newer
            // Same app, watched within a day of its Watch Next card: the same show. Apps often publish
            // the episode name while playing ("The Battlefront of Alabasta!") and the show, poster and
            // progress to Watch Next ("One Piece", S4 · E105): keep the rich card and add the episode.
            if (same != null && (same.title.equalsIgnoreCase(p.title) || p.when - same.when < 86_400_000L)) {
                same.when = p.when;
                if (!same.title.equalsIgnoreCase(p.title) && same.episode == null) same.episode = p.title;
                continue;
            }
            Continue w = new Continue();
            w.pkg = p.pkg;
            w.title = p.title;
            w.meta = p.subtitle == null ? "" : p.subtitle;
            w.progressPct = -1;
            w.posterUri = p.art;
            w.when = p.when;
            w.session = NowPlaying.RESUME.get(p.pkg);
            App a = find(apps, p.pkg);
            if (a != null) {
                w.fallbackArt = a.banner;
                w.intent = launchIntent(a);
            } else {
                w.intent = pm.getLeanbackLaunchIntentForPackage(p.pkg);
                if (w.intent == null) w.intent = pm.getLaunchIntentForPackage(p.pkg);
            }
            if (w.intent != null) out.add(w);
        }
        Collections.sort(out, (x, y) -> Long.compare(y.when, x.when));

        // Safety net: a video app used since the newest show above, that told us nothing about what
        // it played, still leads. Better "Continue on Netflix" than an older show from another app.
        Map<String, Long> last = lastUsed(2);
        long newest = out.isEmpty() ? 0 : out.get(0).when;
        App lead = null;
        for (App a : apps) {
            if (a.game || NowPlaying.isAudio(a.pkg) || LIVE_TV_PKG.equals(a.pkg)) continue;
            long t = get(last, a.pkg);
            if (t > newest + 60_000 && (lead == null || t > get(last, lead.pkg))) lead = a;
        }
        if (lead != null) {
            boolean known = false;
            for (Continue w : out) known |= w.pkg.equals(lead.pkg);
            if (!known) {
                Continue w = new Continue();
                w.pkg = lead.pkg;
                w.title = "Continue on " + lead.label;
                w.meta = "Opened " + ago(get(last, lead.pkg));
                w.progressPct = -1;
                w.fallbackArt = lead.banner;
                w.intent = launchIntent(lead);
                w.when = get(last, lead.pkg);
                out.add(0, w);
            }
        }
    }

    private Map<String, Long> lastUsed(int days) {
        Map<String, Long> m = new HashMap<>();
        try {
            UsageStatsManager usm = context.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - days * 86_400_000L, now);
            if (stats != null) for (UsageStats s : stats) {
                if (s.getTotalTimeInForeground() <= 0) continue;
                m.put(s.getPackageName(), Math.max(get(m, s.getPackageName()), s.getLastTimeUsed()));
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    /** Loads a poster from the app's content:// or https:// address, scaled down for a card. */
    public Bitmap loadPoster(String uri, int maxW) {
        if (uri == null) return null;
        try {
            Uri u = Uri.parse(uri);
            byte[] data;
            if (ContentResolver.SCHEME_FILE.equals(u.getScheme())) {
                try (InputStream in = new java.io.FileInputStream(u.getPath())) {
                    data = readAll(in);
                }
            } else if (ContentResolver.SCHEME_CONTENT.equals(u.getScheme()) || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(u.getScheme())) {
                try (InputStream in = context.getContentResolver().openInputStream(u)) {
                    data = readAll(in);
                }
            } else {
                HttpURLConnection conn = (HttpURLConnection) new URL(uri).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    data = readAll(in);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= maxW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ---- Live TV ----

    public static class Channel {
        public long id;
        public String name, number;
    }

    /** Channels the TV's inputs publish (Xiaomi TV+ and others), in channel-number order. */
    public List<Channel> channels(int max) {
        List<Channel> out = new ArrayList<>();
        String[] cols = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER};
        try (Cursor c = context.getContentResolver().query(TvContract.Channels.CONTENT_URI, cols,
                TvContract.Channels.COLUMN_BROWSABLE + "=1", null, null)) {
            while (c != null && c.moveToNext()) {
                Channel ch = new Channel();
                ch.id = c.getLong(0);
                ch.name = nz(c.getString(1));
                ch.number = nz(c.getString(2));
                if (!ch.name.isEmpty()) out.add(ch);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> {
            try {
                return Double.compare(Double.parseDouble(a.number), Double.parseDouble(b.number));
            } catch (NumberFormatException e) {
                return a.number.compareTo(b.number);
            }
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    public Bitmap channelLogo(long id) {
        try (InputStream in = context.getContentResolver().openInputStream(TvContract.buildChannelLogoUri(id))) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            return in == null ? null : BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    private static long get(Map<String, Long> m, String k) {
        Long v = m.get(k);
        return v == null ? 0 : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String minutes(long ms) {
        long m = Math.max(1, ms / 60000);
        return m >= 60 ? (m / 60) + " h " + String.format(java.util.Locale.US, "%02d", m % 60) + " min" : m + " min";
    }

    static String ago(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 3_600_000) return Math.max(1, d / 60000) + " min ago";
        if (d < 86_400_000) return (d / 3_600_000) + " h ago";
        long days = d / 86_400_000;
        return days == 1 ? "yesterday" : days + " days ago";
    }
}
