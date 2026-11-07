package com.pyeonit.notice;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SeedProfileTests {
  @Autowired JdbcTemplate db;
  @Autowired Environment environment;

  @Test
  void defaultProfileDoesNotLoadDevSeed() {
    assertThat(environment.getProperty("spring.flyway.locations", "classpath:db/migration"))
        .doesNotContain("db/dev");
    assertThat(db.queryForObject("select count(*) from universities", Integer.class)).isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from flyway_schema_history where script='R__dev_seed.sql'",
                Integer.class))
        .isZero();
  }
}
