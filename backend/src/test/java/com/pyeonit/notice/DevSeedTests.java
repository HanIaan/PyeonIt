package com.pyeonit.notice;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class DevSeedTests {
  @Autowired JdbcTemplate db;
  @Autowired Flyway flyway;

  @Test
  void repeatableSeedSurvivesChecksumChangeAndRestart() {
    var tables =
        java.util.List.of(
            "universities",
            "admission_documents",
            "departments",
            "quotas",
            "schedules",
            "track_aliases");
    var before =
        tables.stream()
            .map(t -> db.queryForObject("select count(*) from " + t, Integer.class))
            .toList();
    assertThat(before.get(0)).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select count(*) from admission_documents where status='APPROVED'", Integer.class))
        .isEqualTo(2);
    assertThat(db.queryForObject("select count(*) from crawl_sources", Integer.class)).isZero();
    db.update("update flyway_schema_history set checksum=0 where script='R__dev_seed.sql'");
    flyway.migrate(); // content checksum differs: execute the repeatable again
    flyway.migrate(); // restart without changes: no work
    assertThat(
            tables.stream()
                .map(t -> db.queryForObject("select count(*) from " + t, Integer.class))
                .toList())
        .isEqualTo(before);
  }
}
