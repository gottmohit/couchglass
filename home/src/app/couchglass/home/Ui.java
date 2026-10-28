package app.couchglass.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

/**
 * Palette, type and the shared focus treatment from the Glass TV Home design.
 * Colours come from the active theme (Mist, Dusk, Aurora, Sand); call apply() before building views.
 */
public final class Ui {
    public static final String[] THEMES = {"Auto", "Mist", "Dusk", "Aurora", "Sand"};

    // Ground and ink
    public static int GROUND, INK, INK_SOFT, MUTED, ACCENT, DANGER;
    // Neumorphic light and shade
    public static int NEU_LIGHT, NEU_DARK;
    // Glass fills and surfaces
    public static int GLASS, GLASS_STRONG, POPUP, CHIP, STRIP, TILE, TRACK, CLOUD;
    public static boolean DARK;
    private static int[] orbs;
    private static float orbAlpha;
    public static String current = "";

    private static Typeface display, body, bodyBold;

    private Ui() {
    }

    /** Applies a theme by name ("Mist", "Dusk", "Aurora" or "Sand"). */
    public static void apply(String theme) {
        current = theme;
        switch (theme) {
            case "Dusk":
                set(0xFF161B2B, 0xFFEEF1F8, 0xFFC7CEDF, 0xFFA6AFC4, 0xFF6A86FF, 0xFFFF8A80,
                        0x0FFFFFFF, 0x8C000000, 0x12FFFFFF, 0x29FFFFFF, 0xF2222A3E, 0x29FFFFFF, 0x9E161B2B, 0xFF232A3E, 0x24FFFFFF, 0xFFC7CEDF, true,
                        new int[]{0xFF7A47E0, 0xFFD2468C, 0xFFE3954A}, 0.42f);
                break;
            case "Aurora":
                set(0xFF0F1D1C, 0xFFE8F3F0, 0xFFC1D8D3, 0xFF9DB9B3, 0xFF2BC4A0, 0xFFFF8A80,
                        0x0DFFFFFF, 0x80000000, 0x12FFFFFF, 0x26FFFFFF, 0xF21A2C2A, 0x26FFFFFF, 0x990F1D1C, 0xFF1A2C2A, 0x21FFFFFF, 0xFFC1D8D3, true,
                        new int[]{0xFF22B593, 0xFF2A73CC, 0xFF8FD65A}, 0.38f);
                break;
            case "Sand":
                set(0xFFE6D9C6, 0xFF2A241C, 0xFF443A2D, 0xFF5A4D3C, 0xFF256B57, 0xFFA8321E,
                        0xE6FFFFFF, 0xA6A08562, 0x52FFFFFF, 0xC0FFFFFF, 0xF5F5EEE3, 0xC7FFFFFF, 0xB8FFFFFF, 0xFFD8CAB5, 0xB3FFFFFF, 0xFFFFFFFF, false,
                        new int[]{0xFF7FB48A, 0xFFF2AA35, 0xFFE07656}, 0.82f);
                break;
            default: // Mist
                current = "Mist";
                set(0xFFD0DAE6, 0xFF1A2536, 0xFF2F3F56, 0xFF3B4B60, 0xFF2259D6, 0xFFB3261E,
                        0xF2FFFFFF, 0xA67C8FA8, 0x4DFFFFFF, 0xB8FFFFFF, 0xF5EEF3F8, 0xC7FFFFFF, 0xB3FFFFFF, 0xFFC2CEDC, 0xA6FFFFFF, 0xFFFFFFFF, false,
                        new int[]{0xFF4FC4B8, 0xFF9A88F2, 0xFFF4A27C}, 0.85f);
        }
    }

    private static void set(int ground, int ink, int inkSoft, int muted, int accent, int danger,
                            int neuLight, int neuDark, int glass, int glassStrong, int popup, int chip, int strip,
                            int tile, int track, int cloud, boolean dark, int[] orbColors, float orbA) {
        GROUND = ground; INK = ink; INK_SOFT = inkSoft; MUTED = muted; ACCENT = accent; DANGER = danger;
        NEU_LIGHT = neuLight; NEU_DARK = neuDark; GLASS = glass; GLASS_STRONG = glassStrong; POPUP = popup;
        CHIP = chip; STRIP = strip; TILE = tile; TRACK = track; CLOUD = cloud; DARK = dark;
        orbs = orbColors; orbAlpha = orbA;
    }

