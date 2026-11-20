package io.github.westw0od.taobaocoins;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MainActivity extends Activity {
    private static final int INK = 0xFF28251F, MUTED = 0xFF70685F, ORANGE = 0xFFE66A22;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, history, permission;
    private Button start, stop;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 800); }
    };

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFFFF7ED);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(26));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(dp(22) + bars.left, dp(22) + bars.top, dp(22) + bars.right, dp(26) + bars.bottom);
            return insets;
        });
        setContentView(scroll);

        TextView eyebrow = text("TAOBAO COINS  /  安卓预览版", 12, ORANGE);
        root.addView(eyebrow);
        TextView title = text("淘金币助手", 32, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(10));
        root.addView(title);
        root.addView(text("手机独立运行，不需要连接电脑。", 16, MUTED));

        LinearLayout setup = card(root);
        setup.addView(heading("01  准备开始"));
        setup.addView(text("先登录淘宝并保持竖屏、解锁。开启无障碍后，点击开始即可运行。", 15, MUTED));
        permission = text("", 14, INK);
        permission.setPadding(0, dp(12), 0, dp(6));
        setup.addView(permission);
        Button accessibility = button("开启 / 管理无障碍权限", false);
        accessibility.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        setup.addView(accessibility);
        Button appInfo = button("打开应用信息（处理受限制设置）", false);
        appInfo.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + getPackageName()))));
        setup.addView(appInfo);
        TextView settingsHelp = text("若无障碍列表中的本应用是灰色，请先点击它并关闭系统提示，再打开应用信息 → 右上角菜单 → 允许限制性设置，按系统要求验证身份，然后回到无障碍开启服务。", 12, MUTED);
        settingsHelp.setPadding(0, dp(8), 0, 0);
        setup.addView(settingsHelp);

        LinearLayout actions = card(root);
        actions.addView(heading("02  开始这一轮"));
        actions.addView(text("只做免费的站内淘金币任务。跨 App、游戏、付款和分享邀请会跳过；验证或登录提示需手动处理。", 15, MUTED));
        start = button("开始淘金币任务", true);
        start.setOnClickListener(view -> confirmStart());
        actions.addView(start);
        stop = button("停止运行", false);
        stop.setOnClickListener(view -> {
            CoinService service = CoinService.current();
            if (service != null) service.requestStop();
        });
        actions.addView(stop);
        status = text("未运行", 14, INK);
        status.setPadding(0, dp(10), 0, 0);
        actions.addView(status);

        LinearLayout logs = card(root);
        logs.addView(heading("运行记录"));
        history = text("尚无运行记录", 13, MUTED);
        history.setTextIsSelectable(true);
        logs.addView(history);
        Button copy = button("复制日志", false);
        copy.setOnClickListener(view -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("淘金币日志", history.getText()));
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        });
        logs.addView(copy);
        root.addView(text("截图只在内存中识别，不保存、不上传。本应用无需联网权限。运行中右上角的“停止”按钮可随时结束。\n\nAndroid 11+ · v" + BuildConfig.VERSION_NAME + "\n首次预览版，页面适配以实际运行结果为准。", 12, MUTED));
    }
    private void confirmStart() {
        CoinService service = CoinService.current();
        if (service == null) {
            new AlertDialog.Builder(this).setTitle("先开启无障碍服务")
                .setMessage("在系统辅助功能 → 已安装的应用程序中开启“淘金币助手”。如果是灰色，请先按页面说明允许限制性设置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置", (dialog, which) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("开始本轮任务？")
            .setMessage("本轮运行会读取淘宝页面和屏幕截图，并自动点击、滑动。截图仅在内存中识别。\n\n请保持屏幕解锁，并暂时不要操作手机。遇到验证时程序会停止，可通过悬浮的“停止”按钮随时结束。")
            .setNegativeButton("取消", null)
            .setPositiveButton("同意并开始", (dialog, which) -> service.startRun()).show();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private void update() {
        CoinService service = CoinService.current();
        permission.setText(service == null ? "● 无障碍尚未开启" : "● 无障碍已就绪");
        permission.setTextColor(service == null ? ORANGE : 0xFF26775E);
        boolean running = service != null && service.isRunning();
        start.setEnabled(!running);
        start.setText(service == null ? "先开启无障碍权限" : "开始淘金币任务");
        stop.setEnabled(running);
        String last = getSharedPreferences("state", MODE_PRIVATE).getString("last_status", "未运行");
        status.setText(getString(R.string.run_state, service == null ? last : service.status()));
        String log = service == null ? "" : service.logText();
        if (log.isEmpty()) {
            try { log = new String(Files.readAllBytes(new File(getFilesDir(), "last-run.txt").toPath()), StandardCharsets.UTF_8); }
            catch (Exception ignored) { }
        }
        history.setText(log.isEmpty() ? "尚无运行记录" : log);
    }
    private LinearLayout card(LinearLayout root) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFFFFFF); background.setCornerRadius(dp(20));
        background.setStroke(dp(1), 0xFFF1E5D7);
        layout.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(20), 0, dp(2));
        root.addView(layout, params);
        return layout;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(4), 1); return view;
    }
    private TextView heading(String value) {
        TextView view = text(value, 18, INK); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(10)); return view;
    }
    private Button button(String value, boolean primary) {
        Button view = new Button(this); view.setText(value); view.setAllCaps(false);
        view.setTextSize(15); view.setTextColor(primary ? 0xFFFFFFFF : INK);
        view.setBackgroundTintList(ColorStateList.valueOf(primary ? ORANGE : 0xFFF4EDE3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(56));
        params.setMargins(0, dp(10), 0, 0); view.setLayoutParams(params); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
