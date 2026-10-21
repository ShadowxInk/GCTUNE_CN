package com.poc.gctune;

/**
 * Standalone «Прочее» page, opened from the Кастомизация hub.
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class MiscActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_MISC;
    }
}
