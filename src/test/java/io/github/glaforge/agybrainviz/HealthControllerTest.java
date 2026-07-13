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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Liveness stays up regardless of the store; readiness follows the store's reachability. */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // required by TestPropertyProvider
class HealthControllerTest implements TestPropertyProvider {

    @Override
    public Map<String, String> getProperties() {
        return TestPostgres.datasourceProperties();
    }

    @Inject
    @Client("/")
    HttpClient client;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void livenessIsUpAndReportsTheBuildVersion() throws IOException {
        JsonNode body = MAPPER.readTree(client.toBlocking().retrieve("/health"));
        assertEquals("UP", body.get("status").asText());
        // The build version is exposed so a running instance's version is one `curl /health` away.
        assertEquals(Version.VERSION, body.get("version").asText());
    }

    @Test
    void readinessIsUpWhenTheStoreIsReachable() throws IOException {
        JsonNode body = MAPPER.readTree(client.toBlocking().retrieve("/health/ready"));
        assertEquals("UP", body.get("status").asText());
        assertEquals("UP", body.get("db").asText());
    }

    @Test
    void readinessIsA503WithADownBodyWhenTheStoreIsUnreachable() {
        HealthController controller = new HealthController(new UnreachableDataSource());
        HttpResponse<Map<String, String>> response = controller.readiness();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.status());
        assertEquals("DOWN", response.body().get("status"));
        assertEquals("DOWN", response.body().get("db"));
    }

    /** A DataSource that can't hand out a connection, standing in for an unreachable store. */
    private static final class UnreachableDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("store is down");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("store is down");
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Logger getParentLogger() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
