package app.crika;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Actualizaciones de Crika: mira si en GitHub hay una versión más nueva,
 * la descarga y la instala encima de la actual (sin desinstalar nada).
 */
final class Updater {

    private static final String API = "https://api.github.com/repos/erikalelis/crika/releases/latest";
    /** Solo se descargan archivos publicados en las Releases de este repositorio. */
    private static final String PREFIX = "https://github.com/erikalelis/crika/releases/download/";

    interface Progress {
        void on(int percent);
    }

    /** Última versión publicada: {"code":N,"name":"...","url":"..."}. */
    static JSONObject latest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Crika-Android");
        try {
            if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            JSONObject j = new JSONObject(bos.toString("UTF-8"));
            String tag = j.optString("tag_name", "").replaceAll("[^0-9]", "");
            int code = Integer.parseInt(tag);
            String url = "";
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").endsWith(".apk")) {
                        url = a.optString("browser_download_url", "");
                        break;
                    }
                }
            }
            JSONObject out = new JSONObject();
            out.put("code", code);
            out.put("name", j.optString("name", ""));
            out.put("url", url);
            return out;
        } finally {
            c.disconnect();
        }
    }

    static File download(Context ctx, String url, Progress progress) throws IOException {
        if (url == null || !url.startsWith(PREFIX)) throw new IOException("url no permitida");
        File dir = new File(ctx.getCacheDir(), "update");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "Crika.apk");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Crika-Android");
        try {
            if (c.getResponseCode() != 200) throw new IOException("http " + c.getResponseCode());
            long total = c.getContentLengthLong();
            long done = 0;
            int last = -1;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != last) {
                            last = pct;
                            progress.on(pct);
                        }
                    }
                }
            }
            if (out.length() < 100 * 1024) throw new IOException("archivo incompleto");
            return out;
        } finally {
            c.disconnect();
        }
    }

    /** Entrega el APK descargado al instalador de Android (pide confirmación al usuario). */
    static void install(Context ctx, File apk) throws IOException {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk); OutputStream os = session.openWrite("Crika.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                session.fsync(os);
            }
            Intent i = new Intent(ctx, InstallReceiver.class);
            i.setAction("app.crika.INSTALL_RESULT");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i, flags);
            session.commit(pi.getIntentSender());
        } finally {
            session.close();
        }
    }
}
