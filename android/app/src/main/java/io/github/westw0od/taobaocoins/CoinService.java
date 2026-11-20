package io.github.westw0od.taobaocoins;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** All screenshots stay in memory. Gestures require a fresh Taobao focus check. */
public class CoinService extends AccessibilityService {
    private static volatile WeakReference<CoinService> connected = new WeakReference<>(null);
    public static CoinService current() { return connected.get(); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final List<String> messages = new ArrayList<>();
    private volatile boolean stopped;
    private volatile Thread runThread;
    private volatile String state = "未运行";
    private TextRecognizer recognizer;
    private Button stopOverlay;
    private PowerManager.WakeLock wakeLock;
    private long nextScreenshotAt;

    static class Attention extends Exception { Attention(String text) { super(text); } }
    static class LeftTaobao extends Exception { LeftTaobao(String text) { super(text); } }
    static class SkipTask extends Exception { SkipTask(String text) { super(text); } }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = new WeakReference<>(this);
        state = "无障碍已开启，等待开始";
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { requestStop(); }
    @Override public void onDestroy() {
        requestStop();
        worker.shutdownNow();
        removeOverlay();
        releaseWakeLock();
        if (current() == this) connected.clear();
        super.onDestroy();
    }
    public boolean isRunning() { return busy.get(); }
    public String status() { return state; }
    public synchronized String logText() { return String.join("\n", messages); }
    public synchronized void log(String text) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        messages.add(time + "  " + text);
        if (messages.size() > 300) messages.remove(0);
        state = text;
    }
    public void requestStop() {
        stopped = true;
        Thread thread = runThread;
        if (thread != null) thread.interrupt();
    }
    public void startRun() {
        if (!busy.compareAndSet(false, true)) return;
        stopped = false;
        synchronized (this) { messages.clear(); }
        log("准备打开淘宝");
        showOverlay();
        PowerManager power = getSystemService(PowerManager.class);
        // Release on every exit. The one-hour timeout also bounds battery use.
        wakeLock = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, getPackageName() + ":coins");
        wakeLock.acquire(60 * 60 * 1000L);
        worker.execute(() -> {
            runThread = Thread.currentThread();
            try {
                recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                new CoinEngine(this).run();
            } catch (InterruptedException e) {
                log("已停止");
                Thread.currentThread().interrupt();
            } catch (Attention e) {
                log("需要手动处理：" + e.getMessage());
            } catch (Exception e) {
                log("运行已停止：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                if (recognizer != null) { recognizer.close(); recognizer = null; }
                releaseWakeLock();
                saveLog();
                main.post(() -> {
                    removeOverlay();
                    Toast.makeText(this, state, Toast.LENGTH_LONG).show();
                });
                runThread = null;
                busy.set(false);
                // The executor reuses its thread on the next run.
                Thread.interrupted();
            }
        });
    }
    private synchronized void saveLog() {
        try {
            Files.write(new File(getFilesDir(), "last-run.txt").toPath(), logText().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        getSharedPreferences("state", MODE_PRIVATE).edit().putString("last_status", state).apply();
    }
    private synchronized void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
    private void showOverlay() {
        if (stopOverlay != null) return;
        stopOverlay = new Button(this);
        stopOverlay.setText("停止");
        stopOverlay.setTextColor(0xFFFFFFFF);
        stopOverlay.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFB34A12));
        stopOverlay.setOnClickListener(view -> requestStop());
        int dp = (int) (getResources().getDisplayMetrics().density * 76);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(dp, dp * 2 / 3,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.y = (int) (getResources().getDisplayMetrics().density * 36);
        try { getSystemService(WindowManager.class).addView(stopOverlay, params); }
        catch (RuntimeException e) { stopOverlay = null; }
    }
    private void removeOverlay() {
        if (stopOverlay == null) return;
        try { getSystemService(WindowManager.class).removeView(stopOverlay); }
        catch (RuntimeException ignored) { }
        stopOverlay = null;
    }
    void checkStopped() throws InterruptedException, Attention {
        if (stopped || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!getSystemService(PowerManager.class).isInteractive()
            || getSystemService(KeyguardManager.class).isKeyguardLocked())
            throw new Attention("手机已锁屏，请解锁后重新开始");
    }
    void waitMs(long milliseconds) throws InterruptedException, Attention {
        checkStopped();
        Thread.sleep(milliseconds);
        checkStopped();
    }
    String foreground() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null && active.getPackageName() != null) return active.getPackageName().toString();
        // Never fall back to a merely visible/background application window.
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                && window.isFocused()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
            }
        }
        return "";
    }
    void requireTaobao() throws Exception {
        checkStopped();
        String current = foreground();
        for (int n = 0; current.isEmpty() && n < 3; n++) {
            waitMs(180);
            current = foreground();
        }
        if (!PageModel.TAOBAO.equals(current)) throw new LeftTaobao("前台为 " + (current.isEmpty() ? "未知窗口" : current));
    }
    void launchTaobao() throws Exception {
        checkStopped();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        main.post(() -> {
            if (stopped) { result.complete(false); return; }
            Intent intent = getPackageManager().getLaunchIntentForPackage(PageModel.TAOBAO);
            if (intent == null) { result.complete(false); return; }
            try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); result.complete(true); }
            catch (RuntimeException e) { result.complete(false); }
        });
        if (!result.get(5, TimeUnit.SECONDS)) throw new Attention("未能打开淘宝，请确认已安装并登录淘宝");
        for (int n = 0; n < 20; n++) {
            waitMs(300);
            if (PageModel.TAOBAO.equals(foreground())) return;
        }
        throw new Attention("淘宝未进入前台，请手动打开淘宝后重新开始");
    }
    void back() throws Exception {
        requireTaobao();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        main.post(() -> {
            if (stopped || !PageModel.TAOBAO.equals(foreground())) { result.complete(false); return; }
            result.complete(performGlobalAction(GLOBAL_ACTION_BACK));
        });
        if (!result.get(3, TimeUnit.SECONDS)) throw new LeftTaobao("返回前页面已切换");
        waitMs(900);
    }
    void tap(PageModel.Line line) throws Exception {
        gesture(line.x, line.y, line.x, line.y, 80);
        waitMs(950);
    }
    void swipe(int width, int height, boolean down) throws Exception {
        gesture(width * .5f, height * (down ? .40f : .77f), width * .5f, height * (down ? .84f : .51f), 600);
        waitMs(700);
    }
    private void gesture(float x1, float y1, float x2, float y2, long duration) throws Exception {
        requireTaobao();
        CompletableFuture<Boolean> complete = new CompletableFuture<>();
        main.post(() -> {
            if (stopped || !PageModel.TAOBAO.equals(foreground())) { complete.complete(false); return; }
            Path path = new Path(); path.moveTo(x1, y1);
            if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
            boolean sent = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription value) { complete.complete(true); }
                @Override public void onCancelled(GestureDescription value) { complete.complete(false); }
            }, main);
            if (!sent) complete.complete(false);
        });
        if (!complete.get(4, TimeUnit.SECONDS)) throw new SkipTask("手势被取消或页面已切换");
        checkStopped();
    }
    private Bitmap capture() throws Exception {
        requireTaobao();
        long gap = nextScreenshotAt - android.os.SystemClock.elapsedRealtime();
        if (gap > 0) waitMs(gap);
        nextScreenshotAt = android.os.SystemClock.elapsedRealtime() + 400;
        CompletableFuture<Bitmap> future = new CompletableFuture<>();
        main.post(() -> {
            if (stopped || future.isDone()) { future.cancel(false); return; }
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap copy = null;
                    try (HardwareBuffer buffer = result.getHardwareBuffer()) {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardware != null) {
                            try { copy = hardware.copy(Bitmap.Config.ARGB_8888, false); }
                            finally { hardware.recycle(); }
                        }
                        if (copy == null) future.completeExceptionally(new Attention("无法读取屏幕截图"));
                        else if (!future.complete(copy)) copy.recycle();
                    } catch (Exception e) { future.completeExceptionally(e); }
                }
                @Override public void onFailure(int code) {
                    future.completeExceptionally(new Attention("无法截图（代码 " + code + "），请检查无障碍权限或页面限制"));
                }
            });
        });
        boolean delivered = false;
        try {
            Bitmap bitmap = future.get(6, TimeUnit.SECONDS);
            delivered = true;
            return bitmap;
        }
        finally {
            // A late callback must recycle its image if this worker was stopped.
            if (!delivered && !future.cancel(false)) {
                try {
                    Bitmap abandoned = future.getNow(null);
                    if (abandoned != null) abandoned.recycle();
                } catch (RuntimeException ignored) { }
            }
        }
    }
    PageModel.Page read() throws Exception {
        checkStopped();
        Bitmap bitmap = capture();
        com.google.android.gms.tasks.Task<Text> recognition = null;
        try {
            int width = bitmap.getWidth(), height = bitmap.getHeight();
            if (width > height) throw new Attention("请将手机切换为竖屏后重新开始");
            recognition = recognizer.process(InputImage.fromBitmap(bitmap, 0));
            Text result = Tasks.await(recognition, 12, TimeUnit.SECONDS);
            requireTaobao();
            List<PageModel.Line> lines = new ArrayList<>();
            for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
                addLine(lines, line.getText(), line.getBoundingBox());
                // ML Kit can join a title and its button into a single OCR line.
                StringBuilder leftText = new StringBuilder();
                Rect leftBounds = null;
                List<PageModel.Line> fragments = new ArrayList<>();
                List<PageModel.Line> symbols = new ArrayList<>();
                for (Text.Element element : line.getElements()) {
                    Rect bounds = element.getBoundingBox();
                    if (bounds == null) continue;
                    String word = element.getText().replaceAll("\\s+", "");
                    fragments.add(new PageModel.Line(word, bounds.left, bounds.top, bounds.right, bounds.bottom));
                    // Character boxes locate a label even when ML Kit joins all
                    // icons into one element. Do not estimate positions by text length.
                    for (Text.Symbol symbol : element.getSymbols()) {
                        Rect box = symbol.getBoundingBox();
                        if (box != null && !symbol.getText().trim().isEmpty())
                            symbols.add(new PageModel.Line(symbol.getText(), box.left, box.top, box.right, box.bottom));
                    }
                    if (PageModel.isTaskAction(word)) addLine(lines, word, bounds);
                    if (bounds.centerX() < width * .75) {
                        leftText.append(word);
                        if (leftBounds == null) leftBounds = new Rect(bounds); else leftBounds.union(bounds);
                    }
                }
                fragments.addAll(PageModel.navigationFragments(symbols, width, height));
                for (PageModel.Line navigation : PageModel.navigationFragments(fragments, width, height))
                    addLine(lines, navigation.text, new Rect(Math.round(navigation.left), Math.round(navigation.top),
                        Math.round(navigation.right), Math.round(navigation.bottom)));
                if (leftBounds != null && !leftText.toString().equals(line.getText().replaceAll("\\s+", "")))
                    addLine(lines, leftText.toString(), leftBounds);
            }
            // Native text augments OCR; WebView content is never used by itself.
            collectNative(getRootInActiveWindow(), lines, 0, width, height);
            PageModel.Page page = new PageModel.Page(lines, width, height);
            String verification = page.verification();
            if (verification != null) throw new Attention(verification);
            return page;
        } finally {
            // OCR may still own the pixels if a timeout or Stop interrupted await.
            if (recognition != null && !recognition.isComplete())
                recognition.addOnCompleteListener(getMainExecutor(), ignored -> bitmap.recycle());
            else bitmap.recycle();
        }
    }
    private void collectNative(AccessibilityNodeInfo node, List<PageModel.Line> lines, int depth, int width, int height) {
        if (node == null || depth > 25 || lines.size() > 600 || !node.isVisibleToUser()) return;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        CharSequence description = node.getContentDescription();
        CharSequence text = node.getText();
        // Only current native navigation controls; never old WebView task text.
        for (CharSequence value : new CharSequence[]{text, description}) {
            if (value == null) continue;
            String word = value.toString().replaceAll("\\s+", "");
            if (!word.equals("领淘金币") && !word.equals("我的淘宝") && !word.equals("关闭按钮")) continue;
            PageModel.Line candidate = new PageModel.Line(word, bounds.left, bounds.top, bounds.right, bounds.bottom);
            if (PageModel.validNavigationBounds(candidate, width, height)) addLine(lines, word, bounds);
        }
        for (int n = 0; n < node.getChildCount(); n++) collectNative(node.getChild(n), lines, depth + 1, width, height);
    }
    private static void addLine(List<PageModel.Line> lines, String text, Rect bounds) {
        if (bounds == null || text.trim().isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) return;
        String word = text.replaceAll("\\s+", "");
        for (PageModel.Line existing : lines)
            if (existing.text.equals(word) && Math.abs(existing.x - bounds.centerX()) < 12
                && Math.abs(existing.y - bounds.centerY()) < 12) return;
        lines.add(new PageModel.Line(word, bounds.left, bounds.top, bounds.right, bounds.bottom));
    }
}
