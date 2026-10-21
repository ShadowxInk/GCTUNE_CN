package com.poc.gctune;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Icon pack import ported from Origin Icons (IconPackEngine.kt).
 *
 * Discovery: activities handling org.adw.launcher.THEMES / com.novalauncher.THEME.
 * Mapping: assets/appfilter.xml (or res/raw/appfilter.xml) with
 * <item component="ComponentInfo{pkg/class}" drawable="name"/> entries, plus a
 * fallback that scans the pack APK for res/drawable|mipmap file names that look
 * like package names (underscores replaced with dots).
 * Lookup per app: appfilter first, then fallback, then null (caller decides).
 */
final class IconPackLoader {

    /** One discovered pack: package, label, launcher icon. */
    static final class Pack {
        final String pkg;
        final String label;
        final Drawable icon;

        Pack(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private final Context context;
    private final PackageManager pm;

    private Context packContext;
    private final Map<String, String> appfilter = new LinkedHashMap<>();
    private final Map<String, String> fallback = new LinkedHashMap<>();

    IconPackLoader(Context context) {
        this.context = context;
        this.pm = context.getPackageManager();
    }

    boolean isLoaded() {
        return packContext != null;
    }

    /** Installed icon packs, sorted by label. */
    List<Pack> findPacks() {
        LinkedHashSet<String> pkgs = new LinkedHashSet<>();
        for (String action : new String[]{"org.adw.launcher.THEMES", "com.novalauncher.THEME"}) {
            try {
                List<ResolveInfo> list = pm.queryIntentActivities(new Intent(action), 0);
                for (ResolveInfo ri : list) pkgs.add(ri.activityInfo.packageName);
            } catch (Throwable ignored) {
            }
        }
        List<Pack> out = new ArrayList<>();
        for (String p : pkgs) {
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                out.add(new Pack(p, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai)));
            } catch (Throwable ignored) {
            }
        }
        Collections.sort(out, new Comparator<Pack>() {
            @Override public int compare(Pack a, Pack b) {
                return a.label.toLowerCase(Locale.getDefault())
                        .compareTo(b.label.toLowerCase(Locale.getDefault()));
            }
        });
        return out;
    }

    /** Loads appfilter + fallback from the pack; returns mapped icon count (0 = unsupported pack). */
    int load(String packPkg) {
        appfilter.clear();
        fallback.clear();
        packContext = null;
        try {
            packContext = context.createPackageContext(packPkg, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            return 0;
        }
        try {
            readAppFilter(packPkg);
            readFallback(packPkg, appfilter.keySet());
        } catch (Throwable ignored) {
        }
        return appfilter.size() + fallback.size();
    }

    /** Pack icon for a package, or null when the pack has no entry for it. */
    Drawable iconFor(String pkg) {
        if (packContext == null) return null;
        String name = appfilter.get(pkg);
        if (name == null) name = fallback.get(pkg);
        if (name == null) return null;
        try {
            Resources r = packContext.getResources();
            String packPkg = packContext.getPackageName();
            int id = r.getIdentifier(name, "drawable", packPkg);
            if (id == 0) id = r.getIdentifier(name, "mipmap", packPkg);
            if (id == 0) return null;
            return r.getDrawable(id, packContext.getTheme());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ appfilter

    private void readAppFilter(String packPkg) {
        InputStream in = null;
        try {
            in = packContext.getAssets().open("appfilter.xml");
        } catch (Throwable ignored) {
        }
        if (in == null) {
            try {
                int id = packContext.getResources().getIdentifier("appfilter", "raw", packPkg);
                if (id != 0) in = packContext.getResources().openRawResource(id);
            } catch (Throwable ignored) {
            }
        }
        if (in == null) return;
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG || !"item".equalsIgnoreCase(p.getName())) continue;
                String comp = attr(p, "component");
                String dr = attr(p, "drawable");
                if (comp == null || dr == null || comp.isEmpty() || dr.isEmpty()) continue;
                String pkgName = normalizeComponent(comp);
                if (pkgName != null && validOutputName(pkgName)) {
                    appfilter.put(pkgName, dr);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { in.close(); } catch (Throwable ignored) {
            }
        }
    }

    /** "ComponentInfo{pkg/.Activity}" -> "pkg" */
    private static String normalizeComponent(String value) {
        String raw = value.trim();
        if (raw.startsWith("ComponentInfo{") && raw.endsWith("}")) {
            raw = raw.substring("ComponentInfo{".length(), raw.length() - 1);
        }
        int sep = raw.indexOf('/');
        if (sep <= 0) return null;
        return raw.substring(0, sep);
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (name.equalsIgnoreCase(p.getAttributeName(i))) return p.getAttributeValue(i);
        }
        return null;
    }

    // ------------------------------------------------------------------ fallback

    /** Scans pack APK for drawable/mipmap file names that look like package names. */
    private void readFallback(String packPkg, Set<String> mappedPackages) {
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(packPkg, 0);
        } catch (Throwable t) {
            return;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            ZipFile zip = new ZipFile(info.sourceDir);
            try {
                Enumeration<?> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = ((java.util.zip.ZipEntry) entries.nextElement()).getName();
                    if (!name.startsWith("res/drawable") && !name.startsWith("res/mipmap")) continue;
                    int slash = name.lastIndexOf('/');
                    if (slash < 0) continue;
                    String file = name.substring(slash + 1).toLowerCase(Locale.US);
                    if (!file.endsWith(".png") && !file.endsWith(".webp") && !file.endsWith(".xml")) continue;
                    if (file.endsWith(".9.png")) continue;
                    names.add(name.substring(slash + 1, name.lastIndexOf('.')));
                }
            } finally {
                zip.close();
            }
        } catch (Throwable ignored) {
            return;
        }
        for (String n : names) {
            if (n.startsWith("abc_") || n.startsWith("ic_menu_")) continue;
            String dotted = n.replace('_', '.');
            if (dotted.indexOf('.') < 0 || !validOutputName(dotted)) continue;
            if (mappedPackages.contains(dotted)) continue;
            fallback.putIfAbsent(dotted, n);
        }
    }

    /** Package-name-shaped, file-safe output key (as in Origin Icons). */
    private static boolean validOutputName(String name) {
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
