package com.pyeonit.community;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import java.util.List;
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
class CommunityDevSeedTests {
  @Autowired JdbcTemplate db;
  @Autowired Flyway flyway;

  @Test
  void seedIsRepeatableAndIncludesRepliesAndMultipleTags() {
    var tables = List.of("users", "posts", "comments", "post_universities", "universities");
    var before =
        tables.stream()
            .map(t -> db.queryForObject("select count(*) from " + t, Integer.class))
            .toList();
    assertThat(before.get(1)).isEqualTo(3);
    assertThat(before.get(2)).isEqualTo(2);
    assertThat(before.get(3)).isEqualTo(3);
    assertThat(
            db.queryForObject(
                "select count(*) from comments where parent_id is not null", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from posts p where comment_count<>(select count(*) from comments c"
                    + " where c.post_id=p.id and c.status='PUBLISHED')",
                Integer.class))
        .isZero();
    db.update(
        "update flyway_schema_history set checksum=0 where script='R__dev_seed_community.sql'");
    flyway.migrate();
    flyway.migrate();
    assertThat(
            tables.stream()
                .map(t -> db.queryForObject("select count(*) from " + t, Integer.class))
                .toList())
        .isEqualTo(before);
  }
}
