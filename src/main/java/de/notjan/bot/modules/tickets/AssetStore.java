package de.notjan.bot.modules.tickets;

import de.notjan.bot.modules.tickets.TranscriptRepository.Asset;
import de.notjan.bot.modules.tickets.TranscriptRepository.AssetKind;
import de.notjan.bot.modules.tickets.TranscriptRepository.AssetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class AssetStore {

    private static final Logger LOG = LoggerFactory.getLogger(AssetStore.class);

    static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final long WAIT_STEP_MILLIS = 500;

    private final Path root;
    private final TranscriptRepository repository;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ticket-assets-", 0).factory());

    public AssetStore(String storageDir, TranscriptRepository repository) {
        this.root = Path.of(storageDir).toAbsolutePath().normalize();
        this.repository = repository;
    }

    Long store(Ticket ticket, AssetKind kind, String key, String url, String fileName, String contentType, long size) {
        Optional<Asset> existing = repository.asset(ticket.id(), kind, key);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        Optional<Long> reserved = repository.reserveAsset(ticket.id(), kind, key, safeName(fileName), contentType, size);
        if (reserved.isEmpty()) {
            return repository.asset(ticket.id(), kind, key).map(Asset::id).orElse(null);
        }
        long id = reserved.get();
        if (size > MAX_BYTES) {
            repository.finishAsset(id, AssetStatus.TOO_LARGE, null, size, contentType);
            return id;
        }
        executor.execute(() -> download(ticket, id, url, safeName(fileName), contentType));
        return id;
    }

    byte[] fetch(String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(1)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] data = in.readNBytes((int) MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException("File is larger than " + MAX_BYTES + " bytes");
            }
            return data;
        }
    }

    void awaitPending(long ticketId, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (repository.countPending(ticketId) > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(WAIT_STEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    Optional<Path> resolve(Asset asset) {
        if (asset.storagePath() == null || asset.status() != AssetStatus.STORED) {
            return Optional.empty();
        }
        Path path = root.resolve(asset.storagePath()).normalize();
        return path.startsWith(root) && Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    void deleteTicket(long guildId, String uuid) {
        deleteTree(root.resolve("transcripts").resolve(String.valueOf(guildId)).resolve(uuid));
    }

    void deleteGuild(long guildId) {
        deleteTree(root.resolve("transcripts").resolve(String.valueOf(guildId)));
    }

    private void download(Ticket ticket, long id, String url, String fileName, String contentType) {
        String relative = "transcripts/" + ticket.guildId() + "/" + ticket.uuid() + "/" + id + "-" + fileName;
        Path target = root.resolve(relative).normalize();
        try {
            Files.createDirectories(target.getParent());
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException("HTTP " + response.statusCode());
            }
            long written = 0;
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_BYTES) {
                        break;
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (written > MAX_BYTES) {
                Files.deleteIfExists(target);
                repository.finishAsset(id, AssetStatus.TOO_LARGE, null, written, contentType);
                return;
            }
            String type = contentType != null ? contentType : response.headers().firstValue("Content-Type").orElse(null);
            repository.finishAsset(id, AssetStatus.STORED, relative, written, type);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not store transcript asset {} for ticket {}: {}", id, ticket.id(), e.getMessage());
            repository.finishAsset(id, AssetStatus.FAILED, null, 0, contentType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            repository.finishAsset(id, AssetStatus.FAILED, null, 0, contentType);
        }
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.warn("Could not delete {}", path, e);
                }
            });
        } catch (IOException e) {
            LOG.warn("Could not delete {}", directory, e);
        }
    }

    static String safeName(String fileName) {
        String name = fileName == null || fileName.isBlank() ? "file" : fileName.strip();
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.startsWith(".")) {
            name = "_" + name;
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }
}
