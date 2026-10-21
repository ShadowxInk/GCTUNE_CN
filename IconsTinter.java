package com.poc.gctune;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * Icon rendering for the recolor tab.
 *
 * A faithful port of Origin Icons (IconPackEngine.render / renderTinted):
 *
 *   1. the source icon — adaptive included — is drawn over the FULL canvas;
 *   2. it is desaturated;
 *   3. multiplied by the normalized tint (channel / max channel);
 *   4. written as PNG. The launcher scales the PNG into the icon slot as-is.
 *
 * Nothing else touches geometry. The full canvas is what keeps every icon the
 * same size (adaptive icons come out platform-masked = uniformly rounded for
 * free); the old pipeline's 24px inset and per-source base scales (x1.25 /
 * x0.78) are exactly what made icons jump in size and paint over their own
 * rounded corners.
 *
 * The optional compose layer (IconPack Studio style, bgEnabled) draws a
 * rounded background tile over the SAME full canvas and a normalised glyph on
 * top, so geometry stays identical to the plain pipeline.
 */
final class IconsTinter {

    static final int SIZE = 192;

    private IconsTinter() {
    }

    /** Flat render, Origin Icons look (defaults). */
    static Bitmap tint(Drawable icon, int tint) {
        return tint(icon, tint, 100, 0, 100);
    }

    /** Flat render with adjustable pipeline. */
    static Bitmap tint(Drawable icon, int tint, int desat, int lighten, int strength) {
        return compose(icon, tint, desat, lighten, strength, false, 0, 100, 0);
    }

    // Shape presets (Material 3 Expressive Comprehensive Collection)
    static final int SHAPE_ROUNDED = 0;    // Скругленный квадрат
    static final int SHAPE_SQUIRCLE = 1;   // Squircle / Суперэллипс
    static final int SHAPE_CIRCLE = 2;     // Круг
    static final int SHAPE_TEARDROP = 3;   // Капля (закругленный угол)
    static final int SHAPE_HEXAGON = 4;    // Шестигранник
    static final int SHAPE_OCTAGON = 5;    // Восьмигранник
    static final int SHAPE_STAR = 6;       // Цветок (12 лепестков)
    static final int SHAPE_PEBBLE = 7;     // Галька (асимметричная)
    static final int SHAPE_PENTAGON = 8;   // Пятигранник
    static final int SHAPE_DIAMOND = 9;    // Ромб / Diamond
    static final int SHAPE_CLOVER = 10;    // Клевер (4 лепестка)
    static final int SHAPE_ARCH = 11;      // Арка (закругленный верх)
    static final int SHAPE_SUNBURST = 12;  // Солнце (8 лучей)
    static final int SHAPE_HEART = 13;     // Сердце (M3 Expressive Heart)
    static final int SHAPE_TRIANGLE = 14;  // Треугольник скругленный
    static final int SHAPE_SEMI_CIRCLE = 15;// Полукруг (Flat bottom)
    static final int SHAPE_PILL_V = 16;    // Вертикальная капсула / Pill
    static final int SHAPE_BURST16 = 17;   // Острая звезда (16 лучей)
    static final int SHAPE_FLOWER16 = 18;  // Ромашка (16 круглых лепестков)
    static final int SHAPE_SCALLOP = 19;   // Волнистый круг (Scallop)
    static final int SHAPE_BUN = 20;       // Булочка (pill с выемкой снизу)
    static final int SHAPE_PIXEL = 21;     // Пиксельный значок (Pixelated badge)
    static final int SHAPE_CORNER_ROUND = 22; // Скруглен 1 угол (Quarter round)
    static final int SHAPE_SLANTED = 23;   // Скошенный параллелограмм (Slanted)

