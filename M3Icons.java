package com.poc.gctune;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Hand-drawn Material Symbols Rounded style icons.
 * Everything is drawn on the standard 24dp grid with round caps/joins,
 * so no XML resources are needed and the build script stays unchanged.
 */
final class M3Icons {

    private M3Icons() {}

    // ------------------------------------------------------------------ icon
    static final class Icon extends Drawable {

        private final Path strokes = new Path();
        private final Path fills = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int intrinsic;

        private Icon(int color, float strokeWidth, int intrinsic) {
            this.intrinsic = intrinsic;
            paint.setColor(color);
            paint.setStrokeWidth(strokeWidth);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        private Icon stroke(Path p) { strokes.addPath(p); return this; }

        private Icon fill(Path p) { fills.addPath(p); return this; }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float s = Math.min(b.width(), b.height()) / 24f;
            canvas.save();
            canvas.translate(b.left + (b.width() - 24f * s) * 0.5f,
                    b.top + (b.height() - 24f * s) * 0.5f);
            canvas.scale(s, s);
            if (!fills.isEmpty()) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(fills, paint);
            }
            if (!strokes.isEmpty()) {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawPath(strokes, paint);
            }
            canvas.restore();
        }

        @Override
        public void setTint(int color) { paint.setColor(color); invalidateSelf(); }

