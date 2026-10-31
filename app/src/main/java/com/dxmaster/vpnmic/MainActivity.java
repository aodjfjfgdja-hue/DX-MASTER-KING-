package com.dxmaster.vpnmic;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    private static final String FB_URL = "https://www.facebook.com/";
    private static final String MSG_URL = "https://www.facebook.com/messages/";
    private static final String EXT_HOST = "dxext.local";
    // Desktop user agent: Messenger calls work on the desktop web version
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int REQ_ZIP = 11;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int RED = 0xFFFF1744;
    private static final int BLUE = 0xFF2979FF;
    private static final int GREEN = 0xFF00C853;
    private static final int DARK = 0xFF0A0A1A;

    private SharedPreferences prefs;
    private FrameLayout root;
    private WebView web;
    private WebView popup;
    private LinearLayout popupHolder;
    private LinearLayout boostPanel;
    private TextView popupTitle;
    private TextView gainTv;
    private TextView onBtn;
    private TextView fabExt;
    private String coreJs = "";
    private boolean redirected = false;

    // built-in mic booster state
    private int gain = 300;
    private boolean boostOn = true;

    // ZIP extension state
    private Map<String, byte[]> extFiles = null;
    private String extName = "";
    private String manifestJson = "{}";
    private String popupPath = null;
    private final List<String> csJs = new ArrayList<>();
    private final List<String> csCss = new ArrayList<>();
    private final List<String> bgJs = new ArrayList<>();
    private JSONObject store = new JSONObject();

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        prefs = getSharedPreferences("dx", MODE_PRIVATE);
        gain = prefs.getInt("gain", 300);
        boostOn = prefs.getBoolean("on", true);
        try {
            store = new JSONObject(prefs.getString("store", "{}"));
        } catch (Exception e) {
            store = new JSONObject();
        }
        coreJs = readAsset("dx_core.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(DARK);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(buildBar(), new LinearLayout.LayoutParams(MATCH, dp(52)));
        web = new WebView(this);
        col.addView(web, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));

        popup = new WebView(this);
        buildOverlay();

        final View splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
        splash.postDelayed(() -> splash.animate().alpha(0f).setDuration(500)
                .withEndAction(() -> splash.setVisibility(View.GONE)).start(), 1800);

        setupMain();
        setupPopup();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA}, 1);

        loadSavedExtension();
        applyBoostUi();

        String c = CookieManager.getInstance().getCookie("https://www.facebook.com");
        if (c != null && c.contains("c_user=")) {
            redirected = true;
            web.loadUrl(MSG_URL);
        } else {
            web.loadUrl(FB_URL);
        }
    }

    @Override
    public void onBackPressed() {
        if (popupHolder.getVisibility() == View.VISIBLE) {
            showPopup(false);
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ WebViews

    private void setupMain() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(false), "DXNative");
        web.setWebChromeClient(new PermClient(false));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String sc = r.getUrl().getScheme();
                return !("http".equals(sc) || "https".equals(sc));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                injectMain(v);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                injectMain(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                injectMain(v);
                CookieManager.getInstance().flush();
                String c = CookieManager.getInstance().getCookie("https://www.facebook.com");
                boolean logged = c != null && c.contains("c_user=");
                if (!logged) redirected = false;
                // Facebook logged in -> open Messenger automatically
                if (logged && !redirected && url != null && url.contains("facebook.com")
                        && !url.contains("/messages")) {
                    redirected = true;
                    v.loadUrl(MSG_URL);
                }
            }
        });
    }

    private void setupPopup() {
        WebSettings s = popup.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setAllowFileAccess(false);
        popup.setBackgroundColor(DARK);
        popup.addJavascriptInterface(new Bridge(true), "DXNative");
        popup.setWebChromeClient(new PermClient(true));
        popup.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!EXT_HOST.equals(u.getHost())) return null;
                String p = u.getPath();
                if (p == null || p.isEmpty() || p.equals("/")) {
                    p = "/" + (popupPath == null ? "index.html" : popupPath);
                }
                p = p.substring(1);
                if (p.equals("__dx_core.js")) {
                    return resp("application/javascript", coreJs.getBytes(StandardCharsets.UTF_8));
                }
                byte[] data = extFiles == null ? null : extFiles.get(p);
                if (data == null) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                }
                String mime = mimeOf(p);
                if (mime.equals("text/html")) data = injectShim(data);
                return resp(mime, data);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !EXT_HOST.equals(r.getUrl().getHost());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (extFiles == null || url == null || !url.startsWith("https://" + EXT_HOST)) return;
                for (String js : bgJs) {
                    byte[] d = extFiles.get(js);
                    if (d == null) continue;
                    String code = "(function(){window.__dxBG=window.__dxBG||{};var k="
                            + JSONObject.quote(js) + ";if(window.__dxBG[k])return;window.__dxBG[k]=1;"
                            + "window.__dxInBG=true;try{\n" + new String(d, StandardCharsets.UTF_8)
                            + "\n}catch(e){console.log('DX bg error',e)}window.__dxInBG=false;})();";
                    v.evaluateJavascript(code, null);
                }
            }
        });
    }

    private class PermClient extends WebChromeClient {
        private final boolean isPopup;

        PermClient(boolean isPopup) {
            this.isPopup = isPopup;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                String h = request.getOrigin() == null ? null : request.getOrigin().getHost();
                boolean ok = h != null && (isPopup ? EXT_HOST.equals(h)
                        : (h.endsWith("facebook.com") || h.endsWith("messenger.com")));
                if (ok) request.grant(request.getResources());
                else request.deny();
            });
        }
    }

    // ------------------------------------------------------------------ JS bridge

    private class Bridge {
        private final boolean fromPopup;

        Bridge(boolean fromPopup) {
            this.fromPopup = fromPopup;
        }

        @JavascriptInterface
        public boolean hasExt() {
            return extFiles != null;
        }

        @JavascriptInterface
        public String manifest() {
            return manifestJson;
        }

        @JavascriptInterface
        public void send(String json) {
            toOther("__dxRecv", json, fromPopup);
        }

        @JavascriptInterface
        public void reply(String json) {
            toOther("__dxReply", json, fromPopup);
        }

        @JavascriptInterface
        public boolean runFile(String path) {
            if (fromPopup || extFiles == null) return false;
            byte[] d = extFiles.get(path);
            if (d == null) return false;
            final String code = "(function(){window.__dxWA=window.__dxWA||{};var k="
                    + JSONObject.quote(path) + ";if(window.__dxWA[k])return;window.__dxWA[k]=1;try{\n"
                    + new String(d, StandardCharsets.UTF_8)
                    + "\n}catch(e){window.__dxWA[k]=0;console.log('DX file error',e)}})();";
            runOnUiThread(() -> web.evaluateJavascript(code, null));
            return true;
        }

        @JavascriptInterface
        public String storageGet() {
            synchronized (MainActivity.this) {
                return store.toString();
            }
        }

        @JavascriptInterface
        public void storageSet(String json) {
            storeChange(json, false);
        }

        @JavascriptInterface
        public void storageRemove(String json) {
            storeChange(json, true);
        }
    }

    private void toOther(final String fn, final String json, final boolean fromPopup) {
        runOnUiThread(() -> {
            WebView target = fromPopup ? web : popup;
            target.evaluateJavascript("window." + fn + "&&window." + fn + "("
                    + JSONObject.quote(json) + ");", null);
        });
    }

    private void storeChange(String json, boolean remove) {
        try {
            JSONObject changes = new JSONObject();
            synchronized (this) {
                if (remove) {
                    JSONArray ks = new JSONArray(json);
                    for (int i = 0; i < ks.length(); i++) {
                        String k = ks.getString(i);
                        if (store.has(k)) {
                            JSONObject c = new JSONObject();
                            c.put("oldValue", store.get(k));
                            changes.put(k, c);
                            store.remove(k);
                        }
                    }
                } else {
                    JSONObject items = new JSONObject(json);
                    Iterator<String> it = items.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONObject c = new JSONObject();
                        if (store.has(k)) c.put("oldValue", store.get(k));
                        c.put("newValue", items.get(k));
                        changes.put(k, c);
                        store.put(k, items.get(k));
                    }
                }
                prefs.edit().putString("store", store.toString()).apply();
            }
            if (changes.length() > 0) {
                final String js = "window.__dxStorage&&window.__dxStorage("
                        + JSONObject.quote(changes.toString()) + ");";
                runOnUiThread(() -> {
                    web.evaluateJavascript(js, null);
                    popup.evaluateJavascript(js, null);
                });
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ injection

    private void injectMain(WebView v) {
        v.evaluateJavascript(coreJs, null);
        v.evaluateJavascript("window.__dxSet&&window.__dxSet(" + (boostOn ? gain / 100.0 : 1.0)
                + ");", null);
        if (extFiles == null) return;
        for (String css : csCss) {
            byte[] d = extFiles.get(css);
            if (d == null) continue;
            v.evaluateJavascript("window.__dxCss&&window.__dxCss(" + JSONObject.quote(css) + ","
                    + JSONObject.quote(new String(d, StandardCharsets.UTF_8)) + ");", null);
        }
        for (String js : csJs) {
            byte[] d = extFiles.get(js);
            if (d == null) continue;
            String code = "(function(){window.__dxCS=window.__dxCS||{};var k=" + JSONObject.quote(js)
                    + ";if(window.__dxCS[k])return;window.__dxCS[k]=1;try{\n"
                    + new String(d, StandardCharsets.UTF_8)
                    + "\n}catch(e){window.__dxCS[k]=0;console.log('DX ext error',e)}})();";
            v.evaluateJavascript(code, null);
        }
    }

    private WebResourceResponse resp(String mime, byte[] data) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(data));
        r.setResponseHeaders(h);
        return r;
    }

    private byte[] injectShim(byte[] data) {
        String h = new String(data, StandardCharsets.UTF_8);
        String tag = "<script src=\"https://" + EXT_HOST + "/__dx_core.js\"></script>";
        Matcher m = Pattern.compile("(?i)<head(\\s[^>]*)?>").matcher(h);
        if (m.find()) {
            h = h.substring(0, m.end()) + tag + h.substring(m.end());
        } else {
            m = Pattern.compile("(?i)<html(\\s[^>]*)?>").matcher(h);
            if (m.find()) h = h.substring(0, m.end()) + tag + h.substring(m.end());
            else h = tag + h;
        }
        return h.getBytes(StandardCharsets.UTF_8);
    }

    private static String mimeOf(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html";
        if (l.endsWith(".js") || l.endsWith(".mjs")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".xml")) return "text/xml";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".gif")) return "image/gif";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".ico")) return "image/x-icon";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".wav")) return "audio/wav";
        if (l.endsWith(".ogg")) return "audio/ogg";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".woff")) return "font/woff";
        if (l.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ ZIP extension

    private void onExtTap() {
        if (extFiles == null) {
            pickZip();
        } else if (popupPath == null) {
            toast("This ZIP has no popup page (content script only)");
        } else {
            showPopup(popupHolder.getVisibility() != View.VISIBLE);
        }
    }

    private void showExtMenu() {
        String[] items = extFiles == null
                ? new String[]{"Load ZIP"}
                : new String[]{"Load another ZIP", "Remove ZIP (" + extName + ")"};
        new AlertDialog.Builder(this)
                .setTitle("DX MASTER \u2022 Extension")
                .setItems(items, (d, i) -> {
                    if (i == 0) pickZip();
                    else removeExt();
                })
                .show();
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_ZIP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ZIP || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            byte[] bytes;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bytes = in == null ? null : readAll(in);
            } catch (Exception e) {
                bytes = null;
            }
            final byte[] fb = bytes;
            runOnUiThread(() -> {
                if (fb == null || !loadZipBytes(fb)) {
                    toast("ZIP not valid (need manifest.json or an .html page)");
                    return;
                }
                try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "ext.zip"))) {
                    o.write(fb);
                } catch (Exception ignored) {
                }
                boostOn = false; // the ZIP mic takes over from the built-in booster
                applyBoost();
                injectMain(web);
                showPopup(popupPath != null);
                toast("DX MASTER: " + extName + " loaded");
            });
        }).start();
    }

    private void loadSavedExtension() {
        File f = new File(getFilesDir(), "ext.zip");
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            loadZipBytes(readAll(in));
        } catch (Exception ignored) {
        }
    }

    private void removeExt() {
        extFiles = null;
        popupPath = null;
        extName = "";
        manifestJson = "{}";
        csJs.clear();
        csCss.clear();
        bgJs.clear();
        synchronized (this) {
            store = new JSONObject();
        }
        prefs.edit().remove("store").apply();
        new File(getFilesDir(), "ext.zip").delete();
        popup.loadUrl("about:blank");
        showPopup(false);
        fabExt.setBackground(circleBg(RED));
        web.reload();
        toast("ZIP removed");
    }

    private boolean loadZipBytes(byte[] data) {
        Map<String, byte[]> raw = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            long total = 0;
            byte[] buf = new byte[8192];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().replace('\\', '/');
                if (n.startsWith("/") || n.contains("../") || n.startsWith("__MACOSX/")) continue;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int r;
                while ((r = z.read(buf)) > 0) {
                    bo.write(buf, 0, r);
                    total += r;
                    if (total > 60000000L) return false;
                }
                raw.put(n, bo.toByteArray());
            }
        } catch (Exception ex) {
            return false;
        }
        if (raw.isEmpty()) return false;

        String manifestKey = null;
        for (String k : raw.keySet()) {
            if (k.equals("manifest.json") || k.endsWith("/manifest.json")) {
                if (manifestKey == null || k.length() < manifestKey.length()) manifestKey = k;
            }
        }
        String base;
        if (manifestKey != null) {
            base = manifestKey.substring(0, manifestKey.length() - "manifest.json".length());
        } else {
            String bestHtml = null;
            for (String k : raw.keySet()) {
                String lk = k.toLowerCase();
                if (!(lk.endsWith(".html") || lk.endsWith(".htm"))) continue;
                if (bestHtml == null || depth(k) < depth(bestHtml)
                        || (depth(k) == depth(bestHtml) && rank(k) < rank(bestHtml))) {
                    bestHtml = k;
                }
            }
            if (bestHtml == null) return false;
            base = bestHtml.substring(0, bestHtml.lastIndexOf('/') + 1);
        }

        Map<String, byte[]> files = new HashMap<>();
        for (Map.Entry<String, byte[]> en : raw.entrySet()) {
            if (en.getKey().startsWith(base)) {
                files.put(en.getKey().substring(base.length()), en.getValue());
            }
        }

        JSONObject m = new JSONObject();
        byte[] mb = files.get("manifest.json");
        if (mb != null) {
            try {
                String s = new String(mb, StandardCharsets.UTF_8);
                if (s.startsWith("\uFEFF")) s = s.substring(1);
                m = new JSONObject(s);
            } catch (Exception ignored) {
                m = new JSONObject();
            }
        }

        String nm = m.optString("name", "");
        if (nm.isEmpty() || nm.startsWith("__MSG")) nm = "ZIP Extension";

        String pp = null;
        String[] actionKeys = {"action", "browser_action", "page_action"};
        for (String key : actionKeys) {
            JSONObject o = m.optJSONObject(key);
            if (o != null && o.optString("default_popup", "").length() > 0) {
                pp = o.optString("default_popup");
                break;
            }
        }
        if (pp != null) pp = pp.replaceFirst("^\\.?/", "");
        if (pp == null || !files.containsKey(pp)) {
            pp = null;
            String[] cand = {"popup.html", "index.html"};
            for (String c : cand) {
                if (files.containsKey(c)) {
                    pp = c;
                    break;
                }
            }
            if (pp == null) {
                for (String k : files.keySet()) {
                    String lk = k.toLowerCase();
                    if (lk.endsWith(".html") || lk.endsWith(".htm")) {
                        pp = k;
                        break;
                    }
                }
            }
        }

        List<String> js = new ArrayList<>();
        List<String> css = new ArrayList<>();
        JSONArray cs = m.optJSONArray("content_scripts");
        if (cs != null) {
            for (int i = 0; i < cs.length(); i++) {
                JSONObject o = cs.optJSONObject(i);
                if (o == null) continue;
                addPaths(o.optJSONArray("js"), js);
                addPaths(o.optJSONArray("css"), css);
            }
        }
        List<String> bg = new ArrayList<>();
        JSONObject bo = m.optJSONObject("background");
        if (bo != null) {
            addPaths(bo.optJSONArray("scripts"), bg);
            String sw = bo.optString("service_worker", "");
            if (bg.isEmpty() && !sw.isEmpty()) bg.add(sw.replaceFirst("^\\.?/", ""));
        }
        if (pp == null && js.isEmpty()) return false;

        extFiles = files;
        extName = nm;
        manifestJson = m.toString();
        popupPath = pp;
        csJs.clear();
        csJs.addAll(js);
        csCss.clear();
        csCss.addAll(css);
        bgJs.clear();
        bgJs.addAll(bg);
        popupTitle.setText("DX MASTER \u2022 " + nm);
        fabExt.setBackground(circleBg(GREEN));
        if (pp != null) popup.loadUrl("https://" + EXT_HOST + "/" + Uri.encode(pp, "/"));
        else popup.loadUrl("about:blank");
        return true;
    }

    private static void addPaths(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) out.add(s.replaceFirst("^\\.?/", ""));
        }
    }

    private static int depth(String k) {
        int n = 0;
        for (int i = 0; i < k.length(); i++) if (k.charAt(i) == '/') n++;
        return n;
    }

    private static int rank(String k) {
        String n = k.substring(k.lastIndexOf('/') + 1).toLowerCase();
        return n.equals("index.html") ? 0 : n.equals("popup.html") ? 1 : 2;
    }

    private void showPopup(boolean show) {
        popupHolder.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ built-in booster

    private void applyBoost() {
        applyBoostUi();
        web.evaluateJavascript("window.__dxSet&&window.__dxSet(" + (boostOn ? gain / 100.0 : 1.0)
                + ");", null);
        prefs.edit().putInt("gain", gain).putBoolean("on", boostOn).apply();
    }

    private void applyBoostUi() {
        gainTv.setText(gain + "%");
        onBtn.setText(boostOn ? "ON" : "OFF");
        onBtn.setAlpha(boostOn ? 1f : 0.5f);
    }

    // ------------------------------------------------------------------ UI

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(8), 0);
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFB71C1C, DARK, 0xFF1565C0}));

        TextView title = new TextView(this);
        title.setText("DX MASTER");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.15f);
        title.setShadowLayer(12, 0, 0, BLUE);
        bar.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));

        bar.addView(barBtn("Facebook", v -> {
            redirected = true;
            web.loadUrl(FB_URL);
        }));
        bar.addView(barBtn("Messenger", v -> web.loadUrl(MSG_URL)));
        bar.addView(barBtn("\u27F3", v -> web.reload()));
        return bar;
    }

    private TextView barBtn(String t, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Color.WHITE);
        v.setTextSize(12);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x33FFFFFF);
        bg.setStroke(dp(1), BLUE);
        v.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMargins(dp(4), 0, 0, 0);
        v.setLayoutParams(lp);
        v.setOnClickListener(l);
        return v;
    }

    private void buildOverlay() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.CENTER_HORIZONTAL);

        // puzzle button: load / open the ZIP extension (long press = menu)
        fabExt = circleBtn("\uD83E\uDDE9", 20, v -> onExtTap());
        fabExt.setOnLongClickListener(v -> {
            showExtMenu();
            return true;
        });
        pulse(fabExt);
        side.addView(fabExt, btnLp());

        // mic button: show / hide the built-in booster
        TextView fabMic = circleBtn("\uD83C\uDFA4", 20, v -> boostPanel.setVisibility(
                boostPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        pulse(fabMic);
        side.addView(fabMic, btnLp());

        boostPanel = new LinearLayout(this);
        boostPanel.setOrientation(LinearLayout.VERTICAL);
        boostPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        boostPanel.setPadding(dp(6), dp(8), dp(6), dp(8));
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(0xE60A0A1A);
        pbg.setCornerRadius(dp(18));
        pbg.setStroke(dp(2), BLUE);
        boostPanel.setBackground(pbg);
        boostPanel.addView(label("DX\nMASTER", 11, Color.WHITE));
        boostPanel.addView(circleBtn("+", 22, v -> {
            gain = Math.min(1000, gain + 50);
            applyBoost();
        }), btnLp());
        gainTv = label("300%", 14, 0xFF82B1FF);
        boostPanel.addView(gainTv);
        boostPanel.addView(circleBtn("\u2212", 22, v -> {
            gain = Math.max(100, gain - 50);
            applyBoost();
        }), btnLp());
        onBtn = circleBtn("ON", 12, v -> {
            boostOn = !boostOn;
            applyBoost();
        });
        boostPanel.addView(onBtn, btnLp());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(64), WRAP);
        plp.setMargins(0, dp(6), 0, 0);
        side.addView(boostPanel, plp);

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.END | Gravity.CENTER_VERTICAL);
        slp.setMargins(0, dp(40), dp(6), 0);
        root.addView(side, slp);

        // extension popup window
        popupHolder = new LinearLayout(this);
        popupHolder.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(DARK);
        hbg.setCornerRadius(dp(16));
        hbg.setStroke(dp(2), RED);
        popupHolder.setBackground(hbg);
        popupHolder.setPadding(dp(3), dp(3), dp(3), dp(3));
        popupHolder.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), 0, dp(4), 0);
        popupTitle = label("DX MASTER", 13, Color.WHITE);
        popupTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        head.addView(popupTitle, new LinearLayout.LayoutParams(0, MATCH, 1f));
        head.addView(smallBtn("\u27F3", v -> popup.reload()), new LinearLayout.LayoutParams(dp(38), MATCH));
        head.addView(smallBtn("\u2715", v -> showPopup(false)), new LinearLayout.LayoutParams(dp(38), MATCH));
        popupHolder.addView(head, new LinearLayout.LayoutParams(MATCH, dp(38)));
        popupHolder.addView(popup, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                Math.min(sw - dp(76), dp(380)), (int) (sh * 0.7), Gravity.TOP | Gravity.START);
        hlp.setMargins(dp(8), dp(64), 0, 0);
        root.addView(popupHolder, hlp);
    }

    private View buildSplash() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(DARK);
        l.setClickable(true);

        TextView t = new TextView(this);
        t.setText("DX MASTER");
        t.setTextColor(RED);
        t.setTextSize(38);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.2f);
        t.setShadowLayer(30, 0, 0, BLUE);
        l.addView(t);

        TextView s = new TextView(this);
        s.setText("VPN MIC  \u2022  VOLUME BOOSTER");
        s.setTextColor(0xFF82B1FF);
        s.setTextSize(13);
        s.setLetterSpacing(0.2f);
        s.setPadding(0, dp(10), 0, 0);
        l.addView(s);
        return l;
    }

    private GradientDrawable circleBg(int c1) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{c1, BLUE});
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(1), 0x88FFFFFF);
        return g;
    }

    private TextView circleBtn(String text, int sp, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sp);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setBackground(circleBg(RED));
        v.setOnClickListener(l);
        return v;
    }

    private TextView smallBtn(String text, View.OnClickListener l) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(16);
        v.setGravity(Gravity.CENTER);
        v.setOnClickListener(l);
        return v;
    }

    private TextView label(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    private void pulse(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 1f, 0.55f);
        a.setDuration(900);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total > 80000000L) throw new Exception("too big");
        }
        return out.toByteArray();
    }
}