    /** Auto: Mist while the sun is up, Dusk after sunset (times from the weather service, else 6:00–18:30). */
    public static String resolve(String choice, long sunrise, long sunset) {
        if (!"Auto".equals(choice)) return choice;
        long now = System.currentTimeMillis();
        if (sunrise > 0 && sunset > 0 && Math.abs(now - sunrise) < 86_400_000L) {
            return now >= sunrise && now < sunset ? "Mist" : "Dusk";
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        return m >= 360 && m < 1110 ? "Mist" : "Dusk";
    }

    public static void init(Context c) {
        if (display != null) return;
        display = font(c, "fonts/urbanist.ttf", 750);
        body = font(c, "fonts/figtree.ttf", 420);
        bodyBold = font(c, "fonts/figtree.ttf", 600);
    }

    private static Typeface font(Context c, String path, int weight) {
        try {
            return new Typeface.Builder(c.getAssets(), path)
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Exception e) {
            return weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        }
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, int style) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setTypeface(style == 2 ? display : style == 1 ? bodyBold : body);
        return t;
    }

    public static final int BODY = 0, BOLD = 1, DISPLAY = 2;

    /** The typeface for a style, for views that draw their own text. */
    public static Typeface face(int style) {
        return style == DISPLAY ? display : style == BOLD ? bodyBold : body;
    }

    public static void focusLift(View v, float radiusPx, float scale) {
        focusLift(v, radiusPx, scale, ACCENT);
    }

    /**
     * Remote focus: lift, scale and a ring. The ring is a foreground so it sits over
     * images; the lift uses translation so neighbours don't move.
     */
    public static void focusLift(View v, float radiusPx, float scale, int ringColor) {
        focus(v, radiusPx, scale, ringColor, true);
    }

    /** For rows of small controls (the top bar): grow and ring, but stay in line with neighbours. */
    public static void focusGrow(View v, float radiusPx, float scale) {
        focus(v, radiusPx, scale, ACCENT, false);
    }

    private static void focus(View v, float radiusPx, float scale, int ringColor, boolean lift) {
        Context c = v.getContext();
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(radiusPx);
        ring.setStroke(dp(c, 2.5f), ringColor);
        v.setFocusable(true);
        v.setOnFocusChangeListener((view, has) -> {
            view.setForeground(has ? ring : null);
            view.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                    .translationY(has && lift ? -dp(c, 4) : 0).translationZ(has ? dp(c, lift ? 8 : 3) : 0)
                    .setDuration(150).start();
        });
    }

    public static int[] orbColors() {
        return orbs;
    }

    public static float orbAlpha() {
        return orbAlpha;
    }

    /** Soft colour fields for the wallpaper, drawn at half resolution (they have no detail to lose). */
    public static Bitmap wallpaper(int w, int h) {
        int sw = Math.max(1, w / 2), sh = Math.max(1, h / 2);
        Bitmap b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(GROUND);
        float k = sw / 960f;
        orb(c, k * 1420, k * 110, k * 560, orbs[0]);
        orb(c, k * 1760, k * 410, k * 440, orbs[1]);
        orb(c, k * 820, k * 240, k * 440, orbs[2]);
        return b;
    }

    private static void orb(Canvas c, float cx, float cy, float r, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int a = Math.round(255 * orbAlpha);
        int rgb = color & 0x00FFFFFF;
        int full = (a << 24) | rgb;
        int strong = (Math.round(a * 0.85f) << 24) | rgb;
        int soft = (Math.round(a * 0.35f) << 24) | rgb;
        p.setShader(new RadialGradient(cx / 2, cy / 2, r / 2, new int[]{full, strong, soft, rgb},
                new float[]{0f, 0.4f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx / 2, cy / 2, r / 2, p);
    }
}