    /**
     * Builds a geometric Path for the icon background tile or mask within the given rectangle.
     */
    static Path buildShapePath(int shapeType, RectF rect, float radiusPct) {
        Path p = new Path();
        float w = rect.width();
        float h = rect.height();
        float size = Math.min(w, h);
        float r = size * (radiusPct / 100.0f) / 2.0f;
        float cx = rect.centerX();
        float cy = rect.centerY();

        switch (shapeType) {
            case SHAPE_CIRCLE:
                p.addCircle(cx, cy, size / 2f, Path.Direction.CW);
                break;

            case SHAPE_SQUIRCLE: {
                // True superellipse / smooth squircle approximation using cubic beziers
                float half = size / 2f;
                float c = half * 0.94f; // control point pull
                p.moveTo(cx, cy - half);
                p.cubicTo(cx + c, cy - half, cx + half, cy - c, cx + half, cy);
                p.cubicTo(cx + half, cy + c, cx + c, cy + half, cx, cy + half);
                p.cubicTo(cx - c, cy + half, cx - half, cy + c, cx - half, cy);
                p.cubicTo(cx - half, cy - c, cx - c, cy - half, cx, cy - half);
                p.close();
                break;
            }

            case SHAPE_TEARDROP: {
                float[] radii = new float[]{
                        r, r,
                        r, r,
                        Math.min(r, size * 0.15f), Math.min(r, size * 0.15f), // sharper bottom-right
                        r, r
                };
                p.addRoundRect(rect, radii, Path.Direction.CW);
                break;
            }

            case SHAPE_HEXAGON: {
                float rad = size / 2f;
                float cr = Math.max(2f, Math.min(rad * 0.35f, r));
                p = createRoundedPolygon(cx, cy, rad, 6, 0f, cr);
                break;
            }

            case SHAPE_OCTAGON: {
                float rad = size / 2f;
                float cr = Math.max(2f, Math.min(rad * 0.35f, r));
                p = createRoundedPolygon(cx, cy, rad, 8, 22.5f, cr);
                break;
            }

            case SHAPE_STAR: {
                // 12-lobed flower / badge
                float rOuter = size / 2f;
                float rInner = size * 0.40f;
                int points = 12;
                p = createFlowerBadge(cx, cy, rOuter, rInner, points);
                break;
            }

            case SHAPE_PEBBLE: {
                // Organic asymmetric pebble
                float[] radii = new float[]{
                        size * 0.45f, size * 0.45f,
                        size * 0.30f, size * 0.30f,
                        size * 0.48f, size * 0.48f,
                        size * 0.25f, size * 0.25f
                };
                p.addRoundRect(rect, radii, Path.Direction.CW);
                break;
            }

            case SHAPE_PENTAGON: {
                float rad = size / 2f;
                float cr = Math.max(2f, Math.min(rad * 0.35f, r));
                p = createRoundedPolygon(cx, cy, rad, 5, -18f, cr);
                break;
            }

            case SHAPE_DIAMOND: {
                float rad = size / 2f;
                float cr = Math.max(2f, Math.min(rad * 0.35f, r));
                p = createRoundedPolygon(cx, cy, rad, 4, 0f, cr);
                break;
            }

            case SHAPE_CLOVER: {
                // M3 4-Leaf Clover badge
                p = createFlowerBadge(cx, cy, size / 2f, size * 0.33f, 4);
                break;
            }

            case SHAPE_SUNBURST: {
                // M3 8-pointed sunny burst badge
                p = createFlowerBadge(cx, cy, size / 2f, size * 0.40f, 8);
                break;
            }

            case SHAPE_ARCH: {
                // Material 3 Architectural Arch (pill rounded top, subtle rounded bottom)
                float[] radii = new float[]{
                        size / 2f, size / 2f,
                        size / 2f, size / 2f,
                        Math.min(r, size * 0.15f), Math.min(r, size * 0.15f),
                        Math.min(r, size * 0.15f), Math.min(r, size * 0.15f)
                };
                p.addRoundRect(rect, radii, Path.Direction.CW);
                break;
            }

            case SHAPE_HEART: {
                // Smooth Material 3 Expressive Heart
                float top = rect.top, bottom = rect.bottom, left = rect.left, right = rect.right;
                p.moveTo(cx, top + size * 0.35f);
                p.cubicTo(cx - size * 0.3f, top, left, top + size * 0.3f, left, top + size * 0.55f);
                p.cubicTo(left, bottom - size * 0.2f, cx - size * 0.15f, bottom - size * 0.05f, cx, bottom);
                p.cubicTo(cx + size * 0.15f, bottom - size * 0.05f, right, bottom - size * 0.2f, right, top + size * 0.55f);
                p.cubicTo(right, top + size * 0.3f, cx + size * 0.3f, top, cx, top + size * 0.35f);
                p.close();
                break;
            }

            case SHAPE_TRIANGLE: {
                // Smooth rounded triangle (pointing up)
                float rad = size / 2f;
                float cr = Math.max(3f, Math.min(rad * 0.35f, r));
                p = createRoundedPolygon(cx, cy + size * 0.06f, rad * 0.95f, 3, -90f, cr);
                break;
            }

            case SHAPE_SEMI_CIRCLE: {
                // Semi-circle (arch top, completely flat bottom)
                float[] radii = new float[]{
                        size / 2f, size / 2f,
                        size / 2f, size / 2f,
                        0f, 0f,
                        0f, 0f
                };
                p.addRoundRect(new RectF(rect.left, rect.top + size * 0.25f, rect.right, rect.bottom), radii, Path.Direction.CW);
                break;
            }

            case SHAPE_PILL_V: {
                // Vertical capsule / pill
                float pw = size * 0.65f;
                RectF pillR = new RectF(cx - pw / 2f, rect.top, cx + pw / 2f, rect.bottom);
                p.addRoundRect(pillR, pw / 2f, pw / 2f, Path.Direction.CW);
                break;
            }

            case SHAPE_BURST16: {
                // 16-pointed sharp star burst
                p = createFlowerBadge(cx, cy, size / 2f, size * 0.30f, 16);
                break;
            }

            case SHAPE_FLOWER16: {
                // 16-lobed daisy / sunflower badge
                p = createFlowerBadge(cx, cy, size / 2f, size * 0.43f, 16);
                break;
            }

            case SHAPE_SCALLOP: {
                // 8-lobed wavy scallop
                p = createFlowerBadge(cx, cy, size / 2f, size * 0.42f, 8);
                break;
            }

            case SHAPE_BUN: {
                // Rounded bun with arched bottom cut
                float cr = size * 0.35f;
                p.moveTo(rect.left, rect.bottom);
                p.lineTo(rect.left, rect.top + cr);
                p.quadTo(rect.left, rect.top, rect.left + cr, rect.top);
                p.lineTo(rect.right - cr, rect.top);
                p.quadTo(rect.right, rect.top, rect.right, rect.top + cr);
                p.lineTo(rect.right, rect.bottom);
                p.quadTo(cx, rect.bottom - size * 0.25f, rect.left, rect.bottom);
                p.close();
                break;
            }

            case SHAPE_PIXEL: {
                // Stepped 8-bit / pixelated badge approximation
                p = createRoundedPolygon(cx, cy, size / 2f, 8, 22.5f, 0f);
                break;
            }

            case SHAPE_CORNER_ROUND: {
                // Only 1 corner rounded (quarter round leaf)
                float[] radii = new float[]{
                        0f, 0f,
                        size, size,
                        0f, 0f,
                        0f, 0f
                };
                p.addRoundRect(rect, radii, Path.Direction.CW);
                break;
            }

            case SHAPE_SLANTED: {
                // Slanted rounded parallelogram
                p.moveTo(rect.left + size * 0.2f, rect.top);
                p.lineTo(rect.right, rect.top);
                p.lineTo(rect.right - size * 0.2f, rect.bottom);
                p.lineTo(rect.left, rect.bottom);
                p.close();
                break;
            }

            case SHAPE_ROUNDED:
            default:
                p.addRoundRect(rect, r, r, Path.Direction.CW);
                break;
        }
        return p;
    }

