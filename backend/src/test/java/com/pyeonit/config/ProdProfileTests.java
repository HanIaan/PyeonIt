package com.pyeonit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import com.pyeonit.community.ImageStorage;
import com.pyeonit.community.R2ImageStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "DB_URL=jdbc:postgresql://localhost:1/fake", "DB_USERNAME=fake", "DB_PASSWORD=fake",
    "PYEONIT_COOKIE_DOMAIN=.example.invalid",
    "PYEONIT_CORS_ALLOWED_ORIGINS=https://web.example.invalid",
    "PYEONIT_WEB_BASE_URL=https://web.example.invalid",
    "PYEONIT_R2_ENDPOINT=https://r2.example.invalid", "PYEONIT_R2_BUCKET=fake-images",
    "PYEONIT_R2_ACCESS_KEY=fake-access", "PYEONIT_R2_SECRET_KEY=fake-secret",
    "PYEONIT_R2_PUBLIC_URL_BASE=https://images.example.invalid",
    "PYEONIT_BLOCKLIST_HMAC_KEY=q2-fake-test-key-never-use-in-production"
})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProdProfileTests {
  @Autowired MockMvc mvc;
  @Autowired Environment env;
  @Autowired ImageStorage storage;
  @Autowired JdbcTemplate db;

  @Test
  void devLoginIsNotMappedInProd() throws Exception {
    mvc.perform(post("/api/dev/login").with(csrf()).contentType("application/json")
        .content("{\"nickname\":\"fakeuser\"}"))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void productionPoliciesAndStorageAreLoadedWithoutDevMigrations() throws Exception {
    assertThat(storage).isInstanceOf(R2ImageStorage.class);
    assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
    assertThat(db.queryForObject("select count(*) from flyway_schema_history where script like 'R__%'", Integer.class)).isZero();
    assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(env.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
    assertThat(env.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
    assertThat(env.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
    assertThat(env.getProperty("server.servlet.session.cookie.domain")).isEqualTo(".example.invalid");
    assertThat(env.getProperty("server.error.include-stacktrace")).isEqualTo("never");
    assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    mvc.perform(get("/actuator/health")).andExpect(status().isOk())
        .andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(options("/api/posts").header("Origin", "https://web.example.invalid")
        .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "https://web.example.invalid"));
    mvc.perform(options("/api/posts").header("Origin", "https://untrusted.example.invalid")
        .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
  }
}
