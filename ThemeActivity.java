package com.poc.gctune;

/**
 * Standalone «Тема» page, opened from the Кастомизация hub.
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class ThemeActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_THEME;
    }
}
