package com.poc.gctune;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志：把「uid 1000 通道为什么没响应」变成一份用户可以直接发出来的 txt。
 *
 * <p>背景：GC Tune 的所有操作都靠 Game Cube 通道完成——命令写进
 * Settings.Secure，由系统以 uid 1000 执行，结果再回传。通道不通时界面只会显示
 * 「uid 1000 无响应」，用户既看不到原因，也无法提供任何日志（非 root 应用读不到
 * logcat / dumpsys）。本类在 App 内部做记录：把通道的每个环节（发布命令、建图层、
 * 收 ACK、唤醒重试、超时）和最后一次命令的结果都记进环形缓冲，再叠加当前环境快照
 * （机型、ROM 版本、权限、三条 settings 值），生成一份纯文本报告，自动判定最可能
 * 的原因，并支持直接分享（微信 / QQ 等）或复制到剪贴板。
 *
 * <p>报告不含隐私数据：只有设备型号、系统版本、权限状态、通道状态与命令执行事件，
 * 不读取已安装应用列表、账号或任何文件内容。
 */
public final class Diag {

    private Diag() {}

    private static final String TAG = "GCTuneDiag";
    /** 报告格式版本：字段变化时递增，便于以后解析。 */
    private static final String REPORT_VER = "diag-1";
    private static final int MAX_EVENTS = 400;
    private static final int MAX_FILES = 8;
    /** 应用外部私有目录下的子目录（FileProvider 分享用）。 */
    private static final String DIR_NAME = "diag";
    /** Android 10+ 额外复制到公共下载目录，便于文件管理器找到。 */
    private static final String MEDIA_REL = "Download/gctune/diag";
    private static final String FMT_TS = "HH:mm:ss.SSS";
    private static final String FMT_FILE = "yyyyMMdd-HHmmss";
    private static final String FMT_FULL = "yyyy-MM-dd HH:mm:ss";
    private static final String ACK_KEY = "gc_tune_ack";
    private static final String CMD_KEY = "gc_tune_cmd";
    /** 与 Main 的 RC_UNKNOWN 一致：表示没有拿到返回码。 */
    private static final int RC_UNKNOWN = -999;

    /** vivo / OriginOS 里对定位有用的属性（读不到的显示 "-"）。 */
    private static final String[] ROM_PROPS = {
            "ro.vivo.os.version",
            "ro.vivo.os.rom.version",
            "ro.vivo.os.name",
            "ro.vivo.os.build.display.id",
            "ro.vivo.rom.version",
            "ro.build.display.id",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.build.version.security_patch",
            "ro.build.fingerprint",
            "ro.build.description",
            "ro.product.model",
            "ro.product.brand",
            "ro.product.device",
    };

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat(FMT_TS, Locale.US);
    private static final SimpleDateFormat FULL = new SimpleDateFormat(FMT_FULL, Locale.US);
    private static final SimpleDateFormat FILE_TS = new SimpleDateFormat(FMT_FILE, Locale.US);

    private static Context app;
    private static File lastFile;
    private static String entry = "设置页";

    // ------------------------------------------------------------ 最近一次命令
    private static boolean runActive = false;
    private static long runStart = 0;
    private static long runEnd = 0;
    private static int runAck = 0;
    private static int runTimeout = 0;
    private static String runCmd = null;
    private static boolean runDone = false;
    private static boolean runOk = false;
    private static int runRc = 0;
    private static String runOut = null;

    // ------------------------------------------------------------- 通道观测值
    /** null = 还没观测到（服务没跑起来 / 日志被清）；true/false = 图层是否建成功。 */
    private static Boolean layerValid = null;
    private static String layerError = null;
    private static int ackReceived = 0;
    private static int wakeCount = 0;

    // ------------------------------------------------------------------ 初始化
    /** 幂等；Main.onCreate 与 ChannelService 都会调用。 */
    public static void init(Context c) {
        if (c == null) return;
        synchronized (LOCK) {
            if (app == null) {
                app = c.getApplicationContext();
                record("app", "诊断模块初始化，pid=" + android.os.Process.myPid());
            }
        }
    }

