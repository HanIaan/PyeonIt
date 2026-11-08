package com.pyeonit.community;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "pyeonit.community.retention-cleanup-cron=-")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ImageMetadataUploadTests {
  static final Path DIRECTORY;
  static {
    try { DIRECTORY = Files.createTempDirectory("pyeonit-b8-uploads-"); }
    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
  }
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("pyeonit.community.image-directory", DIRECTORY::toString);
  }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  long member;

  @BeforeEach
  void setup() throws Exception {
    db.execute("truncate users cascade");
    member = db.queryForObject("insert into users(nickname) values ('B8업로드') returning id", Long.class);
    try (var files = Files.list(DIRECTORY)) {
      for (Path file : files.toList()) Files.delete(file);
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    try (var files = Files.list(DIRECTORY)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(DIRECTORY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"jpg", "png", "gif", "webp"})
  void uploadsOnlySanitizedBytesAndRecordsTheirActualSize(String ext) throws Exception {
    String type = "image/" + (ext.equals("jpg") ? "jpeg" : ext);
    mvc.perform(multipart("/api/post-images")
        .file(new MockMultipartFile("file", "../../gps." + ext, type, ImageFixtures.read("metadata." + ext)))
        .with(user("" + member).roles("USER")).with(csrf()))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.contentType").value(type));
    var row = db.queryForMap("select storage_key,size_bytes from post_images");
    String key = (String) row.get("storage_key");
    assertThat(key).matches("[a-f0-9-]{36}\\." + ext);
    byte[] saved = Files.readAllBytes(DIRECTORY.resolve(key));
    assertThat(saved).isEqualTo(ImageFixtures.read("stripped." + ext));
    assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(saved.length);
  }

  @ParameterizedTest
  @ValueSource(strings = {"jpg", "png", "gif", "webp"})
  void rejectsSanitizationFailureWithExistingValidationCodeAndStoresNothing(String ext) throws Exception {
    String type = "image/" + (ext.equals("jpg") ? "jpeg" : ext);
    byte[] truncated = Arrays.copyOf(ImageFixtures.read("metadata." + ext), 12);
    mvc.perform(multipart("/api/post-images")
        .file(new MockMultipartFile("file", "bad." + ext, type, truncated))
        .with(user("" + member).roles("USER")).with(csrf()))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(db.queryForObject("select count(*) from post_images", Integer.class)).isZero();
    try (var files = Files.list(DIRECTORY)) { assertThat(files.count()).isZero(); }
  }
}
