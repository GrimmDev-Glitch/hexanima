package com.hexanima.npu;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Parallel ranged download: one file in 16 MB chunks over 6 connections. A single HTTP stream from the Hugging Face / Civitai storage CDNs
 *  tops out far below a fast Wi-Fi link; several ranges at once fill it. Resumable: finished chunks are listed in <part>.map.
 *  Only URLs that need no credentials come here (Civitai's own host is resolved with the key first, the storage host gets none). */
final class ParDl {
    static final int THREADS = 6;
    static final long CHUNK = 16L << 20;
    interface Prog { void at(long done, long total); }

    /** follows the redirects with a 1-byte range: the final URL and the file size, or null when the server does not do ranges */
    static Object[] probe(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Range", "bytes=0-0");
        try {
            int code = c.getResponseCode();
            String cr = c.getHeaderField("Content-Range");   // "bytes 0-0/12345"
            if (code != 206 || cr == null || cr.indexOf('/') < 0) return null;
            long total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
            return new Object[]{c.getURL().toString(), total};
        } catch (NumberFormatException e) { return null; }
        finally { c.disconnect(); }
    }

    /** downloads url into part (size bytes); false = the server does not do ranges (use a plain download) */
    static boolean get(String url, File part, long size, Prog pg, AtomicBoolean cancel) throws IOException {
        Object[] pr = probe(url); if (pr == null) return false;
        final String[] fu = {(String) pr[0]}; final long total = (Long) pr[1];
        if (size > 0 && total != size) throw new IOException("size " + total + " != " + size);
        final int n = (int) ((total + CHUNK - 1) / CHUNK);
        final File mapF = new File(part.getPath() + ".map");
        final byte[] done = new byte[n];
        if (part.exists() && part.length() == total && mapF.exists() && mapF.length() == n) try (FileInputStream in = new FileInputStream(mapF)) { new DataInputStream(in).readFully(done); }
        else { part.delete(); mapF.delete(); }
        final AtomicLong got = new AtomicLong(); for (int i = 0; i < n; i++) if (done[i] != 0) got.addAndGet(Math.min(CHUNK, total - i * CHUNK));
        final AtomicInteger next = new AtomicInteger(); final IOException[] err = {null};
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.setLength(total);
            final FileChannel ch = raf.getChannel();
            Thread[] th = new Thread[Math.min(THREADS, n)];
            for (int t = 0; t < th.length; t++) {
                th[t] = new Thread(() -> {
                    byte[] buf = new byte[1 << 18];
                    for (int i; (i = next.getAndIncrement()) < n && err[0] == null; ) {
                        if (done[i] != 0) continue;
                        final long a = i * CHUNK, b = Math.min(total, a + CHUNK) - 1;
                        for (int attempt = 0; ; attempt++) {
                            long pos = a, mine = 0;
                            try {
                                HttpURLConnection c = (HttpURLConnection) new URL(fu[0]).openConnection();
                                c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true);
                                c.setRequestProperty("Range", "bytes=" + a + "-" + b);
                                try {
                                    int code = c.getResponseCode();
                                    if (code == 403 || code == 410) { Object[] p2 = probe(url); if (p2 != null) synchronized (fu) { fu[0] = (String) p2[0]; } throw new IOException("HTTP " + code); }   // signed URL expired
                                    if (code != 206) throw new IOException("HTTP " + code);
                                    try (InputStream in = c.getInputStream()) {
                                        for (int r; pos <= b && (r = in.read(buf, 0, (int) Math.min(buf.length, b + 1 - pos))) > 0; ) {
                                            if (cancel != null && cancel.get()) throw new InterruptedIOException("cancelled");
                                            ByteBuffer bb = ByteBuffer.wrap(buf, 0, r); while (bb.hasRemaining()) pos += ch.write(bb, pos);
                                            mine += r; long g = got.addAndGet(r); if (pg != null) pg.at(g, total);
                                        }
                                    }
                                } finally { c.disconnect(); }
                                if (pos != b + 1) throw new IOException("short chunk");
                                synchronized (done) { done[i] = 1; try (FileOutputStream o = new FileOutputStream(mapF)) { o.write(done); } }
                                break;
                            } catch (InterruptedIOException e) { synchronized (err) { if (err[0] == null) err[0] = e; } return;
                            } catch (IOException e) {
                                got.addAndGet(-mine);   // the chunk starts again
                                if (attempt >= 5) { synchronized (err) { if (err[0] == null) err[0] = e; } return; }
                                try { Thread.sleep(1500L * (attempt + 1)); } catch (InterruptedException ie) { return; }
                            }
                        }
                    }
                });
                th[t].start();
            }
            for (Thread t : th) try { t.join(); } catch (InterruptedException e) { cancel.set(true); }
        }
        if (err[0] != null) throw err[0];
        mapF.delete();
        return true;
    }
}
