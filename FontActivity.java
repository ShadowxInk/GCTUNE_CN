package com.poc.gctune;

/**
 * Standalone «Шрифты» page, opened from the Кастомизация hub.
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class FontActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_FONT;
    }
}
