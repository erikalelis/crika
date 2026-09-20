package app.crika;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.os.StatFs;

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
    private volatile List<Rec> lastWa = new ArrayList<>();
    private volatile List<Rec> lastPh = new ArrayList<>();
    private String sdCanon = null;

    /** En "todo el teléfono" solo se listan archivos de más de 100 KB (lo demás casi no pesa). */
    private static final long PHONE_MIN = 100 * 1024;
    private static final int PHONE_MAX_FILES = 40000;

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

    /** Solo se permite leer o borrar archivos del almacenamiento compartido (nunca datos privados de otras apps). */
    boolean allowed(String path) {
        if (path == null) return false;
        try {
            if (sdCanon == null) sdCanon = sd.getCanonicalPath();
            String c = new File(path).getCanonicalPath();
            if (!c.startsWith(sdCanon + "/")) return false;
            String rel = c.substring(sdCanon.length() + 1);
            if (rel.equals("Android") || rel.startsWith("Android/data") || rel.startsWith("Android/obb")) return false;
            return true;
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

    private static String catByExt(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        String e = i >= 0 ? n.substring(i + 1) : "";
        switch (e) {
            case "jpg": case "jpeg": case "png": case "webp": case "gif": case "bmp": case "heic": case "heif": case "dng": return "img";
            case "mp4": case "3gp": case "mkv": case "webm": case "mov": case "avi": case "m4v": return "vid";
            case "mp3": case "m4a": case "aac": case "ogg": case "opus": case "wav": case "amr": case "flac": return "aud";
            case "pdf": case "doc": case "docx": case "xls": case "xlsx": case "ppt": case "pptx": case "txt": case "csv": case "epub": return "doc";
            case "apk": case "xapk": case "apks": return "apk";
            case "zip": case "rar": case "7z": case "tar": case "gz": return "zip";
            default: return "otr";
        }
    }

    private static String folderLabel(String[] parts) {
        if (parts.length <= 1) return "(archivos sueltos)";
        if (parts[0].equals("Android") && parts.length > 3 && parts[1].equals("media")) return "Android/media/" + parts[2];
        return parts[0];
    }

    /** Recorre las carpetas de WhatsApp (o todo el almacenamiento) y devuelve todo lo encontrado. */
    JSONObject scan(boolean phone) throws JSONException {
        findRoots();
        List<File> scanRoots = new ArrayList<>();
        if (phone) scanRoots.add(sd); else scanRoots.addAll(roots);
        String sdPath = sd.getAbsolutePath();
        List<Rec> out = new ArrayList<>();
        long totalBytes = 0, totalCount = 0;
        Map<String, long[]> folders = new HashMap<>();
        for (int ri = 0; ri < scanRoots.size(); ri++) {
            File root = scanRoots.get(ri);
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
                        if (phone) {
                            if (name.startsWith(".")) continue;
                            String kp = k.getAbsolutePath();
                            if (kp.equals(base + "/Android/data") || kp.equals(base + "/Android/obb")) continue;
                        } else if (name.equals(".Thumbs") || name.equals(".trash") || name.equals("Backups") || name.equals("Databases")) {
                            continue;
                        }
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
                        r.path = full;
                        if (phone) {
                            totalBytes += len;
                            totalCount++;
                            String label = folderLabel(parts);
                            long[] agg = folders.get(label);
                            if (agg == null) {
                                agg = new long[2];
                                folders.put(label, agg);
                            }
                            agg[0] += len;
                            agg[1]++;
                            if (len < PHONE_MIN) continue;
                            r.cat = catByExt(name);
                            r.sent = false;
                        } else {
                            r.cat = category(parts[0]);
                            boolean sent = false;
                            for (int i = 1; i < parts.length - 1; i++) if (parts[i].equals("Sent")) sent = true;
                            r.sent = sent;
                        }
                        out.add(r);
                    }
                }
            }
        }
        if (phone && out.size() > PHONE_MAX_FILES) {
            Collections.sort(out, new Comparator<Rec>() {
                @Override
                public int compare(Rec a, Rec b) {
                    return Long.compare(b.size, a.size);
                }
            });
            out = new ArrayList<>(out.subList(0, PHONE_MAX_FILES));
        }
        if (phone) lastPh = out; else lastWa = out;

        JSONObject res = new JSONObject();
        JSONArray rootsJson = new JSONArray();
        for (File rf : scanRoots) rootsJson.put(rf.getAbsolutePath());
        res.put("roots", rootsJson);
        JSONArray files = new JSONArray();
        for (Rec r : out) {
            JSONArray a = new JSONArray();
            a.put(r.root).put(r.rel).put(r.size).put(r.mtime).put(r.cat).put(r.sent ? 1 : 0);
            files.put(a);
        }
        res.put("files", files);
        if (phone) {
            JSONObject disk = new JSONObject();
            try {
                StatFs st = new StatFs(sdPath);
                disk.put("total", st.getTotalBytes());
                disk.put("free", st.getAvailableBytes());
            } catch (Throwable ignored) {
            }
            res.put("disk", disk);
            res.put("totalBytes", totalBytes);
            res.put("totalCount", totalCount);
            List<Map.Entry<String, long[]>> fl = new ArrayList<>(folders.entrySet());
            Collections.sort(fl, new Comparator<Map.Entry<String, long[]>>() {
                @Override
                public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                    return Long.compare(b.getValue()[0], a.getValue()[0]);
                }
            });
            JSONArray fj = new JSONArray();
            for (int i = 0; i < fl.size() && i < 60; i++) {
                JSONArray a = new JSONArray();
                a.put(fl.get(i).getKey()).put(fl.get(i).getValue()[0]).put(fl.get(i).getValue()[1]);
                fj.put(a);
            }
            res.put("folders", fj);
        }
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

    /** Huella rápida: tamaño + primeros 128 KB. Sirve para descartar rápido lo que seguro es distinto. */
    private static String quickHash(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[128 * 1024];
            int n = in.read(buf);
            if (n > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    /** Archivos idénticos (mismo contenido exacto). Solo compara los que tienen el mismo tamaño. */
    JSONArray duplicates(boolean phone) {
        List<Rec> src = phone ? lastPh : lastWa;
        long minSize = phone ? PHONE_MIN : 1024;
        Map<Long, List<Rec>> bySize = new HashMap<>();
        for (Rec r : src) {
            if (r.size < minSize) continue;
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
            Map<String, List<Rec>> byQuick = new HashMap<>();
            for (Rec r : same) {
                try {
                    String h = quickHash(new File(r.path));
                    List<Rec> l = byQuick.get(h);
                    if (l == null) {
                        l = new ArrayList<>();
                        byQuick.put(h, l);
                    }
                    l.add(r);
                } catch (Exception ignored) {
                }
            }
            for (List<Rec> cand : byQuick.values()) {
                if (cand.size() < 2) continue;
                Map<String, List<Rec>> byHash = new HashMap<>();
                for (Rec r : cand) {
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
