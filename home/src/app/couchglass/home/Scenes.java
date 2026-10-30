package app.couchglass.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * The screensaver's scenes. Each is a full-screen view that animates itself while it is attached
 * and stops as soon as it is removed.
 *  - Sky: the real sky outside — colour from the sun's position, the sun or moon on its arc
 *    between sunrise and sunset, and today's weather (clouds, rain, stars).
 *  - Drift: silk ribbons flowing through a slowly changing current, in the theme's colours.
 *  - Flip: a big glass flip clock over the theme's glow.
 *  - Up next: what you were watching, ready to resume with OK.
 */
final class Scenes {
    static final String[] NAMES = {"Sky", "Drift", "Flip clock", "Up next"};
    static final int SKY = 0, DRIFT = 1, FLIP = 2, UP_NEXT = 3;
    private static final long MIN = 60_000L, DAY = 86_400_000L;

    private Scenes() {
    }

    static String time(Context c, Date d) {
        return DateFormat.format(DateFormat.is24HourFormat(c) ? "H:mm" : "h:mm", d).toString();
    }

    static String weatherLine(Weather.Now w) {
        if (w == null) return "";
        return w.temp + "°  ·  " + w.label() + (w.city.isEmpty() ? "" : "  ·  " + w.city);
    }

    /** Today's sunrise and sunset; the cached reading may be from another day, so only its time of day is used. */
    static long[] sunTimes(Weather.Now w, long now) {
        Calendar m = Calendar.getInstance();
        m.setTimeInMillis(now);
        m.set(Calendar.HOUR_OF_DAY, 0);
        m.set(Calendar.MINUTE, 0);
        m.set(Calendar.SECOND, 0);
        m.set(Calendar.MILLISECOND, 0);
        long midnight = m.getTimeInMillis();
        long rise = w != null && w.sunrise > 0 ? midnight + timeOfDay(w.sunrise) : midnight + 375 * MIN;
        long set = w != null && w.sunset > 0 ? midnight + timeOfDay(w.sunset) : midnight + 1080 * MIN;
        return new long[]{rise, set};
    }