    static Path buildShapePath(int shapeType, float size, float radiusPct) {
        return buildShapePath(shapeType, new RectF(0f, 0f, size, size), radiusPct);
    }

    private static Path createRoundedPolygon(float cx, float cy, float radius, int sides, float rotationDeg, float cornerRadius) {
        Path path = new Path();
        double rot = Math.toRadians(rotationDeg);
        float[][] pts = new float[sides][2];
        for (int i = 0; i < sides; i++) {
            double angle = rot + 2.0 * Math.PI * i / sides;
            pts[i][0] = (float) (cx + radius * Math.cos(angle));
            pts[i][1] = (float) (cy + radius * Math.sin(angle));
        }
        // Round the corners
        for (int i = 0; i < sides; i++) {
            int prev = (i + sides - 1) % sides;
            int next = (i + 1) % sides;
            float dx1 = pts[i][0] - pts[prev][0];
            float dy1 = pts[i][1] - pts[prev][1];
            float len1 = (float) Math.hypot(dx1, dy1);
            float dx2 = pts[next][0] - pts[i][0];
            float dy2 = pts[next][1] - pts[i][1];
            float len2 = (float) Math.hypot(dx2, dy2);

            float t1 = Math.min(cornerRadius, len1 * 0.45f);
            float t2 = Math.min(cornerRadius, len2 * 0.45f);

            float p1x = pts[i][0] - (dx1 / len1) * t1;
            float p1y = pts[i][1] - (dy1 / len1) * t1;
            float p2x = pts[i][0] + (dx2 / len2) * t2;
            float p2y = pts[i][1] + (dy2 / len2) * t2;

            if (i == 0) {
                path.moveTo(p1x, p1y);
            } else {
                path.lineTo(p1x, p1y);
            }
            path.quadTo(pts[i][0], pts[i][1], p2x, p2y);
        }
        path.close();
        return path;
    }