        @Override
        public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }

        @Override
        public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }

        @Override
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override
        public int getIntrinsicWidth() { return intrinsic; }

        @Override
        public int getIntrinsicHeight() { return intrinsic; }
    }

    // ------------------------------------------------------------- path tools
    /** Rounded polygon / polyline. xy = {x0,y0,x1,y1,...}, r = corner radius (grid units). */
    static Path poly(float[] xy, float r, boolean closed) {
        int n = xy.length / 2;
        Path p = new Path();
        if (n < 2) return p;

        float[] ax = new float[n], ay = new float[n];
        float[] bx = new float[n], by = new float[n];

        for (int i = 0; i < n; i++) {
            int prev = closed ? (i + n - 1) % n : Math.max(i - 1, 0);
            int next = closed ? (i + 1) % n : Math.min(i + 1, n - 1);

            float dx = xy[i * 2] - xy[prev * 2];
            float dy = xy[i * 2 + 1] - xy[prev * 2 + 1];
            float len = (float) Math.hypot(dx, dy);
            float t = len > 0.01f ? Math.min(r, len * 0.5f) / len : 0f;
            bx[i] = xy[i * 2] - dx * t;
            by[i] = xy[i * 2 + 1] - dy * t;

            float dx2 = xy[next * 2] - xy[i * 2];
            float dy2 = xy[next * 2 + 1] - xy[i * 2 + 1];
            float len2 = (float) Math.hypot(dx2, dy2);
            float t2 = len2 > 0.01f ? Math.min(r, len2 * 0.5f) / len2 : 0f;
            ax[i] = xy[i * 2] + dx2 * t2;
            ay[i] = xy[i * 2 + 1] + dy2 * t2;
        }

        if (closed) {
            p.moveTo(ax[0], ay[0]);
            for (int i = 0; i < n; i++) {
                int next = (i + 1) % n;
                p.lineTo(bx[next], by[next]);
                p.quadTo(xy[next * 2], xy[next * 2 + 1], ax[next], ay[next]);
            }
            p.close();
        } else {
            p.moveTo(xy[0], xy[1]);
            p.lineTo(bx[1], by[1]);
            for (int i = 1; i < n - 1; i++) {
                p.quadTo(xy[i * 2], xy[i * 2 + 1], ax[i], ay[i]);
                p.lineTo(bx[i + 1], by[i + 1]);
            }
            p.lineTo(xy[(n - 1) * 2], xy[(n - 1) * 2 + 1]);
        }
        return p;
    }

    private static Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private static Path rect(float l, float t, float r, float b, float radius) {
        Path p = new Path();
        p.addRoundRect(new RectF(l, t, r, b), radius, radius, Path.Direction.CW);
        return p;
    }

    private static Path circle(float cx, float cy, float radius) {
        Path p = new Path();
        p.addOval(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                Path.Direction.CW);
        return p;
    }

    // ----------------------------------------------------------------- icons
    /** Import script. */
    static Icon folder(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx).stroke(poly(new float[]{
                3.6f, 19.4f,   // bottom left
                3.6f, 6.7f,    // top left
                9.4f, 6.7f,    // tab top right
                11.6f, 9.1f,   // slope into the body
                20.4f, 9.1f,   // body top right
                20.4f, 19.4f   // bottom right
        }, 1.6f, true));
    }

    /** Clear console. */
    static Icon trash(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(line(4.9f, 7.5f, 19.1f, 7.5f))
                .stroke(line(9.8f, 4.7f, 14.2f, 4.7f))
                .stroke(poly(new float[]{
                        7.1f, 9.5f, 16.9f, 9.5f, 15.7f, 19.7f, 8.3f, 19.7f
                }, 1.4f, true))
                .stroke(line(10.6f, 12.0f, 10.6f, 16.5f))
                .stroke(line(13.4f, 12.0f, 13.4f, 16.5f));
    }

    /** Paste from clipboard. */
    static Icon clipboard(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(rect(5.1f, 6.7f, 18.9f, 20.3f, 2.4f))
                .stroke(rect(9.3f, 3.6f, 14.7f, 6.7f, 1.5f));
    }

    /** Settings (tune sliders). */
    static Icon tune(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(line(3.7f, 7.4f, 20.3f, 7.4f))
                .stroke(line(3.7f, 12.0f, 20.3f, 12.0f))
                .stroke(line(3.7f, 16.6f, 20.3f, 16.6f))
                .fill(circle(9.0f, 7.4f, 2.3f))
                .fill(circle(15.2f, 12.0f, 2.3f))
                .fill(circle(7.6f, 16.6f, 2.3f));
    }

    static Icon play(int color, int sizePx) {
        return new Icon(color, 0f, sizePx)
                .fill(poly(new float[]{7.7f, 5.5f, 18.5f, 12f, 7.7f, 18.5f}, 2.2f, true));
    }

    static Icon stop(int color, int sizePx) {
        return new Icon(color, 0f, sizePx).fill(rect(6.9f, 6.9f, 17.1f, 17.1f, 3f));
    }

    static Icon terminal(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(rect(3.1f, 5.5f, 20.9f, 18.5f, 3f))
                .stroke(poly(new float[]{7.7f, 10f, 10.7f, 12.6f, 7.7f, 15.2f}, 1.2f, false))
                .stroke(line(13.5f, 15.3f, 16.5f, 15.3f));
    }

    static Icon chevron(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(poly(new float[]{9.7f, 6f, 15f, 12f, 9.7f, 18f}, 1.8f, false));
    }

    static Icon arrowBack(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(line(19.2f, 12f, 6.1f, 12f))
                .stroke(poly(new float[]{11.4f, 6.8f, 6.1f, 12f, 11.4f, 17.2f}, 1.8f, false));
    }

    static Icon check(int color, int sizePx) {
        return new Icon(color, 1.9f, sizePx)
                .stroke(poly(new float[]{5.2f, 12.6f, 9.6f, 17f, 18.8f, 7.4f}, 2f, false));
    }

    /** Theme / palette (bottom nav). */
    static Icon palette(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(poly(new float[]{
                        12f, 3.4f, 20.6f, 8.2f, 20.6f, 15.8f, 12f, 20.6f, 3.4f, 15.8f, 3.4f, 8.2f
                }, 2.4f, true))
                .fill(circle(8.6f, 9.7f, 1.7f))
                .fill(circle(15.4f, 9.7f, 1.7f))
                .fill(circle(9.7f, 15.3f, 1.7f))
                .fill(circle(15.0f, 15.5f, 1.7f));
    }

    /** Arrow pointing up-right: "open the game mode" hint. */
    static Icon arrowUpRight(int color, int sizePx) {
        return new Icon(color, 1.9f, sizePx)
                .stroke(line(6.4f, 17.6f, 17.6f, 6.4f))
                .stroke(poly(new float[]{9.4f, 6.4f, 17.6f, 6.4f, 17.6f, 14.6f}, 2f, false));
    }

    /** Filled status dot. */
    static Icon dot(int color, int sizePx) {
        return new Icon(color, 0f, sizePx).fill(circle(12f, 12f, 5.4f));
    }

    /** Icon grid (bottom nav). */
    static Icon grid(int color, int sizePx) {
        return new Icon(color, 0f, sizePx)
                .fill(rect(3.6f, 3.6f, 10.4f, 10.4f, 1.6f))
                .fill(rect(13.6f, 3.6f, 20.4f, 10.4f, 1.6f))
                .fill(rect(3.6f, 13.6f, 10.4f, 20.4f, 1.6f))
                .fill(rect(13.6f, 13.6f, 20.4f, 20.4f, 1.6f));
    }

    /** Hollow status dot. */
    static Icon dotHollow(int color, int sizePx) {
        return new Icon(color, 3f, sizePx).stroke(circle(12f, 12f, 5.6f));
    }

    /** Three dots horizontal ("Прочее" tab). */
    static Icon more(int color, int sizePx) {
        return new Icon(color, 0f, sizePx)
                .fill(circle(5.6f, 12f, 2f))
                .fill(circle(12f, 12f, 2f))
                .fill(circle(18.4f, 12f, 2f));
    }

    /** Font "T" icon. */
    static Icon font(int color, int sizePx) {
        return new Icon(color, 2.2f, sizePx)
                .stroke(line(5.0f, 5.0f, 19.0f, 5.0f))
                .stroke(line(12.0f, 5.0f, 12.0f, 19.5f))
                .stroke(line(8.5f, 19.5f, 15.5f, 19.5f));
    }

    /** Palette/brush customization icon. */
    static Icon brush(int color, int sizePx) {
        return palette(color, sizePx);
    }

    /** Shield / protection icon. */
    static Icon shield(int color, int sizePx) {
        return new Icon(color, 1.8f, sizePx)
                .stroke(poly(new float[]{
                        12f, 3.6f,
                        19.2f, 6.4f,
                        19.2f, 13.2f,
                        12f, 20.4f,
                        4.8f, 13.2f,
                        4.8f, 6.4f
                }, 2.2f, true));
    }
}