    private static long timeOfDay(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        return (c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * MIN;
    }

    static int mix(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | (Math.round(ar + (br - ar) * f) << 16) | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    private static float luminance(int c) {
        return (0.2126f * ((c >> 16) & 255) + 0.7152f * ((c >> 8) & 255) + 0.0722f * (c & 255)) / 255f;
    }

    private static int alpha(int color, float a) {
        return (Math.round(255 * Math.max(0, Math.min(1, a))) << 24) | (color & 0x00FFFFFF);
    }

    /** Base: redraws itself every frameMs while attached; minute-by-minute nudges keep pixels from burning in. */
    abstract static class Scene extends View {
        final long start = SystemClock.uptimeMillis();
        final float dp;

        Scene(Context c) {
            super(c);
            dp = c.getResources().getDisplayMetrics().density;
        }

        float seconds() {
            return (SystemClock.uptimeMillis() - start) / 1000f;
        }

        /** A small offset that changes once a minute, eased in over a few seconds. */
        float[] nudge(float rangeX, float rangeY) {
            long minute = System.currentTimeMillis() / MIN;
            double a = (minute % 12) / 12.0 * 2 * Math.PI;
            return new float[]{(float) Math.cos(a) * rangeX * dp, (float) Math.sin(2 * a) * rangeY * dp};
        }

        void next(long frameMs) {
            if (isAttachedToWindow()) postInvalidateDelayed(frameMs);
        }
    }

    // ---------------------------------------------------------------- Sky

    static final class Sky extends Scene {
        private static final int NIGHT_TOP = 0xFF070B1E, NIGHT_BOTTOM = 0xFF1A2350;
        private final Weather.Now w;
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG), trail = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] stars = new float[4 * 140];
        private final float[] drops = new float[2 * 170];
        private final float[][] clouds;
        private final Random rnd = new Random(11);
        private Bitmap puff;
        private Path backHill, frontHill;
        private LinearGradient sky;
        private int top, bottom;
        private long skyAt;
        private float flash;

        Sky(Context c, Weather.Now w) {
            super(c);
            this.w = w;
            kind = w == null ? 0 : Weather.kind(w.code);
            for (int i = 0; i < stars.length; i += 4) {
                stars[i] = rnd.nextFloat();
                stars[i + 1] = rnd.nextFloat() * 0.72f;
                stars[i + 2] = 0.6f + rnd.nextFloat() * 1.4f; // radius in dp
                stars[i + 3] = rnd.nextFloat() * 6.28f;
            }
            for (int i = 0; i < drops.length; i += 2) {
                drops[i] = rnd.nextFloat();
                drops[i + 1] = rnd.nextFloat();
            }
            int n = kind == 0 ? 2 : kind == 1 ? 4 : 8;
            clouds = new float[n][];
            for (int i = 0; i < n; i++) {
                clouds[i] = new float[]{rnd.nextFloat(), 0.08f + rnd.nextFloat() * 0.45f, 0.7f + rnd.nextFloat() * 0.9f, 4 + rnd.nextFloat() * 8};
            }
            dash.setStyle(Paint.Style.STROKE);
            dash.setStrokeWidth(1.5f * dp);
            dash.setColor(0x55FFFFFF);
            dash.setPathEffect(new DashPathEffect(new float[]{3 * dp, 7 * dp}, 0));
            trail.setStyle(Paint.Style.STROKE);
            trail.setStrokeWidth(2f * dp);
            trail.setStrokeCap(Paint.Cap.ROUND);
            big.setTypeface(Ui.face(Ui.DISPLAY));
            big.setColor(0xFFFFFFFF);
            big.setTextSize(92 * dp);
            big.setLetterSpacing(-0.02f);
            big.setShadowLayer(18 * dp, 0, 2 * dp, 0x40000000);
            small.setTypeface(Ui.face(Ui.BODY));
            small.setColor(0xE6FFFFFF);
            small.setTextSize(17 * dp);
            small.setShadowLayer(10 * dp, 0, 1 * dp, 0x40000000);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float hy = h * 0.80f;
            backHill = hill(w, h, hy - 30 * dp, 22 * dp, 0.0031f, 1.7f);
            frontHill = hill(w, h, hy + 6 * dp, 16 * dp, 0.0053f, 4.1f);
            puff = puff((int) (200 * dp), (int) (110 * dp));
            sky = null;
        }

        private Path hill(int w, int h, float base, float amp, float freq, float phase) {
            Path path = new Path();
            path.moveTo(0, h);
            for (int x = 0; x <= w + 24; x += 24) {
                float y = base + (float) (Math.sin(x * freq + phase) * amp + Math.sin(x * freq * 2.7f + phase * 2) * amp * 0.35f);
                path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.close();
            return path;
        }

        /** One soft cloud, drawn once and reused at different sizes. */
        private static Bitmap puff(int w, int h) {
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            // Radii stay inside the bitmap so no blob is cut off at an edge.
            float[][] blobs = {{0.32f, 0.60f, 0.20f}, {0.50f, 0.50f, 0.24f}, {0.68f, 0.60f, 0.19f}, {0.45f, 0.64f, 0.21f}};
            for (float[] k : blobs) {
                float cx = k[0] * w, cy = k[1] * h, r = Math.min(k[2] * w, Math.min(cy, h - cy));
                p.setShader(new RadialGradient(cx, cy, r, new int[]{0x99FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, r, p);
            }
            return b;
        }

        /** Sky colours through the day, keyed to the real sunrise and sunset. */
        private void skyColours(long now, long rise, long set) {
            long[] at = {rise - 80 * MIN, rise - 30 * MIN, rise + 10 * MIN, rise + 70 * MIN, set - 80 * MIN, set - 20 * MIN, set + 10 * MIN, set + 45 * MIN, set + 95 * MIN};
            int[] tops = {NIGHT_TOP, 0xFF242E63, 0xFF5767AD, 0xFF3B7AD6, 0xFF3B7AD6, 0xFF4560B0, 0xFF3A2C6C, 0xFF1C1B48, NIGHT_TOP};
            int[] bottoms = {NIGHT_BOTTOM, 0xFF7E5590, 0xFFF3A27D, 0xFFA9D4F4, 0xFFA9D4F4, 0xFFF5C279, 0xFFE8725C, 0xFF56386C, NIGHT_BOTTOM};
            top = NIGHT_TOP;
            bottom = NIGHT_BOTTOM;
            for (int i = 0; i < at.length - 1; i++) {
                if (now >= at[i] && now < at[i + 1]) {
                    float f = (now - at[i]) / (float) (at[i + 1] - at[i]);
                    top = mix(tops[i], tops[i + 1], f);
                    bottom = mix(bottoms[i], bottoms[i + 1], f);
                }
            }
            float grey = kind == 1 ? 0.12f : kind == 2 ? 0.45f : kind >= 3 ? 0.55f : 0;
            boolean dark = luminance(top) < 0.2f;
            top = mix(top, dark ? 0xFF1E2230 : 0xFF7C8596, grey);
            bottom = mix(bottom, dark ? 0xFF353A48 : 0xFFB9C0CB, grey);
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight(), t = seconds();
            long now = System.currentTimeMillis();
            long[] sun = sunTimes(w, now);
            long rise = sun[0], set = sun[1];
            if (sky == null || now - skyAt > 5000) {
                skyColours(now, rise, set);
                sky = new LinearGradient(0, 0, 0, H * 0.82f, top, bottom, Shader.TileMode.CLAMP);
                skyAt = now;
            }
            p.setShader(sky);
            c.drawRect(0, 0, W, H, p);
            p.setShader(null);

            // Stars fade in as the sky darkens.
            float night = Math.max(0, Math.min(1, (0.26f - luminance(top)) / 0.18f)) * (kind >= 2 ? 0.25f : 1f);
            if (night > 0) {
                for (int i = 0; i < stars.length; i += 4) {
                    float tw = 0.55f + 0.45f * (float) Math.sin(t * 0.9f + stars[i + 3]);
                    p.setColor(alpha(0xFFFFFFFF, night * tw * 0.85f));
                    c.drawCircle(stars[i] * W, stars[i + 1] * H, stars[i + 2] * dp * 0.8f, p);
                }
            }

            // The sun's path: dashed ahead, solid behind; the moon takes the same path at night.
            float hy = H * 0.80f, cx = W / 2, rx = W * 0.38f, ry = H * 0.50f;
            RectF oval = new RectF(cx - rx, hy - ry, cx + rx, hy + ry);
            boolean day = now >= rise && now < set;
            float f = day ? (now - rise) / (float) (set - rise)
                    : now >= set ? (now - set) / (float) (rise + DAY - set) : (now - (set - DAY)) / (float) (rise - (set - DAY));
            c.drawArc(oval, 180, 180, false, dash);
            trail.setShader(new LinearGradient(cx - rx, 0, cx + rx, 0, 0x10FFFFFF, day ? 0xCCFFF1C9 : 0x99E4EAFF, Shader.TileMode.CLAMP));
            c.drawArc(oval, 180, 180 * f, false, trail);
            double a = Math.toRadians(180 + 180 * f);
            float bx = cx + rx * (float) Math.cos(a), by = hy + ry * (float) Math.sin(a);
            if (day) {
                float r = 120 * dp;
                p.setShader(new RadialGradient(bx, by, r, new int[]{0x88FFEDB8, 0x33FFD98A, 0x00FFD98A}, new float[]{0, 0.35f, 1}, Shader.TileMode.CLAMP));
                c.drawCircle(bx, by, r, p);
                p.setShader(null);
                p.setColor(0xFFFFF6DE);
                c.drawCircle(bx, by, 17 * dp, p);
            } else {
                float r = 70 * dp;
                p.setShader(new RadialGradient(bx, by, r, new int[]{0x44DDE6FF, 0x00DDE6FF}, null, Shader.TileMode.CLAMP));
                c.drawCircle(bx, by, r, p);
                p.setShader(null);
                Path moon = new Path(), bite = new Path();
                moon.addCircle(bx, by, 15 * dp, Path.Direction.CW);
                bite.addCircle(bx + 7 * dp, by - 4 * dp, 13 * dp, Path.Direction.CW);
                moon.op(bite, Path.Op.DIFFERENCE);
                p.setColor(0xFFEEF1FA);
                c.drawPath(moon, p);
            }

            // Clouds drift across, in front of the sun.
            float cloudA = luminance(top) < 0.2f ? 0.22f : kind >= 2 ? 0.95f : 0.8f;
            for (float[] k : clouds) {
                float cw = puff.getWidth() * k[2] * 1.6f, ch = puff.getHeight() * k[2] * 1.6f;
                float x = ((k[0] * (W + cw) + t * k[3] * dp) % (W + cw)) - cw;
                p.setAlpha(Math.round(255 * cloudA));
                c.drawBitmap(puff, null, new RectF(x, k[1] * H, x + cw, k[1] * H + ch), p);
            }
            p.setAlpha(255);

            // Hills in the colour of the horizon.
            p.setColor(mix(bottom, 0xFF000000, 0.42f));
            c.drawPath(backHill, p);
            p.setColor(mix(bottom, 0xFF000000, 0.66f));
            c.drawPath(frontHill, p);

            // Rain and thunder.
            if (kind == 3 || kind == 4) {
                p.setColor(0x66FFFFFF);
                p.setStrokeWidth(1.4f * dp);
                for (int i = 0; i < drops.length; i += 2) {
                    float x = drops[i] * W, y = ((drops[i + 1] + t * 0.9f) % 1f) * H;
                    c.drawLine(x - (y / H) * 40 * dp, y, x - (y / H) * 40 * dp - 5 * dp, y + 22 * dp, p);
                }
                if (kind == 4) {
                    if (flash <= 0 && rnd.nextFloat() < 0.004f) flash = 1;
                    if (flash > 0) {
                        c.drawColor(alpha(0xFFFFFFFF, flash * 0.35f));
                        flash -= 0.08f;
                    }
                }
            }

            // The time.
            float[] n = nudge(24, 14);
            float x0 = 84 * dp + n[0], y0 = 150 * dp + n[1];
            small.setTextAlign(Paint.Align.LEFT);
            c.drawText(time(getContext(), new Date(now)), x0, y0, big);
            small.setTextSize(19 * dp);
            c.drawText(DateFormat.format("EEEE, d MMMM", now).toString(), x0 + 4 * dp, y0 + 38 * dp, small);
            small.setTextSize(16 * dp);
            float line = y0 + 66 * dp;
            if (w != null) {
                c.drawText(weatherLine(w), x0 + 4 * dp, line, small);
                line += 26 * dp;
            }
            c.drawText("Sunrise " + time(getContext(), new Date(rise)) + "   ·   Sunset " + time(getContext(), new Date(set)), x0 + 4 * dp, line, small);
            next(kind == 3 || kind == 4 ? 40 : 66);
        }
    }

    // ---------------------------------------------------------------- Drift

    static final class Drift extends Scene {
        private static final int N = 1200;
        private final float[] x = new float[N], y = new float[N];
        private final int[] life = new int[N];
        private final float[][] lines = {new float[N * 4], new float[N * 4], new float[N * 4]};
        private final int[] count = new int[3];
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fade = new Paint(), blit = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Random rnd = new Random();
        private Bitmap buf;
        private Canvas bc;
        private final Rect dst = new Rect();

        Drift(Context c) {
            super(c);
            stroke.setStrokeWidth(1.25f);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            fade.setColor(alpha(Ui.GROUND, Ui.DARK ? 0.07f : 0.06f));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            // Half resolution: the ribbons are soft anyway, and it keeps each frame cheap.
            buf = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.ARGB_8888);
            bc = new Canvas(buf);
            bc.drawColor(Ui.GROUND);
            dst.set(0, 0, w, h);
            for (int i = 0; i < N; i++) spawn(i);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            buf = null;
            bc = null;
        }

        private void spawn(int i) {
            x[i] = rnd.nextFloat() * buf.getWidth();
            y[i] = rnd.nextFloat() * buf.getHeight();
            life[i] = 60 + rnd.nextInt(220);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (buf == null) return;
            float t = seconds(), bw = buf.getWidth(), bh = buf.getHeight();
            bc.drawRect(0, 0, bw, bh, fade);
            count[0] = count[1] = count[2] = 0;
            for (int i = 0; i < N; i++) {
                float px = x[i], py = y[i];
                double a = (Math.sin(px * 0.0047 + t * 0.11) + Math.cos(py * 0.0061 - t * 0.08)
                        + Math.sin((px - py) * 0.0023 + t * 0.05)) * 1.7;
                float nx = px + (float) Math.cos(a) * 1.5f, ny = py + (float) Math.sin(a) * 1.5f;
                int g = i % 3;
                float[] l = lines[g];
                int k = count[g];
                l[k] = px;
                l[k + 1] = py;
                l[k + 2] = nx;
                l[k + 3] = ny;
                count[g] = k + 4;
                x[i] = nx;
                y[i] = ny;
                if (--life[i] <= 0 || nx < 0 || ny < 0 || nx >= bw || ny >= bh) spawn(i);
            }
            int[] orbs = Ui.orbColors();
            for (int g = 0; g < 3; g++) {
                stroke.setColor(alpha(orbs[g], Ui.DARK ? 0.55f : 0.42f));
                bc.drawLines(lines[g], 0, count[g], stroke);
            }
            c.drawBitmap(buf, null, dst, blit);
            next(50);
        }
    }

    // ---------------------------------------------------------------- Flip clock

    static final class Flip extends Scene {
        private static final long FLIP_MS = 700;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), digits = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG), tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Weather.Now w;
        private final String[] shown = {"", ""}, before = {"", ""};
        private final long[] flipAt = {0, 0};

        Flip(Context c, Weather.Now w) {
            super(c);
            this.w = w;
            digits.setTypeface(Ui.face(Ui.DISPLAY));
            digits.setColor(Ui.INK);
            digits.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(Ui.face(Ui.BODY));
            label.setColor(Ui.INK_SOFT);
            label.setTextAlign(Paint.Align.CENTER);
            tile.setColor(Ui.POPUP | 0xFF000000);
            tile.setShadowLayer(26 * dp, 0, 12 * dp, Ui.DARK ? 0x66000000 : 0x407C8FA8);
        }

        @Override
        protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            Glow.paint(c, W, H, seconds(), p);

            Date now = new Date();
            boolean h24 = DateFormat.is24HourFormat(getContext());
            String[] want = {DateFormat.format(h24 ? "HH" : "h", now).toString(), DateFormat.format("mm", now).toString()};
            long up = SystemClock.uptimeMillis();
            for (int i = 0; i < 2; i++) {
                if (!want[i].equals(shown[i])) {
                    before[i] = shown[i].isEmpty() ? want[i] : shown[i];
                    shown[i] = want[i];
                    flipAt[i] = up;
                }
            }

            float th = 260 * dp, tw = 250 * dp, gap = 24 * dp; // leaves the bottom right for the Up next ticket
            float[] n = nudge(30, 18);
            float left = (W - 2 * tw - gap) / 2 + n[0], topY = (H - th) / 2 - 110 * dp + n[1];
            boolean busy = false;
            for (int i = 0; i < 2; i++) {
                float pr = Math.min(1, (up - flipAt[i]) / (float) FLIP_MS);
                busy |= pr < 1;
                RectF r = new RectF(left + i * (tw + gap), topY, left + i * (tw + gap) + tw, topY + th);
                drawTile(c, r, before[i], shown[i], pr);
            }
            if (!h24) {
                label.setTextSize(15 * dp);
                label.setTextAlign(Paint.Align.LEFT);
                c.drawText(DateFormat.format("a", now).toString().toUpperCase(), left + 20 * dp, topY + th - 20 * dp, label);
                label.setTextAlign(Paint.Align.CENTER);
            }
            label.setTextSize(20 * dp);
            String line = DateFormat.format("EEEE, d MMMM", now).toString();
            if (w != null) line += "   ·   " + w.temp + "°  " + w.label();
            c.drawText(line, W / 2 + n[0], topY + th + 52 * dp, label);
            next(busy ? 16 : 66);
        }