    private static Path createFlowerBadge(float cx, float cy, float rOuter, float rInner, int points) {
        Path path = new Path();
        int totalPts = points * 2;
        for (int i = 0; i < totalPts; i++) {
            double angle = 2.0 * Math.PI * i / totalPts;
            float r = (i % 2 == 0) ? rOuter : rInner;
            float x = (float) (cx + r * Math.cos(angle));
            float y = (float) (cy + r * Math.sin(angle));
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.close();
        return path;
    }

    /**
     * Full renderer with custom shape control.
     *
     * @param fgScale   glyph size in percent (15..200: from miniature 15% to massive oversized 200%)
     * @param shapeType SHAPE_ROUNDED, SHAPE_SQUIRCLE, SHAPE_CIRCLE, etc.
     */
    static Bitmap compose(Drawable icon, int tint, int desat, int lighten, int strength,
                          boolean bgEnabled, int bgColor, int fgScale, int radiusPct, int shapeType) {
        if (icon == null) return null;
        desat = clamp(desat);
        lighten = clamp(lighten);
        strength = clamp(strength);
        // Ultra-wide scaling support: 15% (tiny minimal) to 200% (oversized full-canvas badge)
        fgScale = Math.max(15, Math.min(200, fgScale));
        radiusPct = clamp(radiusPct);

        float iconSize = SIZE * (fgScale / 100.0f);
        float offset = (SIZE - iconSize) / 2.0f;
        RectF iconBounds = new RectF(offset, offset, offset + iconSize, offset + iconSize);
        Path shapePath = buildShapePath(shapeType, iconBounds, radiusPct);

        if (!bgEnabled) {
            Bitmap layer = drawIcon(icon, 0f, 0f, SIZE, SIZE);
            if (layer == null) return null;
            Bitmap tinted = tintBitmap(layer, tint, desat, lighten, strength);
            layer.recycle();
            if (tinted == null) return null;

            Rect bbox = new Rect();
            if (!alphaBBox(tinted, bbox, 10)) {
                bbox.set(0, 0, SIZE, SIZE);
            }

            // Scale tinted glyph to fit nicely inside iconBounds
            float s = iconSize / Math.max(bbox.width(), bbox.height());
            float w = bbox.width() * s;
            float h = bbox.height() * s;
            RectF dst = new RectF(SIZE / 2f - w / 2f, SIZE / 2f - h / 2f,
                    SIZE / 2f + w / 2f, SIZE / 2f + h / 2f);

            Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            try {
                Canvas c = new Canvas(out);
                c.clipPath(shapePath);
                Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                c.drawBitmap(tinted, bbox, dst, gp);
            } catch (Throwable t) {
                out.recycle();
                tinted.recycle();
                return null;
            }
            tinted.recycle();
            return out;
        }

        // ---- compose layer: styled background tile + foreground icon ----
        Drawable fgSource = icon;
        if (icon instanceof AdaptiveIconDrawable) {
            try {
                Drawable adaptiveFg = ((AdaptiveIconDrawable) icon).getForeground();
                if (adaptiveFg != null) fgSource = adaptiveFg;
            } catch (Throwable ignored) {
            }
        }

        Bitmap layer = drawIcon(fgSource, 0f, 0f, SIZE, SIZE);
        Rect bbox = new Rect();
        boolean ok = layer != null && alphaBBox(layer, bbox, 10)
                && bbox.width() >= SIZE * 0.15f && bbox.height() >= SIZE * 0.15f;
        if (!ok && fgSource != icon) {
            if (layer != null) layer.recycle();
            layer = drawIcon(icon, 0f, 0f, SIZE, SIZE);
            ok = layer != null && alphaBBox(layer, bbox, 10)
                    && bbox.width() >= SIZE * 0.10f && bbox.height() >= SIZE * 0.10f;
        }
        if (!ok) {
            if (layer != null) layer.recycle();
            layer = drawIcon(icon, 0f, 0f, SIZE, SIZE);
            if (layer == null) return null;
            bbox.set(0, 0, SIZE, SIZE);
        }

        Bitmap tinted = tintBitmap(layer, tint, desat, lighten, strength);
        if (layer != null) layer.recycle();
        if (tinted == null) return null;

        // Foreground glyph safe zone inside the styled tile (66.7% of tile)
        float glyphTarget = iconSize * 0.667f;
        float s = glyphTarget / Math.max(bbox.width(), bbox.height());
        float w = bbox.width() * s;
        float h = bbox.height() * s;
        RectF dst = new RectF(SIZE / 2f - w / 2f, SIZE / 2f - h / 2f,
                SIZE / 2f + w / 2f, SIZE / 2f + h / 2f);

        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(out);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(bgColor);
            c.drawPath(shapePath, bp);
            c.clipPath(shapePath);
            Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(tinted, bbox, dst, gp);
        } catch (Throwable t) {
            out.recycle();
            tinted.recycle();
            return null;
        }
        tinted.recycle();
        return out;
    }

