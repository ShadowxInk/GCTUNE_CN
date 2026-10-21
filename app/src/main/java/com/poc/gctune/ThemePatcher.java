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

    /**
     * Structure report for a user picked .itz, computed on the app side (no channel).
     * iTheme only parses an archive it can open with its own zip helper and whose root
     * contains description.xml - anything else is dropped as an invalid package
     * ({@code vf.isNewItzFile} returns false, then "File parse failed, delete.path=").
     */
    static String inspect(File f) {
        StringBuilder sb = new StringBuilder();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
            java.util.zip.ZipEntry desc = z.getEntry(DESC);
            sb.append("能否作为 zip 打开：能（").append(z.size()).append(" 个条目）\n");
            if (desc == null) {
                sb.append("根 description.xml：缺失 —— iTheme 会判为非法包并删除\n");
            } else {
                sb.append("根 description.xml：存在（").append(desc.getSize()).append(" 字节）\n");
            }
            java.util.LinkedHashSet<String> top = new java.util.LinkedHashSet<>();
            int shown = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                String nm = en.nextElement().getName();
                int slash = nm.indexOf('/');
                top.add(slash < 0 ? nm : nm.substring(0, slash) + "/");
            }
            sb.append("顶层：");
            for (String t : top) {
                if (shown++ >= 12) { sb.append(" …"); break; }
                sb.append(t).append(' ');
            }
            sb.append('\n');
            if (desc != null) {
                try (java.io.InputStream in = z.getInputStream(desc)) {
                    byte[] b = new byte[1600];
                    int n = in.read(b);
                    String xml = n <= 0 ? "" : new String(b, 0, n, "UTF-8");
                    sb.append("description.xml 开头：").append(xml.replaceAll("\\s+", " ")).append('\n');
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            sb.append("能否作为 zip 打开：不能 —— ")
                    .append(t.getClass().getSimpleName())
                    .append(t.getMessage() == null ? "" : "：" + t.getMessage())
                    .append('\n');
            sb.append("iTheme 打不开会直接删除该包，请重新打包为普通 zip（deflate 或 store）\n");
        }
        return sb.toString();
    }

    /**
     * Rebuild the user's theme on top of an iTheme managed package: the template keeps its
     * whole structure and its own description.xml (that file is what iTheme validates),
     * while every member that also exists in the user's archive is taken from the user.
     *
     * @return {overwritten members, template members kept}
     */
    static int[] merge(File template, File user, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        int overwritten = 0;
        int kept = 0;
        try (java.util.zip.ZipFile t = new java.util.zip.ZipFile(template);
             java.util.zip.ZipFile u = new java.util.zip.ZipFile(user);
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(dst)))) {
            byte[] buf = new byte[64 * 1024];
            java.util.Enumeration<? extends ZipEntry> en = t.entries();
            while (en.hasMoreElements()) {
                ZipEntry te = en.nextElement();
                String name = te.getName();
                ZipEntry ue = DESC.equals(name) ? null : u.getEntry(name);
                out.putNextEntry(new ZipEntry(name));
                if (ue != null) {
                    try (java.io.InputStream in = u.getInputStream(ue)) {
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    overwritten++;
                } else {
                    try (java.io.InputStream in = t.getInputStream(te)) {
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    kept++;
                }
                out.closeEntry();
            }
        }
        return new int[]{overwritten, kept};
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
