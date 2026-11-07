package com.pyeonit.community;

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
class CommunitySeedProfileTests {
  @Autowired JdbcTemplate db;
  @Autowired Environment environment;

  @Test
  void defaultProfileExcludesCommunitySeed() {
    assertThat(environment.getProperty("spring.flyway.locations", "classpath:db/migration"))
        .doesNotContain("db/dev");
    assertThat(db.queryForObject("select count(*) from posts", Integer.class)).isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from users where nickname in ('편입새싹','합격준비생')", Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from flyway_schema_history where"
                    + " script='R__dev_seed_community.sql'",
                Integer.class))
        .isZero();
  }
}
