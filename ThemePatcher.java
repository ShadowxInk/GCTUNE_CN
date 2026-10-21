package com.poc.gctune;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Stage-2 id substitution: authors of custom .itz files keep the ids of paid
 * store themes in description.xml as placeholders, so iTheme matches the theme
 * against its paid catalogue and shows a price. This pass rewrites only
 * &lt;id&gt;/&lt;id3&gt;/&lt;uid&gt; of the root description.xml to neutral
 * values; every other archive member is copied byte for byte.
 */
final class ThemePatcher {

    static final String DESC = "description.xml";
    /** Neutral ids: not present in the iTheme store catalogue. */
    static final String FREE_ID = "1276336";
    static final String FREE_UID = "100152289";

    private static final Pattern ID_FIELD = Pattern.compile("<(id3|id|uid)>\\d+</\\1>");

    private ThemePatcher() {
    }

    /**
     * Copy archive {@code src} into {@code dst}, replacing the id fields of the
     * root description.xml.
     *
     * @return number of replaced fields (0 = description.xml has no id fields)
     */
    static int patch(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        int replaced = 0;
        try (ZipInputStream in = new ZipInputStream(
                     new BufferedInputStream(new FileInputStream(src)));
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(dst)))) {
            byte[] buf = new byte[64 * 1024];
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (DESC.equals(e.getName())) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    int[] cnt = {0};
                    String xml = replaceIds(new String(b.toByteArray(), "UTF-8"), cnt);
                    replaced = cnt[0];
                    // fresh entry: sizes/crc of the original no longer apply
                    out.putNextEntry(new ZipEntry(DESC));
                    out.write(xml.getBytes("UTF-8"));
                    out.closeEntry();
                } else {
                    out.putNextEntry(new ZipEntry(e.getName()));
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.closeEntry();
                }
            }
        }
        return replaced;
    }

    private static String replaceIds(String xml, int[] cnt) {
        Matcher m = ID_FIELD.matcher(xml);
        StringBuffer sb = new StringBuffer(xml.length() + 64);
        while (m.find()) {
            String tag = m.group(1);
            String val = "uid".equals(tag) ? FREE_UID : FREE_ID;
            m.appendReplacement(sb, "<" + tag + ">" + val + "</" + tag + ">");
            cnt[0]++;
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
