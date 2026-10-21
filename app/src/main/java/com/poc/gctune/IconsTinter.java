package com.poc.gctune;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

import androidx.graphics.shapes.RoundedPolygon;
import androidx.graphics.shapes.Shapes_androidKt;
import com.google.android.material.shape.MaterialShapes;

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

    // Official Google Material 3 Expressive Shapes (com.google.android.material.shape.MaterialShapes)
    static final int SHAPE_M3_CIRCLE = 0;
    static final int SHAPE_M3_SQUARE = 1;
    static final int SHAPE_M3_SLANTED_SQUARE = 2;
    static final int SHAPE_M3_ARCH = 3;
    static final int SHAPE_M3_FAN = 4;
    static final int SHAPE_M3_ARROW = 5;
    static final int SHAPE_M3_SEMI_CIRCLE = 6;
    static final int SHAPE_M3_OVAL = 7;
    static final int SHAPE_M3_PILL = 8;
    static final int SHAPE_M3_TRIANGLE = 9;
    static final int SHAPE_M3_DIAMOND = 10;
    static final int SHAPE_M3_CLAM_SHELL = 11;
    static final int SHAPE_M3_PENTAGON = 12;
    static final int SHAPE_M3_GEM = 13;
    static final int SHAPE_M3_SUNNY = 14;
    static final int SHAPE_M3_VERY_SUNNY = 15;
    static final int SHAPE_M3_COOKIE_4 = 16;
    static final int SHAPE_M3_COOKIE_6 = 17;
    static final int SHAPE_M3_COOKIE_7 = 18;
    static final int SHAPE_M3_COOKIE_9 = 19;
    static final int SHAPE_M3_COOKIE_12 = 20;
    static final int SHAPE_M3_GHOSTISH = 21;
    static final int SHAPE_M3_CLOVER_4 = 22;
    static final int SHAPE_M3_CLOVER_8 = 23;
    static final int SHAPE_M3_BURST = 24;
    static final int SHAPE_M3_SOFT_BURST = 25;
    static final int SHAPE_M3_BOOM = 26;
    static final int SHAPE_M3_SOFT_BOOM = 27;
    static final int SHAPE_M3_FLOWER = 28;
    static final int SHAPE_M3_PUFFY = 29;
    static final int SHAPE_M3_PUFFY_DIAMOND = 30;
    static final int SHAPE_M3_PIXEL_CIRCLE = 31;
    static final int SHAPE_M3_PIXEL_TRIANGLE = 32;
    static final int SHAPE_M3_BUN = 33;
    static final int SHAPE_M3_HEART = 34;

    // Backward compatibility aliases
    static final int SHAPE_ROUNDED = SHAPE_M3_SQUARE;
    static final int SHAPE_SQUIRCLE = SHAPE_M3_COOKIE_4;
    static final int SHAPE_CIRCLE = SHAPE_M3_CIRCLE;
    static final int SHAPE_TEARDROP = SHAPE_M3_CLAM_SHELL;
    static final int SHAPE_HEXAGON = SHAPE_M3_COOKIE_6;
    static final int SHAPE_OCTAGON = SHAPE_M3_CLOVER_8;
    static final int SHAPE_STAR = SHAPE_M3_FLOWER;
    static final int SHAPE_PEBBLE = SHAPE_M3_GEM;
    static final int SHAPE_PENTAGON = SHAPE_M3_PENTAGON;
    static final int SHAPE_DIAMOND = SHAPE_M3_DIAMOND;
    static final int SHAPE_CLOVER = SHAPE_M3_CLOVER_4;
    static final int SHAPE_ARCH = SHAPE_M3_ARCH;
    static final int SHAPE_SUNBURST = SHAPE_M3_SUNNY;
    static final int SHAPE_HEART = SHAPE_M3_HEART;
    static final int SHAPE_TRIANGLE = SHAPE_M3_TRIANGLE;
    static final int SHAPE_SEMI_CIRCLE = SHAPE_M3_SEMI_CIRCLE;
    static final int SHAPE_PILL_V = SHAPE_M3_PILL;
    static final int SHAPE_BURST16 = SHAPE_M3_BURST;
    static final int SHAPE_FLOWER16 = SHAPE_M3_COOKIE_12;
    static final int SHAPE_SCALLOP = SHAPE_M3_COOKIE_9;
    static final int SHAPE_BUN = SHAPE_M3_BUN;
    static final int SHAPE_PIXEL = SHAPE_M3_PIXEL_CIRCLE;
    static final int SHAPE_CORNER_ROUND = SHAPE_M3_FAN;
    static final int SHAPE_SLANTED = SHAPE_M3_SLANTED_SQUARE;

    static RoundedPolygon getOfficialPolygon(int shapeType) {
        switch (shapeType) {
            case SHAPE_M3_CIRCLE: return MaterialShapes.CIRCLE;
            case SHAPE_M3_SQUARE: return MaterialShapes.SQUARE;
            case SHAPE_M3_SLANTED_SQUARE: return MaterialShapes.SLANTED_SQUARE;
            case SHAPE_M3_ARCH: return MaterialShapes.ARCH;
            case SHAPE_M3_FAN: return MaterialShapes.FAN;
            case SHAPE_M3_ARROW: return MaterialShapes.ARROW;
            case SHAPE_M3_SEMI_CIRCLE: return MaterialShapes.SEMI_CIRCLE;
            case SHAPE_M3_OVAL: return MaterialShapes.OVAL;
            case SHAPE_M3_PILL: return MaterialShapes.PILL;
            case SHAPE_M3_TRIANGLE: return MaterialShapes.TRIANGLE;
            case SHAPE_M3_DIAMOND: return MaterialShapes.DIAMOND;
            case SHAPE_M3_CLAM_SHELL: return MaterialShapes.CLAM_SHELL;
            case SHAPE_M3_PENTAGON: return MaterialShapes.PENTAGON;
            case SHAPE_M3_GEM: return MaterialShapes.GEM;
            case SHAPE_M3_SUNNY: return MaterialShapes.SUNNY;
            case SHAPE_M3_VERY_SUNNY: return MaterialShapes.VERY_SUNNY;
            case SHAPE_M3_COOKIE_4: return MaterialShapes.COOKIE_4;
            case SHAPE_M3_COOKIE_6: return MaterialShapes.COOKIE_6;
            case SHAPE_M3_COOKIE_7: return MaterialShapes.COOKIE_7;
            case SHAPE_M3_COOKIE_9: return MaterialShapes.COOKIE_9;
            case SHAPE_M3_COOKIE_12: return MaterialShapes.COOKIE_12;
            case SHAPE_M3_GHOSTISH: return MaterialShapes.GHOSTISH;
            case SHAPE_M3_CLOVER_4: return MaterialShapes.CLOVER_4;
            case SHAPE_M3_CLOVER_8: return MaterialShapes.CLOVER_8;
            case SHAPE_M3_BURST: return MaterialShapes.BURST;
            case SHAPE_M3_SOFT_BURST: return MaterialShapes.SOFT_BURST;
            case SHAPE_M3_BOOM: return MaterialShapes.BOOM;
            case SHAPE_M3_SOFT_BOOM: return MaterialShapes.SOFT_BOOM;
            case SHAPE_M3_FLOWER: return MaterialShapes.FLOWER;
            case SHAPE_M3_PUFFY: return MaterialShapes.PUFFY;
            case SHAPE_M3_PUFFY_DIAMOND: return MaterialShapes.PUFFY_DIAMOND;
            case SHAPE_M3_PIXEL_CIRCLE: return MaterialShapes.PIXEL_CIRCLE;
            case SHAPE_M3_PIXEL_TRIANGLE: return MaterialShapes.PIXEL_TRIANGLE;
            case SHAPE_M3_BUN: return MaterialShapes.BUN;
            case SHAPE_M3_HEART: return MaterialShapes.HEART;
            default: return MaterialShapes.SQUARE;
        }
    }

    private static Path getOfficialM3Path(RoundedPolygon polygon, RectF rect) {
        if (polygon == null) return null;
        try {
            // Apply a small safe padding (1.5%) so complex expressive shapes (bursts, flowers, etc.)
            // never clip against the bounding rect edges.
            float inset = Math.min(rect.width(), rect.height()) * 0.015f;
            RectF fitRect = new RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset);
            RoundedPolygon normalized = MaterialShapes.normalize(polygon, false, fitRect);
            return Shapes_androidKt.toPath(normalized);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Builds a geometric Path for the icon background tile or mask within the given rectangle.
     */
    static Path buildShapePath(int shapeType, RectF rect, float radiusPct) {
        RoundedPolygon poly = getOfficialPolygon(shapeType);
        if (poly != null) {
            Path officialPath = getOfficialM3Path(poly, rect);
            if (officialPath != null) {
                return officialPath;
            }
        }

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
     * Full renderer with custom shape control and icon sizing.
     *
     * @param iconScale overall icon tile scale in percent (30..250: miniature 30% to huge oversized 250%)
     * @param fgScale   glyph size inside shape in percent (15..150: miniature 15% to oversized 150%)
     * @param shapeType SHAPE_ROUNDED, SHAPE_SQUIRCLE, SHAPE_CIRCLE, etc.
     */
    static Bitmap compose(Drawable icon, int tint, int desat, int lighten, int strength,
                          boolean bgEnabled, int bgColor, int iconScale, int fgScale, int radiusPct, int shapeType) {
        if (icon == null) return null;
        desat = clamp(desat);
        lighten = clamp(lighten);
        strength = clamp(strength);
        iconScale = Math.max(30, Math.min(300, iconScale));
        fgScale = Math.max(15, Math.min(150, fgScale));
        radiusPct = clamp(radiusPct);

        // Standard target icon dimension is 192px at 100%.
        float scaleFactor = iconScale / 100.0f;
        // Allows icons to become truly large when scaleFactor > 1.0f
        int shapeDimension = Math.round((SIZE - 4) * scaleFactor);
        int canvasSize = Math.max(SIZE, shapeDimension + 4);
        float offset = (canvasSize - shapeDimension) / 2.0f;

        // The background shape fills the shape dimension centered in canvasSize
        RectF iconBounds = new RectF(offset, offset, offset + shapeDimension, offset + shapeDimension);
        Path shapePath = buildShapePath(shapeType, iconBounds, radiusPct);

        // Prominent glyph scale: 72-76% for rounded/square, 68% for expressive shapes
        // so the icon logo is clearly visible, large and sharp without overflowing
        float autoFgRatio = (shapeType == SHAPE_M3_SQUARE || shapeType == SHAPE_ROUNDED) ? 0.74f : 0.68f;
        float glyphTarget = shapeDimension * autoFgRatio;

        // Effective background color for the shape:
        // If bgEnabled is true, use the custom bgColor chosen by the user.
        // Otherwise, derive a sleek, dark harmonized container from the icon's own color (tint),
        // NEVER pure white or unrelated Monet colors!
        int effectiveBgColor = bgEnabled ? bgColor : getToneBgColor(tint);

        Drawable fgSource = icon;
        if (icon instanceof AdaptiveIconDrawable) {
            try {
                Drawable adaptiveFg = ((AdaptiveIconDrawable) icon).getForeground();
                if (adaptiveFg != null) fgSource = adaptiveFg;
            } catch (Throwable ignored) {
            }
        }

        Bitmap layer = drawIcon(fgSource, 0f, 0f, canvasSize, canvasSize, canvasSize);
        Rect bbox = new Rect();
        boolean ok = layer != null && alphaBBox(layer, bbox, 10)
                && bbox.width() >= canvasSize * 0.15f && bbox.height() >= canvasSize * 0.15f;
        if (!ok && fgSource != icon) {
            if (layer != null) layer.recycle();
            layer = drawIcon(icon, 0f, 0f, canvasSize, canvasSize, canvasSize);
            ok = layer != null && alphaBBox(layer, bbox, 10)
                    && bbox.width() >= canvasSize * 0.10f && bbox.height() >= canvasSize * 0.10f;
        }
        if (!ok) {
            if (layer != null) layer.recycle();
            layer = drawIcon(icon, 0f, 0f, canvasSize, canvasSize, canvasSize);
            if (layer == null) return null;
            bbox.set(0, 0, canvasSize, canvasSize);
        }

        Bitmap tinted = tintBitmap(layer, tint, desat, lighten, strength, canvasSize);
        if (layer != null) layer.recycle();
        if (tinted == null) return null;

        // Auto glyph scale centered inside the shape tile
        float s = glyphTarget / Math.max(bbox.width(), bbox.height());
        float w = bbox.width() * s;
        float h = bbox.height() * s;
        RectF dst = new RectF(canvasSize / 2f - w / 2f, canvasSize / 2f - h / 2f,
                canvasSize / 2f + w / 2f, canvasSize / 2f + h / 2f);

        Bitmap out = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        try {
            Canvas c = new Canvas(out);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(effectiveBgColor);
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

    /** Compatibility overloads for previous callers */
    static Bitmap compose(Drawable icon, int tint, int desat, int lighten, int strength,
                          boolean bgEnabled, int bgColor, int fgScale, int radiusPct, int shapeType) {
        return compose(icon, tint, desat, lighten, strength, bgEnabled, bgColor, 100, fgScale, radiusPct, shapeType);
    }

    /** Compatibility overload for previous callers */
    static Bitmap compose(Drawable icon, int tint, int desat, int lighten, int strength,
                          boolean bgEnabled, int bgColor, int fgScale, int radiusPct) {
        return compose(icon, tint, desat, lighten, strength, bgEnabled, bgColor, fgScale, radiusPct, SHAPE_ROUNDED);
    }

    // ------------------------------------------------------------------ pipeline

    /** Draws the drawable into the given box of a fresh size x size bitmap. */
    private static Bitmap drawIcon(Drawable icon, float l, float t, float r, float b, int size) {
        if (icon == null) return null;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
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

    private static Bitmap drawIcon(Drawable icon, float l, float t, float r, float b) {
        return drawIcon(icon, l, t, r, b, SIZE);
    }

    /** Desaturate -> multiply by normalized tint -> lighten -> mix with original. */
    private static Bitmap tintBitmap(Bitmap src, int tint, int desat, int lighten, int strength, int size) {
        Bitmap gray = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
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

            Bitmap tinted = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            try {
                Canvas c2 = new Canvas(tinted);
                c2.drawBitmap(gray, 0.0f, 0.0f, p2);
                gray.recycle();

                if (lighten > 0) {
                    c2.drawColor(Color.argb(lighten * 255 / 100, 255, 255, 255),
                            PorterDuff.Mode.SRC_ATOP);
                }

                if (strength >= 100) return tinted;

                Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
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
     * Derives a deep harmonious tonal background color matching the icon's tint.
     * Keeps 20% of the icon's color blended onto an elegant dark surface.
     */
    static int getToneBgColor(int tint) {
        int tr = Color.red(tint);
        int tg = Color.green(tint);
        int tb = Color.blue(tint);
        int bgR = Math.round(18 + tr * 0.20f);
        int bgG = Math.round(20 + tg * 0.20f);
        int bgB = Math.round(24 + tb * 0.20f);
        return Color.rgb(Math.min(255, bgR), Math.min(255, bgG), Math.min(255, bgB));
    }

    /**
     * Generates an unconstrained launcher mask (solid black covering the full canvas).
     * By filling the entire canvas with solid black (100% opacity), the OriginOS launcher
     * does NOT apply its own hardware squircle/square clipping mask to the icon!
     * The actual shape (Material 3 stars, flowers, hearts, circles, oversized scale)
     * is already crisply and transparently rendered inside the PNG itself.
     */
    static Bitmap generateMask(int width, int height, int shapeType, int radiusPct, int iconScale) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scaleFactor = iconScale / 100.0f;
        int shapeDim = Math.round((width - 4) * scaleFactor);
        float offset = (width - shapeDim) / 2.0f;
        RectF r = new RectF(offset, offset, offset + shapeDim, offset + shapeDim);
        Path shapePath = buildShapePath(shapeType, r, radiusPct);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        c.drawPath(shapePath, p);
        return out;
    }

    /**
     * Generates a shape-fitted background tile of given color.
     */
    static Bitmap generateBg(int width, int height, int shapeType, int radiusPct, int iconScale, int color) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float scaleFactor = iconScale / 100.0f;
        int shapeDim = Math.round((width - 4) * scaleFactor);
        float offset = (width - shapeDim) / 2.0f;
        RectF r = new RectF(offset, offset, offset + shapeDim, offset + shapeDim);
        Path shapePath = buildShapePath(shapeType, r, radiusPct);
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