    /** Compatibility overload for previous callers */
    static Bitmap compose(Drawable icon, int tint, int desat, int lighten, int strength,
                          boolean bgEnabled, int bgColor, int fgScale, int radiusPct) {
        return compose(icon, tint, desat, lighten, strength, bgEnabled, bgColor, fgScale, radiusPct, SHAPE_ROUNDED);
    }

    // ------------------------------------------------------------------ pipeline

    /** Draws the drawable into the given box of a fresh SIZE x SIZE bitmap. */
    private static Bitmap drawIcon(Drawable icon, float l, float t, float r, float b) {
        if (icon == null) return null;
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(bmp);
            icon.setBounds((int) l, (int) t, (int) r, (int) b);
            icon.draw(c);
        } catch (Throwable err) {
            bmp.recycle();
            return null;
        }
        return bmp;
    }

    /** Desaturate -> multiply by normalized tint -> lighten -> mix with original. */
    private static Bitmap tintBitmap(Bitmap src, int tint, int desat, int lighten, int strength) {
        Bitmap gray = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            Paint p1 = new Paint(1);
            ColorMatrix sat = new ColorMatrix();
            sat.setSaturation(1.0f - desat / 100.0f);
            p1.setColorFilter(new ColorMatrixColorFilter(sat));
            Canvas c1 = new Canvas(gray);
            c1.drawBitmap(src, 0.0f, 0.0f, p1);

            float r = Color.red(tint);
            float g = Color.green(tint);
            float b = Color.blue(tint);
            float max = Math.max(r, Math.max(g, b));
            max = Math.max(max, 1.0f);
            r /= max;
            g /= max;
            b /= max;

            Paint p2 = new Paint(1);
            p2.setColorFilter(new ColorMatrixColorFilter(new float[]{
                    r, 0f, 0f, 0f, 0f,
                    0f, g, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f}));

            Bitmap tinted = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            try {
                Canvas c2 = new Canvas(tinted);
                c2.drawBitmap(gray, 0.0f, 0.0f, p2);
                gray.recycle();

                if (lighten > 0) {
                    c2.drawColor(Color.argb(lighten * 255 / 100, 255, 255, 255),
                            PorterDuff.Mode.SRC_ATOP);
                }

                if (strength >= 100) return tinted;

                Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
                try {
                    Canvas c3 = new Canvas(out);
                    c3.drawBitmap(src, 0.0f, 0.0f, null);
                    Paint p4 = new Paint(1);
                    p4.setAlpha(strength * 255 / 100);
                    c3.drawBitmap(tinted, 0.0f, 0.0f, p4);
                } catch (Throwable t) {
                    out.recycle();
                    return tinted;
                }
                tinted.recycle();
                return out;
            } catch (Throwable t) {
                tinted.recycle();
                gray.recycle();
                return src;
            }
        } catch (Throwable t) {
            gray.recycle();
            return src;
        }
    }

    /** Alpha bounding box of the bitmap; false when nothing is drawn. */
    private static boolean alphaBBox(Bitmap bmp, Rect out, int minAlpha) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int minx = w, miny = h, maxx = -1, maxy = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            boolean hit = false;
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) >= minAlpha) {
                    if (x < minx) minx = x;
                    if (x > maxx) maxx = x;
                    hit = true;
                }
            }
            if (hit) {
                if (y < miny) miny = y;
                maxy = y;
            }
        }
        if (maxx < 0) return false;
        out.set(minx, miny, maxx + 1, maxy + 1);
        return true;
    }

    /**
     * Copies {@code src[bbox]} centred and scaled so its larger side equals
     * {@code targetMax} px — aspect ratio preserved, no cropping.
     */
    private static Bitmap fitTo(Bitmap src, Rect bbox, float targetMax) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float s = targetMax / Math.max(bbox.width(), bbox.height());
        float w = bbox.width() * s;
        float h = bbox.height() * s;
        RectF dst = new RectF(SIZE / 2f - w / 2f, SIZE / 2f - h / 2f,
                SIZE / 2f + w / 2f, SIZE / 2f + h / 2f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(src, bbox, dst, p);
        return out;
    }

    /**
     * Generates a monochrome mask bitmap (solid black inside shape, transparent outside)
     * matching the system mask format (OriginOS expects black silhouette on transparent background).
     */
    static Bitmap generateMask(int width, int height, int shapeType, int radiusPct, int fgScale) {
        return generateBg(width, height, shapeType, radiusPct, fgScale, 0xFF000000);
    }

    /**
     * Generates a shape-fitted background tile of given color.
     */
    static Bitmap generateBg(int width, int height, int shapeType, int radiusPct, int fgScale, int color) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scale = Math.max(15, Math.min(200, fgScale)) / 100.0f;
        float size = Math.min(width, height) * scale;
        float offsetW = (width - size) / 2.0f;
        float offsetH = (height - size) / 2.0f;
        RectF rect = new RectF(offsetW, offsetH, offsetW + size, offsetH + size);
        Path shapePath = buildShapePath(shapeType, rect, radiusPct);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        c.drawPath(shapePath, p);
        return out;
    }

    /**
     * Generates a miniature shape thumbnail bitmap with outline and subtle fill for UI selectors.
     */
    static Bitmap generateShapeThumbnail(int size, int shapeType, int radiusPct, int fillColor, int strokeColor) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float pad = size * 0.12f;
        RectF r = new RectF(pad, pad, size - pad, size - pad);
        Path path = buildShapePath(shapeType, r, radiusPct);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(fillColor);
        c.drawPath(path, fill);

        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(2f, size * 0.06f));
        stroke.setColor(strokeColor);
        c.drawPath(path, stroke);

        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }
}
