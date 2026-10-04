package com.ladygo.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.ladygo.testsupport.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Phase 2 exit criterion: migrations apply from an empty database on real PostgreSQL + PostGIS. */
@IntegrationTest
class DatabaseMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allTwelveMigrationsAppliedSuccessfully() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL", Integer.class);
        String latest = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(applied).isEqualTo(12);
        assertThat(latest).isEqualTo("12");
    }

    @Test
    void postgisIsInstalled() {
        assertThat(jdbc.queryForObject("SELECT postgis_version()", String.class)).isNotBlank();
    }

    @Test
    void locationHistoryIsPartitionedByTime() {
        String kind = jdbc.queryForObject("SELECT relkind::text FROM pg_class WHERE relname = 'location_history'", String.class);
        assertThat(kind).isEqualTo("p");
    }

    @Test
    void safeConfigurationDefaultsAreSeeded() {
        String timeout = jdbc.queryForObject(
                "SELECT value FROM configuration WHERE key = 'driver.response.timeout.s' AND scope = 'GLOBAL'",
                String.class);
        assertThat(timeout).isEqualTo("20");
    }

    @Test
    void devSeedDataIsNotLoadedOutsideTheDevProfile() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cities", Integer.class)).isZero();
    }

    @Test
    @Transactional
    void partitionHelperCreatesAMonthlyPartition() {
        jdbc.execute("SELECT create_location_history_partition(date '2031-01-15')");
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname = 'location_history_2031_01'", Integer.class);
        assertThat(exists).isEqualTo(1);
    }
}
