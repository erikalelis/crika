package app.crika;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Crika para Android: muestra la interfaz de Crika (la misma de la versión web)
 * dentro de una ventana web y le da acceso, con permiso del usuario,
 * a los archivos del teléfono (WhatsApp y el resto del almacenamiento).
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + HOST;
    private static final int REQ_CHOOSER = 41;
    private static final int REQ_PERM = 42;

    private WebView web;
    private ValueCallback<Uri[]> chooserCb;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final WaFiles wa = new WaFiles();
    private final List<String> pendingShared = new ArrayList<>();
    private boolean pageReady = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? Color.parseColor("#0e0e16") : Color.parseColor("#f5f5fa");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (!night) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                if (!night) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        web = new WebView(this);
        web.setBackgroundColor(bg);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "CrikaNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return serve(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                notifyShared();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooserCb != null) chooserCb.onReceiveValue(null);
                chooserCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    chooserCb = null;
                    return false;
                }
                return true;
            }
        });

        handleIntent(getIntent());
        web.loadUrl(ORIGIN + "/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        notifyShared();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_CHOOSER && chooserCb != null) {
            chooserCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooserCb = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (web != null) web.evaluateJavascript("window.__natFocus&&window.__natFocus()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null && pageReady) web.evaluateJavascript("window.__natFocus&&window.__natFocus()", null);
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.__back&&window.__back())===true", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (!"true".equals(v)) finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    /* ---------- contenido compartido desde otras apps (por ejemplo WhatsApp) ---------- */

    private File sharedDir() {
        File d = new File(getCacheDir(), "shared");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action)) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;
        try {
            File dir = sharedDir();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            String name = "compartido";
            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (c != null && c.moveToFirst()) name = c.getString(0);
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            name = name.replaceAll("[^A-Za-z0-9._-]", "_");
            if (name.isEmpty()) name = "compartido";
            File out = new File(dir, name);
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
                if (in == null) return;
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            pendingShared.clear();
            pendingShared.add(name);
        } catch (Exception ignored) {
        }
    }

    private void notifyShared() {
        if (web != null && pageReady && !pendingShared.isEmpty()) {
            web.evaluateJavascript("window.__natShared&&window.__natShared()", null);
        }
    }

    /* ---------- servidor local: la app, miniaturas y archivos ---------- */

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse serve(Uri u) {
        if (!HOST.equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        try {
            if (path.equals("/__t")) {
                String p = u.getQueryParameter("p");
                int size = 260;
                try {
                    size = Math.min(600, Math.max(48, Integer.parseInt(u.getQueryParameter("s"))));
                } catch (Exception ignored) {
                }
                byte[] jpg = wa.thumbnail(p, size);
                if (jpg == null) return notFound();
                Map<String, String> h = new HashMap<>();
                h.put("Cache-Control", "max-age=86400");
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(jpg));
            }
            if (path.equals("/__f")) {
                String p = u.getQueryParameter("p");
                if (!wa.allowed(p)) return notFound();
                File f = new File(p);
                if (!f.isFile()) return notFound();
                return new WebResourceResponse(WaFiles.mimeOf(f.getName()), null, 200, "OK", new HashMap<String, String>(), new FileInputStream(f));
            }
            if (path.equals("/__c")) {
                String n = u.getQueryParameter("n");
                if (n == null || n.contains("/") || n.contains("..")) return notFound();
                File f = new File(sharedDir(), n);
                if (!f.isFile()) return notFound();
                return new WebResourceResponse(WaFiles.mimeOf(f.getName()), null, 200, "OK", new HashMap<String, String>(), new FileInputStream(f));
            }
            String asset = path.substring(1);
            InputStream in = getAssets().open(asset);
            String ext = MimeTypeMap.getFileExtensionFromUrl(asset).toLowerCase(Locale.ROOT);
            String mime;
            switch (ext) {
                case "html": mime = "text/html"; break;
                case "js": mime = "application/javascript"; break;
                case "css": mime = "text/css"; break;
                case "svg": mime = "image/svg+xml"; break;
                case "png": mime = "image/png"; break;
                case "webmanifest": mime = "application/manifest+json"; break;
                default: mime = "application/octet-stream";
            }
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (IOException e) {
            return notFound();
        } catch (Exception e) {
            return notFound();
        }
    }

    /* ---------- puente hacia la interfaz ---------- */

    private void reply(final int id, final boolean ok, final String payload) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript("window.__nat(" + id + "," + ok + "," + JSONObject.quote(payload) + ")", null);
            }
        });
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT <= 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    public final class Bridge {

        @JavascriptInterface
        public boolean hasAccess() {
            return MainActivity.this.hasAccess();
        }

        @JavascriptInterface
        public void requestAccess() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= 30) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    } else {
                        requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                    }
                }
            });
        }

        @JavascriptInterface
        public void scan(final int id, final boolean phone) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        reply(id, true, wa.scan(phone).toString());
                    } catch (Throwable t) {
                        reply(id, false, String.valueOf(t.getMessage()));
                    }
                }
            });
        }

        @JavascriptInterface
        public void dupes(final int id, final boolean phone) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        reply(id, true, wa.duplicates(phone).toString());
                    } catch (Throwable t) {
                        reply(id, false, String.valueOf(t.getMessage()));
                    }
                }
            });
        }

        @JavascriptInterface
        public void del(final int id, final String json) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<String> deleted = new ArrayList<>();
                        JSONObject res = wa.delete(new JSONArray(json), deleted);
                        if (!deleted.isEmpty()) {
                            android.media.MediaScannerConnection.scanFile(MainActivity.this, deleted.toArray(new String[0]), null, null);
                        }
                        reply(id, true, res.toString());
                    } catch (Throwable t) {
                        reply(id, false, String.valueOf(t.getMessage()));
                    }
                }
            });
        }

        /** Abre la app de Google Fotos (para revisar el respaldo antes de borrar fotos). */
        @JavascriptInterface
        public boolean openPhotos() {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.photos");
                if (i == null) return false;
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Número de versión de esta instalación de Crika. */
        @JavascriptInterface
        public int versionCode() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            } catch (Exception e) {
                return 0;
            }
        }

        /** Consulta en GitHub cuál es la última versión publicada. */
        @JavascriptInterface
        public void checkUpdate(final int id) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        reply(id, true, Updater.latest().toString());
                    } catch (Throwable t) {
                        reply(id, false, String.valueOf(t.getMessage()));
                    }
                }
            });
        }

        /** Descarga la versión nueva y la entrega al instalador de Android. */
        @JavascriptInterface
        public void installUpdate(final int id, final String url) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                            reply(id, false, "perm");
                            return;
                        }
                        File apk = Updater.download(MainActivity.this, url, new Updater.Progress() {
                            @Override
                            public void on(final int percent) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (web != null) web.evaluateJavascript("window.__updProgress&&window.__updProgress(" + percent + ")", null);
                                    }
                                });
                            }
                        });
                        Updater.install(MainActivity.this, apk);
                        reply(id, true, "ok");
                    } catch (Throwable t) {
                        reply(id, false, String.valueOf(t.getMessage()));
                    }
                }
            });
        }

        /** Nombre del archivo compartido con Crika (o vacío). Se pide una sola vez. */
        @JavascriptInterface
        public String takeShared() {
            if (pendingShared.isEmpty()) return "";
            String n = pendingShared.get(0);
            pendingShared.clear();
            return n;
        }
    }
}
