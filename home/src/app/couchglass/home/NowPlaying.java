package app.couchglass.home;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers what was last playing in each app, from the "now playing" information apps publish for
 * Assistant and the remote (title, episode, artwork). Some apps, Netflix among them, don't add to
 * Android TV's Watch Next list; this is how Couchglass still knows you were watching them.
 *
 * It is a notification listener only because that is what Android requires to read media sessions;
 * it never reads notifications. Enable it with
 * {@code adb shell cmd notification allow_listener app.couchglass.home/app.couchglass.home.NowPlaying}.
 */
public class NowPlaying extends NotificationListenerService {
    static final String PREFS = "now_playing";
    private static final int KEEP = 6;
    /** Music players: what they play isn't something to "resume watching". */
    private static final java.util.Set<String> AUDIO = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.jio.media.jiobeats", "com.gaana"));

    static boolean isAudio(String pkg) {
        return AUDIO.contains(pkg);
    }

    /** Each app's way back into its player, while this process lives. */
    static final Map<String, PendingIntent> RESUME = new HashMap<>();

    public static class Played {
        public String pkg, title, subtitle, art; // art: a local file, or null
        public long when;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private MediaSessionManager msm;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::watch;

    @Override
    public void onListenerConnected() {
        msm = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, NowPlaying.class);
        try {
            msm.addOnActiveSessionsChangedListener(sessions, me, ui);
            watch(msm.getActiveSessions(me));
        } catch (SecurityException ignored) {
            // Not enabled as a listener: Couchglass falls back to Watch Next alone.
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) e.getKey().unregisterCallback(e.getValue());
        watched.clear();
    }

    private void watch(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) {
            if (watched.containsKey(c) || AUDIO.contains(c.getPackageName()) || getPackageName().equals(c.getPackageName())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    note(c);
                }

                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    note(c);
                }

                @Override
                public void onSessionDestroyed() {
                    note(c);
                    c.unregisterCallback(this);
                    watched.remove(c);
                }
            };
            c.registerCallback(cb, ui);
            watched.put(c, cb);
            note(c);
        }
    }

    /** Records the session while it plays (and once more as it pauses or stops). */
    private void note(MediaController c) {
        PlaybackState st = c.getPlaybackState();
        MediaMetadata md = c.getMetadata();
        if (st == null || md == null) return;
        int s = st.getState();
        if (s != PlaybackState.STATE_PLAYING && s != PlaybackState.STATE_PAUSED && s != PlaybackState.STATE_STOPPED) return;
        String title = first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) return;
        String sub = first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        String pkg = c.getPackageName();
        if (c.getSessionActivity() != null) RESUME.put(pkg, c.getSessionActivity());

        Played p = new Played();
        p.pkg = pkg;
        p.title = title;
        p.subtitle = sub;
        p.when = System.currentTimeMillis();
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        String artUri = first(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (art != null) p.art = saveArt(pkg, art);
        else if (artUri != null) p.art = artUri;
        else {
            Played old = find(this, pkg);
            if (old != null && title.equals(old.title)) p.art = old.art; // keep the picture we had for this show
        }
        save(this, p);
    }

    private String saveArt(String pkg, Bitmap b) {
        File f = new File(getFilesDir(), "np_" + pkg + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 88, out);
            return android.net.Uri.fromFile(f).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String first(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return null;
    }

    // ---- storage: the last few apps, newest first ----

    static List<Played> recent(Context c) {
        List<Played> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences(PREFS, MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Played p = new Played();
                p.pkg = o.getString("pkg");
                p.title = o.getString("title");
                p.subtitle = o.optString("sub", null);
                p.art = o.optString("art", null);
                p.when = o.getLong("when");
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Played find(Context c, String pkg) {
        for (Played p : recent(c)) if (p.pkg.equals(pkg)) return p;
        return null;
    }

    private static void save(Context c, Played p) {
        List<Played> list = recent(c);
        List<Played> keep = new ArrayList<>();
        keep.add(p);
        for (Played q : list) if (!q.pkg.equals(p.pkg) && keep.size() < KEEP) keep.add(q);
        JSONArray a = new JSONArray();
        try {
            for (Played q : keep) {
                JSONObject o = new JSONObject().put("pkg", q.pkg).put("title", q.title).put("when", q.when);
                if (q.subtitle != null) o.put("sub", q.subtitle);
                if (q.art != null) o.put("art", q.art);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        SharedPreferences prefs = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putString("list", a.toString()).apply();
    }
}
