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

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Announces the ingest endpoints' security posture at startup, so an operator who exposes the server
 * without a token can't miss it.
 *
 * <p>The ingest endpoints are the only ones that write and the only ones a remote machine calls. With
 * no {@code INGEST_TOKEN} they are open — the right default on localhost, a hole once the port is
 * reachable from elsewhere. The boot log is the one place every operator sees, so the posture is
 * stated there rather than left to the docs.
 */
@Singleton
public class IngestSecurityAdvisory implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(IngestSecurityAdvisory.class);

    /** The three states of the ingest guard, in words the boot log reports. */
    enum Posture {
        /** A token is set; ingest requires it. */
        AUTHENTICATED,
        /** No token, and none required — the open localhost default. */
        OPEN,
        /** Auth was required but no token is set, so all ingest is refused until one is. */
        MISCONFIGURED,
    }

    private final IngestConfig config;

    public IngestSecurityAdvisory(IngestConfig config) {
        this.config = config;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        switch (classify(config)) {
            case AUTHENTICATED -> LOG.info(
                "Ingest endpoints require a bearer token (INGEST_TOKEN)."
            );
            case OPEN -> LOG.warn(
                "Ingest endpoints are UNAUTHENTICATED — anyone who can reach this port can write " +
                "trajectories. That's fine on localhost; set INGEST_TOKEN before exposing the " +
                "server off this machine (and see the deployment-security notes in the README)."
            );
            case MISCONFIGURED -> LOG.error(
                "INGEST_REQUIRE_AUTH is set but INGEST_TOKEN is empty — all ingest will be refused " +
                "until a token is configured. Set INGEST_TOKEN."
            );
        }
    }

    /** Pure classification of the configured posture, so it can be reasoned about without booting. */
    static Posture classify(IngestConfig config) {
        if (config.token().isPresent()) return Posture.AUTHENTICATED;
        return config.requireAuth() ? Posture.MISCONFIGURED : Posture.OPEN;
    }
}
