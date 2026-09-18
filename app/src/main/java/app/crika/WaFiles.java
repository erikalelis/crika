package app.crika;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Todo lo que Crika hace con los archivos que WhatsApp guarda en el teléfono:
 * buscarlos, medirlos, encontrar idénticos, generar miniaturas y borrar.
 * Solo toca carpetas de WhatsApp (nunca otras).
 */
final class WaFiles {

    /** Carpetas donde WhatsApp guarda lo recibido y enviado (según la versión de Android). */
    private static final String[] ROOT_SUFFIXES = {
            "Android/media/com.whatsapp/WhatsApp/Media",
            "WhatsApp/Media",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
            "WhatsApp Business/Media"
    };

    static final class Rec {
        int root;
        String rel;
        long size;
        long mtime;
        String cat;
        boolean sent;
        String path;
    }

    private final File sd = Environment.getExternalStorageDirectory();
    private final List<File> roots = new ArrayList<>();
    private final List<String> rootCanon = new ArrayList<>();
    private volatile List<Rec> last = new ArrayList<>();

    private void findRoots() {
        roots.clear();
        rootCanon.clear();
        for (String suffix : ROOT_SUFFIXES) {
            File f = new File(sd, suffix);
            if (f.isDirectory()) {
                try {
                    String c = f.getCanonicalPath();
                    if (!rootCanon.contains(c)) {
                        roots.add(f);
                        rootCanon.add(c);
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Solo se permite leer o borrar archivos que están dentro de las carpetas de WhatsApp. */
    boolean allowed(String path) {
        if (path == null) return false;
        try {
            String c = new File(path).getCanonicalPath();
            if (rootCanon.isEmpty()) findRoots();
            for (String r : rootCanon) {
                if (c.startsWith(r + "/")) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static String category(String top) {
        switch (top) {
            case "WhatsApp Images": return "img";
            case "WhatsApp Video": return "vid";
            case "WhatsApp Video Notes": return "vid";
            case "WhatsApp Voice Notes": return "ptt";
            case "WhatsApp Audio": return "aud";
            case "WhatsApp Documents": return "doc";
            case "WhatsApp Animated Gifs": return "gif";
            case "WhatsApp Stickers": return "stk";
            case ".Statuses": return "sts";
            default: return "otr";
        }
    }

    /** Recorre las carpetas de WhatsApp y devuelve todo lo encontrado. */
    JSONObject scan() throws JSONException {
        findRoots();
        List<Rec> out = new ArrayList<>();
        for (int ri = 0; ri < roots.size(); ri++) {
            File root = roots.get(ri);
            String base = root.getAbsolutePath();
            ArrayDeque<File> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                File dir = stack.pop();
                File[] kids = dir.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    String name = k.getName();
                    if (k.isDirectory()) {
                        if (name.equals(".Thumbs") || name.equals(".trash") || name.equals("Backups") || name.equals("Databases")) continue;
                        stack.push(k);
                    } else {
                        if (name.equals(".nomedia")) continue;
                        long len = k.length();
                        if (len <= 0) continue;
                        String full = k.getAbsolutePath();
                        String rel = full.length() > base.length() + 1 && full.startsWith(base + "/")
                                ? full.substring(base.length() + 1)
                                : name;
                        String[] parts = rel.split("/");
                        Rec r = new Rec();
                        r.root = ri;
                        r.rel = rel;
                        r.size = len;
                        r.mtime = k.lastModified();
                        r.cat = category(parts[0]);
                        boolean sent = false;
                        for (int i = 1; i < parts.length - 1; i++) if (parts[i].equals("Sent")) sent = true;
                        r.sent = sent;
                        r.path = full;
                        out.add(r);
                    }
                }
            }
        }
        last = out;

        JSONObject res = new JSONObject();
        JSONArray rootsJson = new JSONArray();
        for (File rf : roots) rootsJson.put(rf.getAbsolutePath());
        res.put("roots", rootsJson);
        JSONArray files = new JSONArray();
        for (Rec r : out) {
            JSONArray a = new JSONArray();
            a.put(r.root).put(r.rel).put(r.size).put(r.mtime).put(r.cat).put(r.sent ? 1 : 0);
            files.put(a);
        }
        res.put("files", files);
        return res;
    }

    private static String md5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    /** Archivos idénticos (mismo contenido exacto). Solo compara los que tienen el mismo tamaño. */
    JSONArray duplicates() {
        Map<Long, List<Rec>> bySize = new HashMap<>();
        for (Rec r : last) {
            if (r.size < 1024) continue;
            List<Rec> l = bySize.get(r.size);
            if (l == null) {
                l = new ArrayList<>();
                bySize.put(r.size, l);
            }
            l.add(r);
        }
        JSONArray groups = new JSONArray();
        for (List<Rec> same : bySize.values()) {
            if (same.size() < 2) continue;
            Map<String, List<Rec>> byHash = new HashMap<>();
            for (Rec r : same) {
                try {
                    String h = md5(new File(r.path));
                    List<Rec> l = byHash.get(h);
                    if (l == null) {
                        l = new ArrayList<>();
                        byHash.put(h, l);
                    }
                    l.add(r);
                } catch (Exception ignored) {
                }
            }
            for (List<Rec> g : byHash.values()) {
                if (g.size() < 2) continue;
                Collections.sort(g, new Comparator<Rec>() {
                    @Override
                    public int compare(Rec a, Rec b) {
                        return Long.compare(a.mtime, b.mtime);
                    }
                });
                JSONArray ja = new JSONArray();
                for (Rec r : g) ja.put(r.path);
                groups.put(ja);
            }
        }
        return groups;
    }

    /** Borra los archivos indicados (solo si están en carpetas de WhatsApp). */
    JSONObject delete(JSONArray paths, List<String> deletedOut) throws JSONException {
        long freed = 0;
        int ok = 0, fail = 0;
        for (int i = 0; i < paths.length(); i++) {
            String p = paths.optString(i, null);
            if (!allowed(p)) {
                fail++;
                continue;
            }
            File f = new File(p);
            long len = f.length();
            if (f.delete()) {
                ok++;
                freed += len;
                deletedOut.add(p);
            } else {
                fail++;
            }
        }
        JSONObject res = new JSONObject();
        res.put("deleted", ok);
        res.put("failed", fail);
        res.put("freed", freed);
        return res;
    }

    private static boolean isImageName(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp");
    }

    private static boolean isVideoName(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov");
    }

    /** Miniatura JPEG de una foto o de un video. Devuelve null si no se puede. */
    byte[] thumbnail(String path, int size) {
        if (!allowed(path)) return null;
        File f = new File(path);
        try {
            Bitmap bmp = null;
            if (isImageName(f.getName())) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                int w = o.outWidth, h = o.outHeight;
                if (w <= 0 || h <= 0) return null;
                int sample = 1;
                while ((w / (sample * 2)) >= size && (h / (sample * 2)) >= size) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                bmp = BitmapFactory.decodeFile(path, o);
            } else if (isVideoName(f.getName())) {
                MediaMetadataRetriever mr = new MediaMetadataRetriever();
                try {
                    mr.setDataSource(path);
                    bmp = mr.getFrameAtTime(500000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (bmp == null) bmp = mr.getFrameAtTime();
                } finally {
                    try {
                        mr.release();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            int big = Math.max(w, h);
            if (big > size) {
                float k = size / (float) big;
                Bitmap s = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
                if (s != bmp) bmp.recycle();
                bmp = s;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(24 * 1024);
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tipo de contenido según la extensión, para servir el archivo a la app. */
    static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".opus") || n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".m4a") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".amr")) return "audio/amr";
        if (n.endsWith(".3gp")) return "audio/3gpp";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4")) return "video/mp4";
        return "application/octet-stream";
    }
}
