package com.pyeonit.community;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class R2ImageStorageTests {
  @Test
  void storesReadsBuildsPublicUrlAndDeletesWithoutRealR2() throws Exception {
    S3Client client = mock(S3Client.class);
    var storage = new R2ImageStorage(client, "fake-images", "https://images.example.invalid///");
    byte[] bytes = {1, 2, 3};
    String key = storage.store(bytes, "jpg");
    assertThat(key).matches("[a-f0-9-]{36}\\.jpg");
    var request = ArgumentCaptor.forClass(PutObjectRequest.class);
    var body = ArgumentCaptor.forClass(RequestBody.class);
    verify(client).putObject(request.capture(), body.capture());
    assertThat(request.getValue().bucket()).isEqualTo("fake-images");
    assertThat(request.getValue().key()).isEqualTo(key);
    assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
    try (var stream = body.getValue().contentStreamProvider().newStream()) {
      assertThat(stream.readAllBytes()).isEqualTo(bytes);
    }
    when(client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
    assertThat(storage.read(key).getContentAsByteArray()).isEqualTo(bytes);
    verify(client).getObjectAsBytes(GetObjectRequest.builder().bucket("fake-images").key(key).build());
    assertThat(storage.url(key)).isEqualTo("https://images.example.invalid/" + key);
    storage.delete(key);
    verify(client).deleteObject(DeleteObjectRequest.builder().bucket("fake-images").key(key).build());
    storage.destroy();
    verify(client).close();
  }

  @Test
  void mapsMissingObjectAndPreservesIoFailureContract() {
    S3Client client = mock(S3Client.class);
    var storage = new R2ImageStorage(client, "fake", "https://images.example.invalid");
    when(client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().build());
    assertThatThrownBy(() -> storage.read("missing.png")).isInstanceOf(com.pyeonit.config.ApiException.class);
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(SdkClientException.create("fake failure"));
    assertThatThrownBy(() -> storage.store(new byte[] {1}, "png")).isInstanceOf(IOException.class);
    when(client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(SdkClientException.create("fake failure"));
    assertThatThrownBy(() -> storage.delete("fake.png")).isInstanceOf(IOException.class);
  }

  private ApplicationContextRunner context() {
    return new ApplicationContextRunner().withUserConfiguration(LocalImageStorage.class, R2ImageStorage.class)
        .withPropertyValues(
            "pyeonit.community.r2.endpoint=https://r2.example.invalid",
            "pyeonit.community.r2.bucket=fake-images",
            "pyeonit.community.r2.access-key=fake-access",
            "pyeonit.community.r2.secret-key=fake-secret",
            "pyeonit.community.r2.public-url-base=https://images.example.invalid");
  }

  @Test
  void prodAndExplicitR2SelectR2WhileDevTestAndDefaultKeepLocal() {
    for (String profile : new String[] {"", "dev", "test"}) {
      context().withPropertyValues("spring.profiles.active=" + profile).run(ctx -> {
        assertThat(ctx).hasNotFailed().doesNotHaveBean(R2ImageStorage.class);
        assertThat(ctx.getBean(ImageStorage.class)).isInstanceOf(LocalImageStorage.class);
      });
    }
    for (String property : new String[] {"spring.profiles.active=prod", "pyeonit.community.storage=r2"}) {
      context().withPropertyValues(property).run(ctx -> {
        assertThat(ctx).hasNotFailed();
        assertThat(ctx.getBean(ImageStorage.class)).isInstanceOf(R2ImageStorage.class);
      });
    }
  }

  @Test
  void missingR2ConfigurationFailsStartup() {
    new ApplicationContextRunner().withUserConfiguration(R2ImageStorage.class)
        .withPropertyValues("spring.profiles.active=prod")
        .run(ctx -> assertThat(ctx).hasFailed());
    context().withPropertyValues("spring.profiles.active=prod", "pyeonit.community.r2.secret-key=")
        .run(ctx -> assertThat(ctx).hasFailed());
  }
}
