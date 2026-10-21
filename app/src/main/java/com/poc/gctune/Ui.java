package com.poc.gctune;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared Material 3 Expressive palette and view helpers. */
final class Ui {

    private Ui() {}

    // Material 3 Expressive Dark Palette (Deep AMOLED Graphite + Vivid Mint & Teal Accents)
    static final int BG = 0xFF0D0F12;              // surface / background: deep expressive obsidian
    static final int SURFACE = 0xFF171A20;         // surfaceContainer: rich tonal card surface
    static final int SURFACE_HIGH = 0xFF222630;    // surfaceContainerHigh: elevated interactive components
    static final int PRIMARY = 0xFF1D3528;         // primaryContainer: vibrant tonal fill
    static final int PRIMARY_PRESSED = 0xFF2A4E3B;
    static final int ACCENT = 0xFF6CE5A8;          // primary: vivid expressive spring mint
    static final int SECONDARY = 0xFF9AE5D8;        // secondary / tertiary: expressive bright aqua
    static final int BORDER = 0xFF2F4639;          // outline: subtle crisp outline
    static final int BORDER_SOFT = 0x336CE5A8;     // outlineVariant: gentle luminescent border
    static final int INPUT_BG = 0xFF12151B;        // surfaceContainerLow: sunken field background
    static final int TEXT = 0xFFE9ECF0;            // onSurface: high-contrast clean typography
    static final int MUTED = 0xFF9399A5;           // onSurfaceVariant: balanced secondary typography
    static final int PROMPT = 0xFF6CE5A8;          // shell prompt / soft mint
    static final int ERROR = 0xFFFF7B7B;           // expressive crisp error red

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static int sp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable shape(Context c, int bg, float radius, int stroke, float strokeW) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(c, radius));
        if (strokeW > 0) gd.setStroke(dp(c, strokeW), stroke);
        return gd;
    }

    /** Expressive Pill / Capsule shape (radius = 999dp) */
    static GradientDrawable pillShape(Context c, int bg, int stroke, float strokeW) {
        return shape(c, bg, 999f, stroke, strokeW);
    }

    /**
     * Material ripple background: rounded content over a masked ripple halo.
     * {@code bg} is the resting fill (0 for transparent surfaces), {@code highlight}
     * is the ripple paint (white on filled buttons, accent-tinted on dark cards).
     */
    static RippleDrawable ripple(Context c, int bg, float radius, int highlight) {
        return new RippleDrawable(
                new ColorStateList(new int[][]{{}}, new int[]{highlight}),
                shape(c, bg, radius, 0, 0),
                shape(c, 0xFFFFFFFF, radius, 0, 0));
    }

    /** Expressive pill ripple for floating tabs, action chips, and pill buttons */
    static RippleDrawable pillRipple(Context c, int bg, int highlight) {
        return new RippleDrawable(
                new ColorStateList(new int[][]{{}}, new int[]{highlight}),
                pillShape(c, bg, 0, 0),
                pillShape(c, 0xFFFFFFFF, 0, 0));
    }

    /** Short tick on interactive taps (respects the system haptic setting). */
    static void tap(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    static TextView text(Context c, String s, float sizeSp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        return t;
    }

    static TextView label(Context c, String s, float sizeSp, int color) {
        TextView t = text(c, s, sizeSp, color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Uppercase letterspaced card/section caption with subtle M3 Expressive tracking. */
    static TextView section(Context c, String s) {
        TextView t = label(c, s.toUpperCase(java.util.Locale.ROOT), 11f, MUTED);
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** Expressive status badge / pill chip */
    static TextView badge(Context c, String s, int bg, int textColor) {
        TextView t = label(c, s, 11f, textColor);
        t.setBackground(pillShape(c, bg, 0, 0));
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 4));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    static LinearLayout row(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    static LinearLayout col(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setOrientation(LinearLayout.VERTICAL);
        return ll;
    }

    static LinearLayout.LayoutParams grow() {
        return new LinearLayout.LayoutParams(0, 1, 1f);
    }

    /**
     * Styles an official MaterialSwitch to match GC-Tune Material 3 Expressive theme.
     */
    static void applyM3Switch(com.google.android.material.materialswitch.MaterialSwitch sw) {
        if (sw == null) return;
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] thumbColors = new int[]{BG, MUTED};
        int[] trackColors = new int[]{ACCENT, SURFACE_HIGH};
        sw.setThumbTintList(new android.content.res.ColorStateList(states, thumbColors));
        sw.setTrackTintList(new android.content.res.ColorStateList(states, trackColors));
        sw.setTrackDecorationTintList(new android.content.res.ColorStateList(states, new int[]{0, BORDER}));
    }

    /**
     * Styles an Android Switch to strict Material 3 Expressive standards:
     * High-contrast capsule track, prominent pill thumb, smooth haptic reaction.
     */
    static void applyM3Switch(android.widget.Switch sw) {
        if (sw == null) return;
        Context c = sw.getContext();
        sw.setShowText(false);

        // M3 Expressive Track: 32dp height capsule
        GradientDrawable onTrack = pillShape(c, ACCENT, 0, 0);
        GradientDrawable offTrack = pillShape(c, SURFACE_HIGH, BORDER, 1.5f);
        android.graphics.drawable.StateListDrawable trackDrawable = new android.graphics.drawable.StateListDrawable();
        trackDrawable.addState(new int[]{android.R.attr.state_checked}, onTrack);
        trackDrawable.addState(new int[]{}, offTrack);
        sw.setTrackDrawable(trackDrawable);

        // M3 Expressive Thumb: 24dp circular pill
        GradientDrawable onThumb = shape(c, BG, 999f, 0, 0);
        GradientDrawable offThumb = shape(c, MUTED, 999f, 0, 0);
        int thumbSize = dp(c, 24);
        onThumb.setSize(thumbSize, thumbSize);
        offThumb.setSize(thumbSize, thumbSize);
        android.graphics.drawable.StateListDrawable thumbDrawable = new android.graphics.drawable.StateListDrawable();
        thumbDrawable.addState(new int[]{android.R.attr.state_checked}, onThumb);
        thumbDrawable.addState(new int[]{}, offThumb);
        sw.setThumbDrawable(thumbDrawable);

        sw.setSplitTrack(false);
    }

    /**
     * Styles an Android SeekBar to Material 3 Expressive standards:
     * Thick 16dp rounded active track with contrasting background and elevated capsule thumb.
     */
    static void applyM3Slider(android.widget.SeekBar sb) {
        if (sb == null) return;
        Context c = sb.getContext();
        sb.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));

        // M3 Progress Track
        GradientDrawable bgTrack = shape(c, SURFACE_HIGH, 999f, BORDER, 1f);
        GradientDrawable progTrack = pillShape(c, ACCENT, 0, 0);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                progTrack, Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable progressDrawable = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bgTrack, clip});
        progressDrawable.setId(0, android.R.id.background);
        progressDrawable.setId(1, android.R.id.progress);
        sb.setProgressDrawable(progressDrawable);

        // M3 Thumb: thick vertical pill / rounded thumb
        GradientDrawable thumb = shape(c, ACCENT, 999f, BG, 2.5f);
        int tw = dp(c, 14);
        int th = dp(c, 26);
        thumb.setSize(tw, th);
        sb.setThumb(thumb);
        sb.setSplitTrack(false);
    }
}
