package net.ozanozkan.liberatedframe;

import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Handler;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors a public Google Photos shared album into a local directory.
 * Fail-closed: if the album page yields zero photo URLs (network error,
 * page format change), local photos are left untouched.
 */
public class SyncEngine {

    public interface Listener {
        void onSyncFinished(String message, boolean changed);
    }

    private static final Pattern PHOTO_URL =
            Pattern.compile("https://lh3\\.googleusercontent\\.com/pw/[A-Za-z0-9_-]+");
    // exact screen size, scaled/cropped by Google's high-quality resampler so the
    // frame displays 1:1 with no client-side rescaling (-p = subject-aware crop)
    private static final String SUFFIX_FILL = "=w1280-h800-p";
    private static final String SUFFIX_FIT = "=w1280-h800";
    private static final String SUFFIX_PROBE = "=w64-h40"; // tiny fetch just to learn the aspect
    private static final float SCREEN_ASPECT = 1280f / 800f;
    private static final float MAX_CROP_LOSS = 0.20f; // crop only if it discards <=20% of the photo (fills 4:3, letterboxes portraits)
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final int MAX_PAGE_BYTES = 8 * 1024 * 1024;

    private final File dir;
    private final SharedPreferences prefs;
    private final Handler main;
    private final Listener listener;
    private volatile boolean running = false;

    public SyncEngine(File dir, SharedPreferences prefs, Handler main, Listener listener) {
        this.dir = dir;
        this.prefs = prefs;
        this.main = main;
        this.listener = listener;
    }

    public void syncAsync() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            public void run() {
                String msg;
                boolean changed = false;
                try {
                    changed = doSync();
                    msg = "OK";
                } catch (Exception e) {
                    msg = "sync error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                running = false;
                final String fmsg = msg;
                final boolean fchanged = changed;
                main.post(new Runnable() {
                    public void run() { listener.onSyncFinished(fmsg, fchanged); }
                });
            }
        }, "album-sync").start();
    }

    private boolean doSync() throws Exception {
        String album = prefs.getString("url", "");
        if (album.isEmpty()) throw new IllegalStateException("no album configured");
        if (!dir.exists()) dir.mkdirs();

        String html = httpGetString(album);
        Matcher m = PHOTO_URL.matcher(html);
        Set<String> urls = new LinkedHashSet<>();
        while (m.find()) urls.add(m.group());
        if (urls.isEmpty()) throw new IllegalStateException("no photos parsed (fail-closed, keeping local)");

        boolean changed = false;
        String mode = prefs.getString("mode", "smart");
        Set<String> keep = new HashSet<>();
        for (String u : urls) {
            String name = md5_16(u + "|" + mode + "|" + MAX_CROP_LOSS) + ".jpg"; // mode+threshold aware: tuning re-downloads
            keep.add(name);
            File f = new File(dir, name);
            if (!f.exists()) {
                File tmp = new File(dir, name + ".tmp");
                try {
                    String suffix = "fill".equals(mode) ? SUFFIX_FILL
                            : "fit".equals(mode) ? SUFFIX_FIT
                            : smartSuffix(u);
                    downloadTo(u + suffix, tmp);
                    if (tmp.length() > 4096 && tmp.renameTo(f)) {
                        changed = true;
                    } else {
                        tmp.delete(); // placeholder/error body — skip this cycle
                    }
                } catch (Exception e) {
                    tmp.delete(); // one bad download must not kill the cycle
                }
            }
        }

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".tmp") || (n.endsWith(".jpg") && !keep.contains(n))) {
                    if (f.delete()) changed = true;
                }
            }
        }
        prefs.edit().putLong("lastSync", System.currentTimeMillis())
                .putInt("photoCount", keep.size()).apply();
        return changed;
    }

    /** Fill the screen only when cropping loses little of the photo; otherwise letterbox it whole. */
    private String smartSuffix(String url) {
        try {
            File probe = File.createTempFile("probe", ".jpg", dir);
            try {
                downloadTo(url + SUFFIX_PROBE, probe);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(probe.getAbsolutePath(), o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return SUFFIX_FIT;
                float aspect = (float) o.outWidth / o.outHeight;
                float loss = 1f - Math.min(aspect, SCREEN_ASPECT) / Math.max(aspect, SCREEN_ASPECT);
                return loss <= MAX_CROP_LOSS ? SUFFIX_FILL : SUFFIX_FIT;
            } finally {
                probe.delete();
            }
        } catch (Exception e) {
            return SUFFIX_FIT; // when unsure, show the whole photo
        }
    }

    private String httpGetString(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_PAGE_BYTES) break;
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void downloadTo(String url, File target) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        // follow redirects manually so cross-host hops (goo.gl -> photos.google.com) always work
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            // HTTPS-only: refuse the initial URL and any redirect that isn't TLS, so a
            // network attacker can't downgrade the album fetch to cleartext and inject HTML.
            if (!current.regionMatches(true, 0, "https://", 0, 8))
                throw new IllegalStateException("refusing non-HTTPS URL (cleartext blocked)");
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IllegalStateException("redirect without location");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IllegalStateException("HTTP " + code);
            }
            return c;
        }
        throw new IllegalStateException("too many redirects");
    }

    private static String md5_16(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.substring(0, 16);
    }
}
