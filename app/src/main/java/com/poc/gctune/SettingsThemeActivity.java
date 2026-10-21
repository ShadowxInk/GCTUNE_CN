package com.poc.gctune;

/**
 * Standalone «系统设置» page, opened from the 个性化 hub.
 * Inherits all page logic from Main; sectionPage() selects the built page.
 */
public class SettingsThemeActivity extends Main {
    @Override
    protected int sectionPage() {
        return PAGE_SETTINGS_THEME;
    }
}