    /** 记录一条带毫秒时间戳的事件（环形缓冲，最多 400 条）。 */
    public static void record(String tag, String msg) {
        if (msg == null) msg = "-";
        if (msg.length() > 500) msg = msg.substring(0, 500) + "…";
        String line;
        synchronized (LOCK) {
            line = stamp(TS, System.currentTimeMillis()) + " [" + tag + "] " + msg;
            EVENTS.addLast(line);
            while (EVENTS.size() > MAX_EVENTS) EVENTS.removeFirst();
        }
        Log.i(TAG, line);
    }

    /** 标记最近一次运行的时间/参数，用于报告里的耗时统计。 */
    public static void runStarted(int ack, String cmd, int timeoutSec, boolean daemon) {
        synchronized (LOCK) {
            runActive = true;
            runStart = System.currentTimeMillis();
            runEnd = 0;
            runAck = ack;
            runTimeout = timeoutSec;
            runCmd = cmd;
            runDone = false;
            runOk = false;
            runRc = 0;
            runOut = null;
        }
        record("apply", "发送命令 ack=" + ack + " 超时=" + timeoutSec + "s 守护进程=" + daemon
                + " 命令长度=" + (cmd == null ? -1 : cmd.length()));
    }

    public static void runFinished(boolean ok, String out, int rc) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            runActive = false;
            runEnd = now;
            runDone = true;
            runOk = ok;
            runRc = rc;
            runOut = out;
        }
        record("run", ok ? "完成：uid 1000 已响应" : "结束：uid 1000 无响应（超时/取消）"
                + "，耗时 " + elapsedLabel(now - runStart) + "，rc=" + rc);
    }

    public static void channelStart(int ack, String layer, boolean daemon) {
        record("chan", "ChannelService 启动 ack=" + ack + " 守护进程=" + daemon
                + " 图层名长度=" + (layer == null ? -1 : layer.length()));
    }

    public static void channelLayer(boolean valid, String err) {
        synchronized (LOCK) {
            layerValid = valid;
            layerError = valid ? null : (err == null ? "未知" : err);
        }
        record("chan", "创建 SurfaceFlinger 图层：" + (valid ? "成功" : "失败 " + (err == null ? "" : err)));
    }

    public static void channelAck(int n, int outLen, String dbg) {
        synchronized (LOCK) {
            ackReceived++;
        }
        record("chan", "收到 ACK 广播 n=" + n + " 负载=" + outLen + " 字节"
                + (dbg == null || dbg.isEmpty() ? "" : " 回显=" + dbg));
    }

    public static void channelWake(int ack, int retry) {
        synchronized (LOCK) {
            wakeCount++;
        }
        record("chan", "WAKE 唤醒重试 #" + retry + "（ack=" + ack + "）");
    }

    public static void channelDone(boolean ok, long elapsedMs) {
        record("chan", "通道结束 " + (ok ? "成功" : "失败") + "，耗时 " + elapsedLabel(elapsedMs));
    }

    public static void entryPoint(String where) {
        entry = where == null ? "应用" : where;
    }

    public static File lastFile() {
        synchronized (LOCK) { return lastFile; }
    }

    public static String lastPathLabel() {
        File f = lastFile();
        return f == null ? "" : f.getAbsolutePath();
    }

    // ------------------------------------------------------------------ 报告
    /** 生成完整诊断报告文本。 */
    public static String report(Context c) {
        Context ctx = c == null ? app : c;
        StringBuilder sb = new StringBuilder(8192);
        long now = System.currentTimeMillis();

        sb.append("GC Tune 诊断报告\n");
        sb.append("生成时间: ").append(stamp(FULL, now)).append('\n');
        sb.append("报告格式: ").append(REPORT_VER).append('\n');
        sb.append("生成入口: ").append(entry).append('\n');

        PackageInfo pi = packageInfo(ctx);
        // ---------------------------------------------------------- 1. 应用
        sb.append("\n== 1. 应用 ==\n");
        if (pi == null) {
            sb.append("包名: ").append(ctx.getPackageName()).append("（包信息读取失败）\n");
        } else {
            sb.append(kv("包名", pi.packageName));
            sb.append(kv("版本", pi.versionName + " (" + pi.versionCode + ")"));
            sb.append(kv("首次安装", pi.firstInstallTime > 0 ? stamp(FULL, pi.firstInstallTime) : "-"));
            sb.append(kv("最近更新", pi.lastUpdateTime > 0 ? stamp(FULL, pi.lastUpdateTime) : "-"));
            sb.append(kv("安装来源", installSource(ctx, pi.packageName)));
            sb.append(kv("签名 SHA-256", signatureSha256(ctx)));
        }

        // ------------------------------------------------------ 2. 设备与系统
        sb.append("\n== 2. 设备与系统 ==\n");
        sb.append(kv("品牌 / 厂商", safe(Build.BRAND) + " / " + safe(Build.MANUFACTURER)));
        sb.append(kv("型号 / 设备", safe(Build.MODEL) + " / " + safe(Build.DEVICE)));
        sb.append(kv("Android", safe(Build.VERSION.RELEASE) + " (SDK " + Build.VERSION.SDK_INT + ")"));
        sb.append(kv("安全补丁", safe(Build.VERSION.SECURITY_PATCH)));
        sb.append(kv("ABI", abis()));
        sb.append(kv("内核", System.getProperty("os.version")));
        sb.append(kv("Java/VM", System.getProperty("java.vm.version")));
        sb.append("OriginOS / ROM 属性:\n");
        for (String key : ROM_PROPS) {
            String v = prop(key);
            sb.append("  ").append(key).append(" = ").append(v.isEmpty() ? "-" : v).append('\n');
        }

        // --------------------------------------------------- 3. 权限与组件
        boolean writeSecure = granted(ctx, "android.permission.WRITE_SECURE_SETTINGS");
        boolean notif = notificationsEnabled(ctx);
        boolean batteryOpt = ignoringBatteryOptimizations(ctx);
        sb.append("\n== 3. 权限与组件 ==\n");
        sb.append(kv("WRITE_SECURE_SETTINGS", writeSecure ? "已授予" : "未授予（通道无法发布命令！）"));
        sb.append(kv("通知权限", notif ? "已允许" : "未允许"));
        sb.append(kv("电池优化豁免", batteryOpt ? "已豁免" : "未豁免"));
        sb.append(kv("ChannelService", componentState(ctx, "com.poc.gctune.ChannelService")));
        sb.append(kv("KeepAliveService", componentState(ctx, "com.poc.gctune.KeepAliveService")));

        // -------------------------------------------------------- 4. 通道快照
        String cmdVal = null;
        String cmdErr = null;
        String ackVal = null;
        String ackErr = null;
        String gamePkg = null;
        try { cmdVal = Settings.Secure.getString(ctx.getContentResolver(), CMD_KEY); }
        catch (Throwable t) { cmdErr = String.valueOf(t); }
        try { ackVal = Settings.System.getString(ctx.getContentResolver(), ACK_KEY); }
        catch (Throwable t) { ackErr = String.valueOf(t); }
        try { gamePkg = Settings.System.getString(ctx.getContentResolver(), "current_game_package"); }
        catch (Throwable ignored) {}

        sb.append("\n== 4. 通道快照 ==\n");
        sb.append(kv("current_game_package", gamePkg == null ? "(读不到)" : gamePkg
                + (ctx.getPackageName().equals(gamePkg) ? "  → 当前在游戏列表中" : "  → 当前不在游戏列表")));
        if (cmdErr != null) {
            sb.append(kv("gc_tune_cmd", "读取失败: " + cmdErr));
        } else if (cmdVal == null || cmdVal.isEmpty()) {
            sb.append(kv("gc_tune_cmd", "空（从未发布过命令 / 已被清除）"));
        } else {
            sb.append(kv("gc_tune_cmd", "长度 " + cmdVal.length() + " 字节"));
            sb.append(kv("  预览", preview(cmdVal, 120)));
            sb.append(kv("  含守护进程引导", cmdVal.contains("/data/bbkcore/theme/.gctw.pid") ? "是" : "否"));
        }
        if (ackErr != null) {
            sb.append(kv("gc_tune_ack", "读取失败: " + ackErr));
        } else if (ackVal == null || ackVal.isEmpty()) {
            sb.append(kv("gc_tune_ack", "空（uid 1000 从未回写过结果）"));
        } else {
            sb.append(kv("gc_tune_ack", "原文 " + preview(ackVal, 160)));
            String[] parts = ackVal.split("\\|", 2);
            sb.append(kv("  编号", parts.length > 0 ? parts[0] : "-"));
            sb.append(kv("  负载长度", parts.length > 1 ? String.valueOf(parts[1].length()) : "0"));
        }
        SharedPreferences p = prefs(ctx);
        sb.append(kv("偏好 require_game", String.valueOf(p.getBoolean("require_game", true))));
        sb.append(kv("偏好 daemon_on", String.valueOf(p.getBoolean("daemon_on", true))));
        sb.append(kv("偏好 timeout_sec", String.valueOf(p.getInt("timeout_sec", 60))));
        synchronized (LOCK) {
            sb.append(kv("最近发送 ack", runAck == 0 ? "-" : String.valueOf(runAck)));
            sb.append(kv("图层创建", layerValid == null ? "未观测到（服务可能没起来）"
                    : (layerValid ? "成功" : "失败：" + (layerError == null ? "未知" : layerError))));
            sb.append(kv("收到 ACK 广播", ackReceived + " 次"));
            sb.append(kv("WAKE 重试", wakeCount + " 次"));
        }

        // ------------------------------------------------------ 5. 上次命令
        sb.append("\n== 5. 上次命令 ==\n");
        synchronized (LOCK) {
            if (runCmd == null) {
                sb.append("尚未执行过命令（可先在设置页点「通道自检」）。\n");
            } else {
                sb.append(kv("命令", runCmd.length() > 400 ? runCmd.substring(0, 400) + "…" : runCmd));
                sb.append(kv("结果", runDone ? (runOk ? "成功：uid 1000 已响应" : "失败：uid 1000 无响应")
                        : "仍在等待中（进行中）"));
                if (runDone) sb.append(kv("耗时", elapsedLabel(runEnd - runStart)));
                sb.append(kv("返回码", runRc == RC_UNKNOWN ? "未知" : String.valueOf(runRc)));
                String out = runOut == null ? "" : runOut.trim();
                if (!out.isEmpty()) {
                    sb.append("输出:\n");
                    for (String line : tail(out, 2000).split("\n")) {
                        sb.append("  ").append(line).append('\n');
                    }
                } else {
                    sb.append(kv("输出", "（空）"));
                }
            }
        }

        // ------------------------------------------------------ 6. 自检判定
        sb.append("\n== 6. 自检判定 ==\n");
        StringBuilder verdict = new StringBuilder();
        boolean okAll = appendVerdicts(ctx, verdict, writeSecure, notif, batteryOpt,
                gamePkg, cmdVal, cmdErr, ackVal, ackErr);
        sb.append(verdict);

        // ------------------------------------------------------ 7. 事件日志
        sb.append("\n== 7. 事件日志（最近 ").append(MAX_EVENTS).append(" 条，按时间正序）==\n");
        synchronized (LOCK) {
            if (EVENTS.isEmpty()) sb.append("（空）\n");
            for (String line : EVENTS) sb.append(line).append('\n');
        }

        // ------------------------------------------------------ 8. 反馈方式
        sb.append("\n== 8. 反馈方式 ==\n");
        sb.append(ctx.getPackageName()).append(" ").append(pi == null ? "" : pi.versionName).append('\n');
        sb.append("把本文件（或全选复制文本）直接发给作者即可，不需要截图。\n");
        sb.append("反馈模板：\n");
        sb.append("  机型/ROM: ").append(safe(Build.MANUFACTURER)).append(' ').append(safe(Build.MODEL))
                .append(" / OriginOS ").append(orDash(prop("ro.vivo.os.version"))).append('\n');
        sb.append("  问题: （例如 安装字体提示「uid 1000 无响应」）\n");
        sb.append("  已做的尝试: （重装/授权/重启 等）\n");
        sb.append("  附件: 本诊断日志\n");
        sb.append("\n本文件不含隐私数据：只有设备型号、系统版本、权限状态、通道状态与命令执行事件，");
        sb.append("不含已安装应用列表、账号信息或任何文件内容。\n");
        if (!okAll) {
            sb.append("（检测到问题，详见第 6 节。）\n");
        }
        return sb.toString();
    }

    /**
     * 判定：把「看到的证据」翻译成结论 + 建议动作。
     * @return true = 没发现明显问题
     */
    private static boolean appendVerdicts(Context ctx, StringBuilder sb, boolean writeSecure,
                                          boolean notif, boolean batteryOpt, String gamePkg,
                                          String cmdVal, String cmdErr, String ackVal, String ackErr) {
        boolean allOk = true;
        boolean requireGame = prefs(ctx).getBoolean("require_game", true);
        boolean inGame = ctx.getPackageName().equals(gamePkg);
        boolean done;
        boolean ok = false;
        Boolean layer;
        int acks;
        synchronized (LOCK) {
            done = runDone;
            ok = runOk;
            layer = layerValid;
            acks = ackReceived;
        }

        if (!writeSecure) {
            allOk = false;
            sb.append("[✗] 缺少 WRITE_SECURE_SETTINGS：命令根本发布不出去，uid 1000 收不到任何东西。\n");
            sb.append("    处理: 用电脑执行一次\n");
            sb.append("      adb shell pm grant ").append(ctx.getPackageName())
                    .append(" android.permission.WRITE_SECURE_SETTINGS\n");
            sb.append("    （重装/更新后该授权会丢失，必须重新执行。）\n");
        } else {
            sb.append("[✓] WRITE_SECURE_SETTINGS 已授予。\n");
        }

        if (requireGame && !inGame) {
            allOk = false;
            sb.append("[✗] 当前不在游戏列表中，命令被 App 直接拦截（还没发到通道）。\n");
            sb.append("    处理: 电竞模式 → 支持的游戏 → 其他 → 添加 GC Tune，");
            sb.append("然后从面板打开本应用。\n");
        } else if (inGame) {
            sb.append("[✓] 当前在游戏列表中。\n");
        }

        sb.append("[").append(cmdErr == null ? "✓" : "✗").append("] gc_tune_cmd ");
        if (cmdErr != null) {
            allOk = false;
            sb.append("读取失败：").append(cmdErr).append("（多半是权限问题）。\n");
        } else if (cmdVal == null || cmdVal.isEmpty()) {
            sb.append("为空：还没成功发布过命令。\n");
            if (writeSecure) allOk = false;
        } else {
            sb.append("已写入（长度 ").append(cmdVal.length()).append(" 字节）。\n");
        }

        if (!done) {
            sb.append("[!] 还没有跑过命令：请先在设置页点「通道自检」，再导出本报告。\n");
        } else if (ok) {
            sb.append("[✓] 最近一次命令：uid 1000 已响应，通道正常。\n");
        } else {
            allOk = false;
            if (Boolean.FALSE.equals(layer)) {
                sb.append("[✗] 图层创建失败：系统没有接受本次通道图层。\n");
                sb.append("    这是 OriginOS 侧钩子变化导致的（通常出现在系统更新后），App 内无法绕过；\n");
                sb.append("    请把本报告发给作者，并附上当前 OriginOS 版本：")
                        .append(orDash(prop("ro.vivo.os.version"))).append('\n');
            } else if (Boolean.TRUE.equals(layer) && acks == 0) {
                sb.append("[✗] 图层已建立但收不到任何 ACK：uid 1000 没有执行这条命令。\n");
                sb.append("    可能原因: ① 系统钩子（Game Cube FpsManager）在新 ROM 上失效；\n");
                sb.append("              ② 命令确实执行了但回传广播被拦截；\n");
                sb.append("              ③ 超时时间内面板没被唤醒。\n");
                sb.append("    处理: 先把超时改大（如 90 秒）重试一次；仍失败请把本报告发给作者。\n");
            } else if (Boolean.TRUE.equals(layer) && acks > 0) {
                sb.append("[✗] 收到了 ACK 广播但编号/负载对不上：可能是上一轮残留结果或守护进程在执行旧命令。\n");
                sb.append("    处理: 重启手机后重试一次，仍失败请把本报告发给作者。\n");
            } else {
                sb.append("[✗] 命令超时且没有观测到通道图层：前台服务可能没启动起来（被系统限制）。\n");
                sb.append("    处理: 允许通知、允许后台运行，然后重试；仍失败请把本报告发给作者。\n");
            }
        }

        if (ackErr != null) {
            allOk = false;
            sb.append("[✗] gc_tune_ack 读取失败：").append(ackErr).append('\n');
        } else if (ackVal == null || ackVal.isEmpty()) {
            sb.append("[!] gc_tune_ack 为空：uid 1000 从未回写过结果（通道从未成功过一次）。\n");
        }

        if (!notif) {
            sb.append("[!] 通知权限未允许：执行期间的前台服务通知不显示，可能影响保活。\n");
        }
        if (!batteryOpt) {
            sb.append("[!] 未加入电池优化白名单：后台执行可能被中断。\n");
        }
        sb.append(allOk ? "结论: 未发现明显问题，通道应当可用。\n"
                : "结论: 见上面 [✗] 的条目，按“处理”操作后重新自检。\n");
        return allOk;
    }

    // ------------------------------------------------------------------ 保存
    /** 生成报告并写下文件（应用外部私有目录 + Android 10 起复制一份到下载目录）。 */
    public static synchronized File save(Context c) {
        Context ctx = c == null ? app : c;
        if (ctx == null) return null;
        String text = report(ctx);
        File dir = new File(ctx.getExternalFilesDir(null), DIR_NAME);
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "gctune-diag-" + stamp(FILE_TS, System.currentTimeMillis()) + ".txt");
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(text.getBytes("UTF-8"));
                out.flush();
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
            }
            synchronized (LOCK) { lastFile = f; }
            record("diag", "报告已保存: " + f.getAbsolutePath() + " (" + text.getBytes("UTF-8").length + " 字节)");
            copyToDownloads(ctx, f, text.getBytes("UTF-8"));
            prune(dir);
            return f;
        } catch (Throwable t) {
            Log.e(TAG, "保存报告失败: " + t);
            record("diag", "保存报告失败: " + t);
            return null;
        }
    }

    /** Android 10+：把报告复制到「下载/gctune/diag」，方便在文件管理器里找到。 */
    private static void copyToDownloads(Context ctx, File src, byte[] data) {
        if (Build.VERSION.SDK_INT < 29) return;
        Uri uri = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_REL);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) { record("diag", "复制到下载目录失败：insert 返回 null"); return; }
            OutputStream os = cr.openOutputStream(uri);
            try { os.write(data); os.flush(); } finally { try { os.close(); } catch (Throwable ignored) {} }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            record("diag", "已复制到 " + MEDIA_REL + "/" + src.getName());
        } catch (Throwable t) {
            Log.e(TAG, "复制到下载目录失败: " + t);
            record("diag", "复制到下载目录失败: " + t);
            try { if (uri != null) ctx.getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
        }
    }

    /** 只保留最近 MAX_FILES 份报告。 */
    private static void prune(File dir) {
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length <= MAX_FILES) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            for (int i = MAX_FILES; i < fs.length; i++) {
                try { fs[i].delete(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------- 分享/复制
    /** 用系统分享面板把报告发出去；返回 false 表示没有可用的分享方式。 */
    public static boolean share(Activity a, File f) {
        if (a == null || f == null || !f.exists()) return false;
        try {
            Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "GC Tune 诊断日志");
            i.putExtra(Intent.EXTRA_STREAM, u);
            i.putExtra(Intent.EXTRA_TITLE, "GC Tune 诊断日志");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "发送诊断日志");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(chooser);
            record("diag", "已打开分享面板: " + f.getName());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "分享失败: " + t);
            record("diag", "分享失败: " + t);
            return false;
        }
    }

    /** 复制报告全文到剪贴板（分享面板里没有微信时用这个）。 */
    public static void copy(Context c) {
        Context ctx = c == null ? app : c;
        if (ctx == null) return;
        String text = report(ctx);
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("GC Tune 诊断日志", text));
            record("diag", "报告已复制到剪贴板（" + text.length() + " 字符）");
        } catch (Throwable t) {
            Log.e(TAG, "复制失败: " + t);
        }
    }

    // ------------------------------------------------------------------ 工具
    private static String kv(String k, String v) {
        return k + ": " + (v == null ? "-" : v) + "\n";
    }

    private static String safe(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static String abis() {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                StringBuilder sb = new StringBuilder();
                for (String a : Build.SUPPORTED_ABIS) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(a);
                }
                return sb.length() == 0 ? "-" : sb.toString();
            }
        } catch (Throwable ignored) {}
        return orDash(Build.CPU_ABI);
    }

    private static String stamp(SimpleDateFormat f, long ts) {
        synchronized (f) { return f.format(new Date(ts)); }
    }

    private static String preview(String s, int n) {
        if (s == null) return "-";
        String one = s.replace('\n', ' ');
        return one.length() <= n ? one : one.substring(0, n) + "…";
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    private static String elapsedLabel(long ms) {
        if (ms < 0) return "-";
        return String.format(Locale.US, "%.1f 秒", ms / 1000.0);
    }

    /** 读系统属性（android.os.SystemProperties 是隐藏类，只能反射）。 */
    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Method get = c.getMethod("get", String.class);
            Object v = get.invoke(null, key);
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("gctune_m3", Context.MODE_PRIVATE);
    }

    private static boolean granted(Context ctx, String perm) {
        try {
            return ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean notificationsEnabled(Context ctx) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm != null && nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean ignoringBatteryOptimizations(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static String componentState(Context ctx, String cls) {
        try {
            ComponentName cn = new ComponentName(ctx.getPackageName(), cls);
            int st = ctx.getPackageManager().getComponentEnabledSetting(cn);
            if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                return "已禁用";
            }
            return st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ? "启用" : "启用（默认）";
        } catch (Throwable t) {
            return "未知: " + t;
        }
    }

    private static PackageInfo packageInfo(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String installSource(Context ctx, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.content.pm.InstallSourceInfo isi =
                        ctx.getPackageManager().getInstallSourceInfo(pkg);
                String a = isi.getInstallingPackageName();
                String b = isi.getInitiatingPackageName();
                if (a != null && a.length() > 0) return a;
                if (b != null && b.length() > 0) return b;
                return "未知（可能由 adb/shell 安装）";
            }
            String s = ctx.getPackageManager().getInstallerPackageName(pkg);
            return s == null || s.isEmpty() ? "未知（可能由 adb/shell 安装）" : s;
        } catch (Throwable t) {
            return "未知: " + t;
        }
    }

    @SuppressWarnings("deprecation")
    private static String signatureSha256(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] sigs = pi.signatures;
            if (sigs == null || sigs.length == 0) return "-";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return "未知: " + t;
        }
    }
}
