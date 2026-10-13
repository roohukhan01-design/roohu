package com.roohu.phoneagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentAccessibilityService extends AccessibilityService {
    public static volatile AgentAccessibilityService instance;
    private static final String[] BLOCK = {"bank", "paypal", "wallet", "paytm", "phonepe", "easypaisa", "jazzcash", "paisa"};
    private static final String[] RISKY = {"send", "pay", "buy", "purchase", "delete", "remove", "install", "uninstall", "confirm", "transfer", "order", "submit", "post", "call"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> log = new ArrayList<>();
    private volatile boolean running = false;
    private volatile boolean stop = false;
    private volatile String lastScreen = "[]";
    private Thread worker;
    private View stopView;
    private WindowManager wm;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (getSharedPreferences("roohu", MODE_PRIVATE).getBoolean("bot", false)) launchBot();
    }

    @Override
    public boolean onUnbind(Intent i) {
        instance = null;
        botOn = false;
        return super.onUnbind(i);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override
    public void onInterrupt() { }

    private synchronized void addLog(String s) {
        log.add(s);
        if (log.size() > 200) log.remove(0);
    }

    public synchronized String getStatusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("log", new JSONArray(log));
            o.put("screen", new JSONArray(lastScreen));
            o.put("bot", botOn);
            o.put("paired", getSharedPreferences("roohu", MODE_PRIVATE).getLong("paired", 0) != 0);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public void stopNow() {
        stop = true;
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    private void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int d) {
        if (n == null || d > 40 || out.size() > 400) return;
        if (n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, d + 1);
    }

    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> l = new ArrayList<>();
        walk(getRootInActiveWindow(), l, 0);
        return l;
    }

    private String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString();
    }

    private boolean blocked() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        String p = String.valueOf(r.getPackageName()).toLowerCase(Locale.ROOT);
        for (String b : BLOCK) if (p.contains(b)) return true;
        for (AccessibilityNodeInfo n : nodes()) if (n.isPassword()) return true;
        return false;
    }

    private boolean risky(String t) {
        for (String w : t.toLowerCase(Locale.ROOT).split("[^a-z]+"))
            for (String r : RISKY) if (w.equals(r)) return true;
        return false;
    }

    private int readScreen() {
        JSONArray a = new JSONArray();
        try {
            for (AccessibilityNodeInfo n : nodes()) {
                String t = label(n);
                if (t.isEmpty() || a.length() >= 80) continue;
                JSONObject o = new JSONObject();
                o.put("text", t);
                o.put("click", n.isClickable());
                o.put("edit", n.isEditable());
                a.put(o);
            }
        } catch (Exception e) { }
        lastScreen = a.toString();
        return a.length();
    }

    private boolean tap(String want) {
        String w = want.toLowerCase(Locale.ROOT).trim();
        AccessibilityNodeInfo hit = null;
        for (AccessibilityNodeInfo n : nodes()) {
            String t = label(n).toLowerCase(Locale.ROOT);
            if (t.equals(w)) { hit = n; break; }
            if (hit == null && !t.isEmpty() && t.contains(w)) hit = n;
        }
        if (hit == null) return false;
        for (AccessibilityNodeInfo c = hit; c != null; c = c.getParent())
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        hit.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }

    private boolean type(String s) {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) {
            for (AccessibilityNodeInfo n : nodes()) {
                if (n.isEditable()) { f = n; n.performAction(AccessibilityNodeInfo.ACTION_CLICK); break; }
            }
        }
        if (f == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private boolean scroll(boolean down) {
        for (AccessibilityNodeInfo n : nodes()) {
            if (n.isScrollable()) {
                return n.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
        return false;
    }

    private boolean openApp(String name) {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String w = name.toLowerCase(Locale.ROOT).trim();
        String pkg = null;
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String l = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            if (l.equals(w)) { pkg = ri.activityInfo.packageName; break; }
            if (pkg == null && l.contains(w)) pkg = ri.activityInfo.packageName;
        }
        if (pkg == null) return false;
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private WindowManager.LayoutParams lp(int gravity, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.y = y;
        return p;
    }

    private void showStop() {
        main.post(() -> {
            Button b = new Button(this);
            b.setText("STOP");
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.RED);
            b.setOnClickListener(v -> stopNow());
            stopView = b;
            wm.addView(b, lp(Gravity.TOP | Gravity.CENTER_HORIZONTAL, dp(40)));
        });
    }

    private void hideStop() {
        main.post(() -> {
            try { if (stopView != null) wm.removeView(stopView); } catch (Exception e) { }
            stopView = null;
        });
    }

    private boolean confirm(String what) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final View[] box = new View[1];
        main.post(() -> {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setBackgroundColor(Color.rgb(24, 24, 36));
            l.setPadding(dp(20), dp(20), dp(20), dp(20));
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(18);
            t.setText("Allow this action?\n\n" + what);
            Button y = new Button(this);
            y.setText("Yes, do it");
            y.setOnClickListener(v -> { ok.set(true); latch.countDown(); });
            Button n = new Button(this);
            n.setText("No");
            n.setOnClickListener(v -> latch.countDown());
            l.addView(t);
            l.addView(y);
            l.addView(n);
            box[0] = l;
            wm.addView(l, lp(Gravity.CENTER, 0));
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { }
        final View v0 = box[0];
        main.post(() -> { try { if (v0 != null) wm.removeView(v0); } catch (Exception e) { } });
        return ok.get() && !stop;
    }

    private String step(String a, String t) throws InterruptedException {
        if ((a.equals("open") || a.equals("tap")) && t.trim().equalsIgnoreCase("roohu")) return "FAIL: that is me";
        switch (a) {
            case "open":
                for (String b : BLOCK) if (t.toLowerCase(Locale.ROOT).contains(b)) return "FAIL: money apps are blocked";
                return openApp(t) ? "ok" : "FAIL: app not found";
            case "tap": return tap(t) ? "ok" : "FAIL: not found on screen";
            case "type": return type(t) ? "ok" : "FAIL: no text box";
            case "scroll": return scroll(!t.equals("up")) ? "ok" : "FAIL: nothing to scroll";
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK) ? "ok" : "FAIL";
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME) ? "ok" : "FAIL";
            case "wait":
                int s = Math.min(10, Math.max(1, Integer.parseInt(t.isEmpty() ? "2" : t)));
                Thread.sleep(s * 1000L);
                return "ok";
            case "enter": return pressEnter() ? "ok" : "FAIL: could not press enter (needs Android 11 or newer)";
            case "read": return "ok, saw " + readScreen() + " items (see Screen tab)";
            default: return "FAIL: unknown step";
        }
    }

    public synchronized boolean runPlan(String json) {
        if (running) return false;
        JSONArray parsed = null;
        try { parsed = new JSONArray(json); } catch (Exception e) { return false; }
        final JSONArray steps = parsed;
        running = true;
        stop = false;
        log.clear();
        showStop();
        worker = new Thread(() -> {
            try {
                for (int i = 0; i < steps.length() && i < 20 && !stop; i++) {
                    JSONObject s = steps.getJSONObject(i);
                    String a = s.optString("a");
                    String t = s.optString("t");
                    addLog((i + 1) + ". " + a + " " + t);
                    if (!a.equals("wait") && !a.equals("home") && blocked()) {
                        addLog("STOPPED: password or money app on screen.");
                        break;
                    }
                    if (a.equals("tap") && risky(t) && !confirm("tap \"" + t + "\"")) {
                        addLog("Not allowed. Stopped.");
                        break;
                    }
                    String r = step(a, t);
                    addLog("   " + r);
                    if (r.startsWith("FAIL")) break;
                    Thread.sleep(1500);
                }
            } catch (Exception e) { }
            addLog(stop ? "Stopped." : "Finished.");
            running = false;
            hideStop();
        });
        worker.start();
        return true;
    }

    private static final String AGENT_SYS = "You control an Android phone. Each turn you get the goal, what was already done, and the visible screen items. Reply with ONE JSON object only. To act: {\"a\":\"open|tap|type|scroll|back|home|enter|wait\",\"t\":\"...\"} (open: app name; tap: exact visible text; type: text; scroll: up or down; wait: seconds). When finished or impossible: {\"done\":true,\"say\":\"short message\"}. Never touch banking, payment or password screens. Never open or tap the app named Roohu. If the goal is only a question or chat, reply {\"done\":true,\"say\":\"your short answer\"}.";

    private String chat(String base, String key, String model, String sys, String user) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/chat/completions").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(40000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + key);
        JSONObject b = new JSONObject();
        b.put("model", model);
        b.put("temperature", 0);
        b.put("max_tokens", 300);
        JSONArray m = new JSONArray();
        m.put(new JSONObject().put("role", "system").put("content", sys));
        m.put(new JSONObject().put("role", "user").put("content", user));
        b.put("messages", m);
        OutputStream o = c.getOutputStream();
        o.write(b.toString().getBytes("UTF-8"));
        o.close();
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        String r = bo.toString("UTF-8");
        if (code >= 400) throw new Exception("AI error " + code + ": " + r.substring(0, Math.min(100, r.length())));
        return new JSONObject(r).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    private JSONObject parseObj(String s) {
        try {
            return new JSONObject(s.substring(s.indexOf('{'), s.lastIndexOf('}') + 1));
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized boolean runAgent(final String goal, final String base, final String key, final String model) {
        if (running) return false;
        running = true;
        stop = false;
        log.clear();
        showStop();
        worker = new Thread(() -> {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME);
                Thread.sleep(1200);
                StringBuilder hist = new StringBuilder();
                for (int i = 0; i < 12 && !stop; i++) {
                    if (blocked()) { addLog("STOPPED: password or money app on screen."); break; }
                    readScreen();
                    String out = chat(base, key, model, AGENT_SYS, "Goal: " + goal + "\nDone so far: " + hist + "\nScreen items: " + lastScreen);
                    JSONObject o = parseObj(out);
                    if (o == null) { String w = out.trim(); addLog("SAY " + w.substring(0, Math.min(300, w.length()))); break; }
                    if (o.optBoolean("done", false)) { addLog("SAY " + o.optString("say", "Done.")); break; }
                    String a = o.optString("a");
                    String t = o.optString("t");
                    addLog((i + 1) + ". " + a + " " + t);
                    if (a.equals("tap") && risky(t) && !confirm("tap \"" + t + "\"")) { addLog("Not allowed. Stopped."); break; }
                    String r = step(a, t);
                    addLog("   " + r);
                    hist.append(a).append(' ').append(t).append(" -> ").append(r).append("; ");
                    Thread.sleep(1500);
                }
            } catch (Exception e) {
                addLog("   FAIL: " + e.getMessage());
            }
            addLog(stop ? "Stopped." : "Finished.");
            running = false;
            hideStop();
        });
        worker.start();
        return true;
    }

    private boolean pressEnter() {
        if (Build.VERSION.SDK_INT < 30) return false;
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        return f != null && f.performAction(android.R.id.accessibilityActionImeEnter);
    }

    private volatile boolean botOn = false;
    private volatile int botGen = 0;

    private Matcher mt(String re, String s) {
        Matcher m = Pattern.compile(re, Pattern.CASE_INSENSITIVE).matcher(s);
        return m.matches() ? m : null;
    }

    private void add(JSONArray out, String a, String t) {
        try {
            out.put(new JSONObject().put("a", a).put("t", t.trim().replaceAll("^[\"'\u201c\u201d]+|[\"'\u201c\u201d]+$", "")));
        } catch (Exception e) { }
    }

    private void search(JSONArray out, String app, String q) {
        if (app != null) { add(out, "open", app); add(out, "wait", "3"); }
        add(out, "tap", "search");
        add(out, "wait", "1");
        add(out, "type", q);
        add(out, "enter", "");
    }

    private JSONArray parseCmd(String text) {
        JSONArray out = new JSONArray();
        for (String raw : text.split("(?i)\\s*(?:,\\s*)?\\b(?:and then|then)\\b\\s*|\\s*[;\\n]\\s*")) {
            String p = raw.trim().replaceAll("^[,\\s]+|[,.\\s]+$", "");
            if (p.isEmpty()) continue;
            Matcher m;
            if ((m = mt("(?:open|launch|start)\\s+(.+?)\\s+(?:and\\s+)?(?:search|find)(?:\\s+for)?\\s+(.+)", p)) != null) search(out, m.group(1), m.group(2));
            else if ((m = mt("(?:search|find)(?:\\s+for)?\\s+(.+?)\\s+(?:in|on)\\s+(.+)", p)) != null) search(out, m.group(2), m.group(1));
            else if ((m = mt("(?:search|find)(?:\\s+for)?\\s+(.+)", p)) != null) search(out, null, m.group(1));
            else if ((m = mt("(?:open|launch|start)\\s+(.+)", p)) != null) add(out, "open", m.group(1));
            else if ((m = mt("(?:tap|click|press|touch|select)\\s+(?:on\\s+)?(.+)", p)) != null) add(out, "tap", m.group(1));
            else if (mt("(?:press\\s+)?enter", p) != null) add(out, "enter", "");
            else if ((m = mt("(?:type|write|enter)\\s+(.+)", p)) != null) add(out, "type", m.group(1));
            else if ((m = mt("scroll(?:\\s+(up|down))?", p)) != null) add(out, "scroll", m.group(1) == null ? "down" : m.group(1).toLowerCase(Locale.ROOT));
            else if (mt("(?:go\\s+)?back", p) != null) add(out, "back", "");
            else if (mt("(?:go\\s+)?home", p) != null) add(out, "home", "");
            else if ((m = mt("wait(?:\\s+(\\d+))?", p)) != null) add(out, "wait", m.group(1) == null ? "2" : m.group(1));
            else if (mt("(?:read|look)(?:\\s+(?:at\\s+)?(?:the\\s+)?screen)?|what(?:'s|\\s+is)\\s+on\\s+(?:my\\s+|the\\s+)?screen\\??", p) != null) add(out, "read", "");
            else return null;
        }
        return out;
    }

    private String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(40000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
        }
        InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private void tgSend(String token, long chat, String text) {
        try {
            if (text.length() > 3500) text = text.substring(0, 3500);
            http("POST", "https://api.telegram.org/bot" + token + "/sendMessage", "chat_id=" + chat + "&text=" + URLEncoder.encode(text, "UTF-8"));
        } catch (Exception e) { }
    }

    private synchronized String summary() {
        StringBuilder b = new StringBuilder();
        for (String l : log) b.append(l.startsWith("SAY ") ? l.substring(4) : l.trim()).append('\n');
        return b.length() == 0 ? "Done." : b.toString();
    }

    private void botHandle(String token, long chat, String text) throws Exception {
        SharedPreferences sp = getSharedPreferences("roohu", MODE_PRIVATE);
        long paired = sp.getLong("paired", 0);
        String code = sp.getString("code", "");
        if (text.startsWith("/pair ")) {
            if (paired == 0 && !code.isEmpty() && text.substring(6).trim().equals(code)) {
                sp.edit().putLong("paired", chat).putString("code", "").apply();
                tgSend(token, chat, "Paired. I am Roohu. Send a command, /screen, /stop or /help.");
            }
            return;
        }
        if (paired == 0 || chat != paired) return;
        String tl = text.toLowerCase(Locale.ROOT);
        if (tl.equals("/stop")) { stopNow(); tgSend(token, chat, "Stopped."); return; }
        if (tl.equals("/start") || tl.equals("/help")) {
            tgSend(token, chat, "Try: open Settings, search cats on YouTube, tap X, type hi, scroll down, back, home, /screen, /stop. Plain words work if an AI is saved in the app.");
            return;
        }
        if (tl.equals("/screen")) {
            if (blocked()) { tgSend(token, chat, "I will not read this screen (password or money app)."); return; }
            readScreen();
            JSONArray a = new JSONArray(lastScreen);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length() && i < 40; i++) b.append(a.getJSONObject(i).optString("text")).append('\n');
            tgSend(token, chat, b.length() == 0 ? "Nothing readable." : b.toString());
            return;
        }
        if (running) { tgSend(token, chat, "I am busy. Send /stop first."); return; }
        JSONArray steps = parseCmd(text);
        boolean ok;
        if (steps != null && steps.length() > 0) {
            ok = runPlan(steps.toString());
        } else {
            String base = sp.getString("base", ""), key = sp.getString("key", ""), model = sp.getString("model", "");
            if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
                tgSend(token, chat, "I do not understand that. Save an AI in the app for plain words.");
                return;
            }
            ok = runAgent(text, base, key, model);
        }
        if (!ok) return;
        for (int i = 0; i < 240 && running; i++) Thread.sleep(500);
        tgSend(token, chat, summary());
    }

    private void botLoop(int g) {
        long offset = 0;
        while (botOn && g == botGen) {
            try {
                String token = getSharedPreferences("roohu", MODE_PRIVATE).getString("token", "");
                JSONArray u = new JSONObject(http("GET", "https://api.telegram.org/bot" + token + "/getUpdates?timeout=25&offset=" + offset, null)).getJSONArray("result");
                for (int i = 0; i < u.length(); i++) {
                    JSONObject up = u.getJSONObject(i);
                    offset = up.getLong("update_id") + 1;
                    JSONObject m = up.optJSONObject("message");
                    if (m == null) continue;
                    botHandle(token, m.getJSONObject("chat").getLong("id"), m.optString("text", "").trim());
                }
            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private synchronized void launchBot() {
        final int g = ++botGen;
        botOn = true;
        new Thread(() -> botLoop(g)).start();
    }

    public void startBot(String token, String code, String base, String key, String model) {
        getSharedPreferences("roohu", MODE_PRIVATE).edit().putString("token", token).putString("code", code).putLong("paired", 0)
                .putString("base", base).putString("key", key).putString("model", model).putBoolean("bot", true).apply();
        launchBot();
    }

    public void stopBot() {
        botOn = false;
        botGen++;
        getSharedPreferences("roohu", MODE_PRIVATE).edit().putBoolean("bot", false).apply();
    }
}
