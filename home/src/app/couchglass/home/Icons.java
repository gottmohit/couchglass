package app.couchglass.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;

/** Outline icons from the design, drawn on a 24-unit grid. */
public final class Icons {
    public static final int INPUT = 0, WIFI = 1, SETTINGS = 2, CAST = 3, MOON = 4, PICTURE = 5, SOUND = 6, POWER = 7, MIC = 8;

    private Icons() {
    }

    public static View view(Context c, int icon, int sizePx, int color) {
        View v = new IconView(c, icon, color);
        v.setLayoutParams(new LinearLayout.LayoutParams(sizePx, sizePx));
        return v;
    }

    private static class IconView extends View {
        private final int icon;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconView(Context c, int icon, int color) {
            super(c);
            this.icon = icon;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = Math.min(getWidth(), getHeight()) / 24f;
            c.save();
            c.scale(k, k);
            p.setStrokeWidth(1.9f);
            Path path = new Path();
            switch (icon) {
                case INPUT:
                    c.drawRoundRect(new RectF(3, 5, 21, 17), 2, 2, p);
                    path.moveTo(8, 21);
                    path.lineTo(16, 21);
                    path.moveTo(12, 17);
                    path.lineTo(12, 21);
                    c.drawPath(path, p);
                    break;
                case WIFI:
                    c.drawArc(new RectF(1, 3, 23, 25), 215, 110, false, p);
                    c.drawArc(new RectF(4.5f, 7.5f, 19.5f, 22.5f), 215, 110, false, p);
                    c.drawArc(new RectF(8, 12, 16, 20), 215, 110, false, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(12, 19.6f, 1.3f, p);
                    p.setStyle(Paint.Style.STROKE);
                    break;
                case SETTINGS:
                    c.drawCircle(12, 12, 3.2f, p);
                    c.drawCircle(12, 12, 7.2f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI / 4 * i;
                        float x1 = 12 + (float) Math.cos(a) * 7.2f, y1 = 12 + (float) Math.sin(a) * 7.2f;
                        float x2 = 12 + (float) Math.cos(a) * 10f, y2 = 12 + (float) Math.sin(a) * 10f;
                        c.drawLine(x1, y1, x2, y2, p);
                    }
                    break;
                case CAST:
                    path.moveTo(2, 8);
                    path.lineTo(2, 6);
                    path.arcTo(new RectF(2, 4, 6, 8), 180, 90);
                    path.lineTo(20, 4);
                    path.arcTo(new RectF(18, 4, 22, 8), 270, 90);
                    path.lineTo(22, 18);
                    path.arcTo(new RectF(18, 16, 22, 20), 0, 90);
                    path.lineTo(14, 20);
                    c.drawPath(path, p);
                    c.drawArc(new RectF(-6, 12, 10, 28), 270, 90, false, p);
                    c.drawArc(new RectF(-2, 16, 6, 24), 270, 90, false, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(2.4f, 19.6f, 1.3f, p);
                    p.setStyle(Paint.Style.STROKE);
                    break;
                case MOON:
                    path.moveTo(20, 14.5f);
                    path.arcTo(new RectF(3, 3, 21, 21), -10, 300, false);
                    path.cubicTo(9, 7, 12, 17, 20, 14.5f);
                    c.drawPath(path, p);
                    break;
                case PICTURE:
                    c.drawRoundRect(new RectF(3, 5, 21, 17), 2, 2, p);
                    path.moveTo(5.5f, 15);
                    path.lineTo(10, 10.5f);
                    path.lineTo(13, 13.5f);
                    path.lineTo(15, 11.5f);
                    path.lineTo(18.5f, 15);
                    path.moveTo(9, 21);
                    path.lineTo(15, 21);
                    c.drawPath(path, p);
                    c.drawCircle(16, 8.5f, 1.2f, p);
                    break;
                case SOUND:
                    path.moveTo(4, 9.5f);
                    path.lineTo(7.5f, 9.5f);
                    path.lineTo(12, 5.5f);
                    path.lineTo(12, 18.5f);
                    path.lineTo(7.5f, 14.5f);
                    path.lineTo(4, 14.5f);
                    path.close();
                    c.drawPath(path, p);
                    c.drawArc(new RectF(11, 8, 19, 16), -50, 100, false, p);
                    c.drawArc(new RectF(10, 4.5f, 23, 19.5f), -50, 100, false, p);
                    break;
                case POWER:
                    c.drawArc(new RectF(4, 4.5f, 20, 20.5f), -60, 300, false, p);
                    c.drawLine(12, 3, 12, 11.5f, p);
                    break;
                case MIC:
                    c.drawRoundRect(new RectF(9, 3, 15, 14), 3, 3, p);
                    c.drawArc(new RectF(5.5f, 6, 18.5f, 17.5f), 0, 180, false, p);
                    c.drawLine(12, 17.5f, 12, 21, p);
                    c.drawLine(9, 21, 15, 21, p);
                    break;
                default:
                    break;
            }
            c.restore();
        }
    }
}
