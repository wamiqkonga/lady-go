package com.ladygo.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** One real PostgreSQL + PostGIS instance for integration tests; same image family as infrastructure/docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgisContainerConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgis() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));
    }
}
