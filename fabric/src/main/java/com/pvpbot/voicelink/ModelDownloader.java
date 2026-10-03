package com.pvpbot.voicelink;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Fetches a Vosk model zip and unpacks it into models/<name>. Vosk zips hold
// a single top-level folder; that folder's contents become models/<name>.
final class ModelDownloader {
    private ModelDownloader() {
    }

    static void download(String url, Path targetDir, Consumer<String> progress) throws IOException, InterruptedException {
        Path parent = targetDir.getParent();
        Files.createDirectories(parent);
        Path zip = parent.resolve(targetDir.getFileName() + ".zip.part");

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " from " + url);

        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = resp.body()) {
            try (var out = Files.newOutputStream(zip)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int lastPct = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct / 10 != lastPct / 10) {
                            lastPct = pct;
                            progress.accept("Downloading speech model... " + pct + "%");
                        }
                    }
                }
            }
        }

        progress.accept("Unpacking speech model...");
        Path staging = parent.resolve(targetDir.getFileName() + ".staging");
        deleteRecursively(staging);
        Files.createDirectories(staging);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                // Drop the zip's own top-level folder.
                String rel = slash >= 0 ? name.substring(slash + 1) : name;
                if (rel.isEmpty()) continue;
                Path out = staging.resolve(rel).normalize();
                if (!out.startsWith(staging)) throw new IOException("Bad zip entry: " + name);
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        deleteRecursively(targetDir);
        Files.move(staging, targetDir, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(zip);
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path q : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }
}
