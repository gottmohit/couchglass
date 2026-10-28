package app.couchglass.care;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared connection, cleaner and settings for the activity and the service. */
public final class Care {
    public static final String KEY_AUTO_SLEEP = "auto_sleep";
    public static final String KEY_AUTO_LOW = "auto_low";
    public static final String KEY_LOW_MB = "low_mb";

    private static Care instance;

    public final AdbClient adb;
    public final Cleaner cleaner;
    /** All debugging work runs here, one command at a time. */
    public final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;

    private Care(Context c) {
        adb = new AdbClient(c, "127.0.0.1", 5555);
        cleaner = new Cleaner(c, adb);
        prefs = c.getApplicationContext().getSharedPreferences(Cleaner.PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized Care get(Context c) {
        if (instance == null) instance = new Care(c);
        return instance;
    }

    /** Connects if needed. Short wait unless the user is looking at the app. */
    public void ensureConnected(int waitForUserMs) throws IOException {
        if (!adb.isConnected()) adb.connect(waitForUserMs);
    }

    public boolean autoOnSleep() { return prefs.getBoolean(KEY_AUTO_SLEEP, true); }
    public boolean autoOnLow() { return prefs.getBoolean(KEY_AUTO_LOW, true); }
    public int lowMb() { return prefs.getInt(KEY_LOW_MB, 200); }

    public void setAutoOnSleep(boolean v) { prefs.edit().putBoolean(KEY_AUTO_SLEEP, v).apply(); }
    public void setAutoOnLow(boolean v) { prefs.edit().putBoolean(KEY_AUTO_LOW, v).apply(); }
    public void setLowMb(int v) { prefs.edit().putInt(KEY_LOW_MB, v).apply(); }
}