        /** A split-flap tile: the old top half folds down onto the new one, then the new bottom half falls into place. */
        private void drawTile(Canvas c, RectF r, String old, String now, float pr) {
            float mid = r.centerY();
            c.drawRoundRect(r, 30 * dp, 30 * dp, tile);
            if (pr >= 1) {
                half(c, r, now, true, 1);
                half(c, r, now, false, 1);
            } else {
                half(c, r, now, true, 1);
                half(c, r, old, false, 1);
                if (pr < 0.5f) half(c, r, old, true, 1 - pr * 2);
                else half(c, r, now, false, (pr - 0.5f) * 2);
            }
            p.setColor(Ui.GROUND);
            p.setAlpha(Ui.DARK ? 200 : 150);
            c.drawRect(r.left, mid - 1.5f * dp, r.right, mid + 1.5f * dp, p);
            p.setAlpha(255);
        }

        private void half(Canvas c, RectF r, String text, boolean upper, float scale) {
            float mid = r.centerY();
            c.save();
            c.clipRect(r.left, upper ? r.top : mid, r.right, upper ? mid : r.bottom);
            c.scale(1, Math.max(0.001f, scale), r.centerX(), mid);
            p.setColor(Ui.POPUP | 0xFF000000);
            c.drawRoundRect(r, 30 * dp, 30 * dp, p);
            digits.setTextSize(r.height() * 0.66f);
            Paint.FontMetrics fm = digits.getFontMetrics();
            c.drawText(text, r.centerX(), mid - (fm.ascent + fm.descent) / 2, digits);
            if (scale < 1) { // shade the moving flap as it turns away from the light
                p.setColor(0xFF000000);
                p.setAlpha(Math.round((1 - scale) * (Ui.DARK ? 120 : 60)));
                c.drawRect(r, p);
                p.setAlpha(255);
            }
            c.restore();
        }
    }

    // ---------------------------------------------------------------- Up next

    static final class Pick {
        String title, meta, app;
        int progressPct;
        Intent intent;
        android.app.PendingIntent session; // the app's own way back to its player, when known
        Bitmap poster, blur;
    }

    /** Ambient "continue watching": one show at a time over its own blurred artwork. OK plays it. */
    static final class UpNext extends FrameLayout {
        private final List<Pick> picks;
        private final Backdrop backdrop;
        private final ImageView poster;
        private final TextView app, title, meta, clock;
        private final Bar bar;
        private final LinearLayout card;
        private int index = -1;
        private final Runnable rotate = new Runnable() {
            @Override
            public void run() {
                show((index + 1) % picks.size());
                postDelayed(this, 25_000);
            }
        };
        private final Runnable clockTick = new Runnable() {
            @Override
            public void run() {
                clock.setText(time(getContext(), new Date()));
                postDelayed(this, 10_000);
            }
        };

        UpNext(Context c, List<Pick> picks) {
            super(c);
            this.picks = picks;
            backdrop = new Backdrop(c);
            addView(backdrop, new LayoutParams(-1, -1));

            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            poster = new ImageView(c);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22));
                }
            });
            poster.setClipToOutline(true);
            poster.setBackgroundColor(0xFF1A1D26);
            poster.setElevation(dp(18));
            card.addView(poster, new LinearLayout.LayoutParams(dp(440), dp(248)));

            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView kicker = Ui.text(c, "PICK UP WHERE YOU LEFT OFF", 12, 0xB3FFFFFF, Ui.BOLD);
            kicker.setLetterSpacing(0.14f);
            app = Ui.text(c, "", 15, 0xCCFFFFFF, Ui.BODY);
            title = Ui.text(c, "", 38, 0xFFFFFFFF, Ui.DISPLAY);
            title.setMaxLines(2);
            title.setLineSpacing(0, 0.95f);
            meta = Ui.text(c, "", 16, 0xD9FFFFFF, Ui.BODY);
            bar = new Bar(c);
            TextView play = Ui.text(c, "OK  ▶  Play", 15, 0xFF111111, Ui.BOLD);
            play.setPadding(dp(18), dp(9), dp(20), dp(9));
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setCornerRadius(dp(20));
            pill.setColor(0xF2FFFFFF);
            play.setBackground(pill);
            col.addView(kicker);
            col.addView(app, lp(14, -2));
            col.addView(title, lp(6, dp(420)));
            col.addView(meta, lp(10, -2));
            col.addView(bar, lp(14, dp(240)));
            LinearLayout.LayoutParams bp = lp(26, -2);
            col.addView(play, bp);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.leftMargin = dp(44);
            card.addView(col, cp);
            LayoutParams lp = new LayoutParams(-2, -2, Gravity.CENTER_VERTICAL);
            lp.leftMargin = dp(90);
            addView(card, lp);

            clock = Ui.text(c, "", 30, 0xFFFFFFFF, Ui.DISPLAY);
            LayoutParams kp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            kp.topMargin = dp(44);
            kp.rightMargin = dp(60);
            addView(clock, kp);
        }

        private LinearLayout.LayoutParams lp(int top, int width) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, -2);
            p.topMargin = dp(top);
            return p;
        }

        private int dp(float v) {
            return Ui.dp(getContext(), v);
        }

        Pick current() {
            return index < 0 ? null : picks.get(index);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            rotate.run();
            clockTick.run();
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(rotate);
            removeCallbacks(clockTick);
            super.onDetachedFromWindow();
        }

        private void show(int i) {
            boolean first = index < 0;
            index = i;
            Pick k = picks.get(i);
            Runnable fill = () -> {
                poster.setImageBitmap(k.poster);
                app.setText(k.app);
                title.setText(k.title);
                meta.setText(k.meta);
                meta.setVisibility(k.meta.isEmpty() ? GONE : VISIBLE);
                bar.pct = k.progressPct;
                bar.setVisibility(k.progressPct > 0 ? VISIBLE : GONE);
                bar.invalidate();
                backdrop.set(k.blur);
                card.setTranslationX((i % 2 == 0 ? 1 : -1) * dp(12)); // a little movement between shows
                card.animate().alpha(1).setDuration(900).start();
            };
            if (first) fill.run();
            else card.animate().alpha(0).setDuration(700).withEndAction(fill).start();
        }

        /** The show's artwork, blurred by scaling a tiny copy up, drifting slowly. */
        private static final class Backdrop extends Scene {
            private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            private Bitmap img;

            Backdrop(Context c) {
                super(c);
            }

            void set(Bitmap b) {
                img = b;
                invalidate();
            }

            @Override
            protected void onDraw(Canvas c) {
                float W = getWidth(), H = getHeight(), t = seconds();
                c.drawColor(0xFF0B0D14);
                if (img != null) {
                    float s = 1.25f + 0.08f * (float) Math.sin(t / 19f);
                    float iw = img.getWidth(), ih = img.getHeight(), k = Math.max(W / iw, H / ih) * s;
                    float dx = (W - iw * k) / 2 + (float) Math.sin(t / 23f) * 30 * dp, dy = (H - ih * k) / 2;
                    c.drawBitmap(img, null, new RectF(dx, dy, dx + iw * k, dy + ih * k), p);
                }
                Paint shade = new Paint();
                shade.setShader(new LinearGradient(0, 0, W, 0, 0xD9080A10, 0x80080A10, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, W, H, shade);
                next(66);
            }
        }

        private static final class Bar extends View {
            int pct;
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            Bar(Context c) {
                super(c);
            }

            @Override
            protected void onMeasure(int w, int h) {
                setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(getContext(), 5));
            }

            @Override
            protected void onDraw(Canvas c) {
                float r = getHeight() / 2f;
                p.setColor(0x40FFFFFF);
                c.drawRoundRect(0, 0, getWidth(), getHeight(), r, r, p);
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(0, 0, getWidth() * pct / 100f, getHeight(), r, r, p);
            }
        }
    }

    // ---------------------------------------------------------------- shared

    /** The theme's three glows, drifting slowly (the background of the flip clock). */
    static final class Glow {
        private Glow() {
        }

        static void paint(Canvas c, float w, float h, float t, Paint paint) {
            c.drawColor(Ui.GROUND);
            int[] orbs = Ui.orbColors();
            orb(c, w * (0.68f + 0.12f * (float) Math.sin(t / 47f)), h * (0.22f + 0.15f * (float) Math.cos(t / 61f)), w * 0.36f, orbs[0], paint);
            orb(c, w * (0.30f + 0.14f * (float) Math.cos(t / 53f)), h * (0.70f + 0.12f * (float) Math.sin(t / 43f)), w * 0.30f, orbs[1], paint);
            orb(c, w * (0.45f + 0.20f * (float) Math.sin(t / 71f)), h * (0.40f + 0.18f * (float) Math.sin(t / 37f)), w * 0.28f, orbs[2], paint);
            paint.setShader(null);
        }

        private static void orb(Canvas c, float cx, float cy, float r, int color, Paint paint) {
            int a = Math.round(255 * Ui.orbAlpha());
            int rgb = color & 0x00FFFFFF;
            paint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{(a << 24) | rgb, (Math.round(a * 0.85f) << 24) | rgb, (Math.round(a * 0.35f) << 24) | rgb, rgb},
                    new float[]{0f, 0.4f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
        }
    }
}
