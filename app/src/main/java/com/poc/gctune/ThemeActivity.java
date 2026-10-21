package com.poc.gctune;

/**
 * Standalone «主题» page, opened from the 个性化 hub.
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class ThemeActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_THEME;
    }
}
