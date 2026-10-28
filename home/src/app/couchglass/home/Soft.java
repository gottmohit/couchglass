package app.couchglass.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * The two surface styles of the design, drawn once into a cached bitmap so
 * scrolling and focus changes cost nothing:
 *  - NEU_UP / NEU_IN: neumorphic surfaces raised from, or pressed into, the ground.
 *  - GLASS: frosted panel with an inner top highlight, hairline border and soft drop shadow.
 * Shadows spill outside the bounds, so parents must not clip children.
 */
public class Soft extends Drawable {
    public enum Kind { NEU_UP, NEU_IN, GLASS, PLAIN }

    private final Kind kind;
    private final float radius;
    private final float spread; // shadow distance in px
    private int fill;
    private Bitmap cache;
    private int margin;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** Every live surface, so their cached bitmaps can be dropped while an app is in front. */
    private static final java.util.Set<Soft> LIVE =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Soft, Boolean>());

    /** Frees all cached surfaces; each redraws itself the next time it's shown. */
    public static void trimAll() {
        synchronized (LIVE) {
            for (Soft s : LIVE) s.cache = null;
        }
    }

    public Soft(Kind kind, float radiusPx, float spreadPx, int fill) {
        synchronized (LIVE) {
            LIVE.add(this);
        }
        this.kind = kind;
        this.radius = radiusPx;
        this.spread = spreadPx;
        this.fill = fill;
    }

    public void setFill(int color) {
        if (fill == color) return;
        fill = color;
        cache = null;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        cache = null;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        if (cache == null) cache = render(b.width(), b.height());
        canvas.drawBitmap(cache, b.left - margin, b.top - margin, paint);
    }

    private Bitmap render(int w, int h) {
        margin = kind == Kind.NEU_IN || kind == Kind.PLAIN ? 0 : Math.round(spread * 2.6f);
        Bitmap bmp = Bitmap.createBitmap(w + margin * 2, h + margin * 2, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        RectF r = new RectF(margin, margin, margin + w, margin + h);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        switch (kind) {
            case NEU_UP: {
                p.setMaskFilter(new BlurMaskFilter(spread * 1.2f, BlurMaskFilter.Blur.NORMAL));
                p.setColor(Ui.NEU_DARK);
                c.drawRoundRect(offset(r, spread, spread), radius, radius, p);
                p.setColor(Ui.NEU_LIGHT);
                c.drawRoundRect(offset(r, -spread, -spread), radius, radius, p);
                p.setMaskFilter(null);
                p.setColor(fill);
                c.drawRoundRect(r, radius, radius, p);
                break;
            }
            case NEU_IN: {
                p.setColor(fill);
                c.drawRoundRect(r, radius, radius, p);
                Path clip = new Path();
                clip.addRoundRect(r, radius, radius, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                drawInnerShadow(c, r, spread * 0.6f, Ui.NEU_DARK);
                drawInnerShadow(c, r, -spread * 0.6f, Ui.NEU_LIGHT);
                c.restore();
                break;
            }
            case GLASS: {
                p.setMaskFilter(new BlurMaskFilter(spread * 1.6f, BlurMaskFilter.Blur.NORMAL));
                p.setColor(Ui.DARK ? 0x66000000 : 0x2E28405A);
                c.drawRoundRect(offset(r, 0, spread * 1.1f), radius, radius, p);
                p.setMaskFilter(null);
                // Erase the shadow under the panel so the translucent fill shows the wallpaper, not grey.
                Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
                clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
                c.drawRoundRect(r, radius, radius, clear);
                p.setColor(fill);
                c.drawRoundRect(r, radius, radius, p);
                Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
                hi.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.46f,
                        Ui.DARK ? 0x1FFFFFFF : 0x61FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(r, radius, radius, hi);
                Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                border.setStyle(Paint.Style.STROKE);
                border.setStrokeWidth(Math.max(1.5f, spread * 0.12f));
                border.setColor(Ui.DARK ? 0x2EFFFFFF : 0x99FFFFFF);
                RectF in = new RectF(r);
                in.inset(border.getStrokeWidth() / 2, border.getStrokeWidth() / 2);
                c.drawRoundRect(in, radius, radius, border);
                break;
            }
            case PLAIN: {
                p.setColor(fill);
                c.drawRoundRect(r, radius, radius, p);
                break;
            }
        }
        return bmp;
    }

    /** A blurred ring offset by d inside the clip reads as the surface being pressed in. */
    private void drawInnerShadow(Canvas c, RectF r, float d, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(Math.abs(d) * 1.4f, BlurMaskFilter.Blur.NORMAL));
        Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        RectF outer = new RectF(r);
        outer.inset(-Math.abs(d) * 6, -Math.abs(d) * 6);
        ring.addRect(outer, Path.Direction.CW);
        ring.addRoundRect(offset(r, d, d), radius, radius, Path.Direction.CW);
        c.drawPath(ring, p);
    }

    private static RectF offset(RectF r, float dx, float dy) {
        RectF o = new RectF(r);
        o.offset(dx, dy);
        return o;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
