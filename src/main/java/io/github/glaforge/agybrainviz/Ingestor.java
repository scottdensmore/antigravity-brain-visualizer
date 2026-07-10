/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.glaforge.agybrainviz;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Normalizes pushed trajectories and stores them.
 *
 * <p>The content hash is computed here, from the transcript the server actually received, rather than
 * trusted from the client. A client that hashed wrongly would otherwise be able to convince the store
 * that a changed trajectory was unchanged, and the row would never be refreshed. Getting it wrong now
 * costs only bandwidth: the client's manifest comparison misses, it re-pushes, and the upsert no-ops.
 */
@Singleton
public class Ingestor {

    private static final Logger LOG = LoggerFactory.getLogger(Ingestor.class);

    private final List<SourceNormalizer> normalizers;
    private final SessionRepository sessions;

    @Inject
    public Ingestor(List<SourceNormalizer> normalizers, SessionRepository sessions) {
        this.normalizers = normalizers;
        this.sessions = sessions;
    }

    /** Stores a batch, counting each trajectory as ingested, skipped (unchanged), or failed. */
    public IngestResult ingest(List<IngestSession> batch) {
        int ingested = 0;
        int skipped = 0;
        int failed = 0;
        for (IngestSession pushed : batch == null ? List.<IngestSession>of() : batch) {
            switch (store(pushed)) {
                case INGESTED -> ingested++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        return new IngestResult(ingested, skipped, failed);
    }

    private enum Outcome {
        INGESTED,
        SKIPPED,
        FAILED,
    }

    private Outcome store(IngestSession pushed) {
        if (pushed == null || isBlank(pushed.id()) || isBlank(pushed.source())) {
            LOG.warn("Rejecting a pushed trajectory with no source or id");
            return Outcome.FAILED;
        }
        Optional<SourceNormalizer> normalizer = normalizerFor(pushed.source());
        if (normalizer.isEmpty()) {
            LOG.warn("Rejecting trajectory {}: unknown source '{}'", pushed.id(), pushed.source());
            return Outcome.FAILED;
        }

        String raw = pushed.raw() == null ? "" : pushed.raw();
        List<String> lines = raw.lines().toList();
        String stepsJson = normalizer.get().toStepsJson(lines);
        String title = titleFor(pushed, normalizer.get(), lines);

        // A pushed mtime of 0 (a client that could not stat the file) would sort the session to the
        // epoch; treat it as "now" so it still appears at the top of a newest-first listing.
        long mtime = pushed.sourceMtime() > 0 ? pushed.sourceMtime() : System.currentTimeMillis();

        boolean written = sessions.upsert(
            new SessionRepository.Session(
                pushed.source(),
                pushed.id(),
                title,
                mtime,
                stepsJson,
                sha256(raw)
            )
        );
        return written ? Outcome.INGESTED : Outcome.SKIPPED;
    }

    private Optional<SourceNormalizer> normalizerFor(String source) {
        return normalizers.stream().filter(n -> n.handles(source)).findFirst();
    }

    // The client's title wins, then whatever the transcript reveals, then a stable last resort — the
    // session list must never show a blank row.
    private String titleFor(IngestSession pushed, SourceNormalizer normalizer, List<String> lines) {
        if (!isBlank(pushed.title())) return pushed.title().trim();
        return normalizer
            .deriveTitle(lines)
            .filter(t -> !t.isBlank())
            .orElseGet(() -> pushed.source() + " session " + shortId(pushed.id()));
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Hex SHA-256 of the transcript's UTF-8 bytes. Clients hash the file the same way. */
    static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
