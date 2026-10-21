package com.poc.gctune;

/**
 * Standalone «Иконки» page, opened from the Кастомизация hub.
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class IconsActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_ICONS;
    }
}
