package com.pyeonit.community;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** R2 is the primary storage in prod; the unchanged local bean remains the dev/test default. */
@Component
@Primary
@Conditional(R2ImageStorage.Enabled.class)
public class R2ImageStorage implements ImageStorage, DisposableBean {
  private final S3Client client;
  private final String bucket;
  private final String publicUrlBase;

  static class Enabled implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      Environment env = context.getEnvironment();
      return env.acceptsProfiles(Profiles.of("prod"))
          || "r2".equals(env.getProperty("pyeonit.community.storage"));
    }
  }

  @Autowired
  public R2ImageStorage(Environment env) {
    this(
        S3Client.builder()
            .endpointOverride(URI.create(required(env, "endpoint")))
            .region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                required(env, "access-key"), required(env, "secret-key"))))
            .forcePathStyle(true)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build(),
        required(env, "bucket"),
        required(env, "public-url-base"));
  }

  R2ImageStorage(S3Client client, String bucket, String publicUrlBase) {
    this.client = client;
    this.bucket = bucket;
    this.publicUrlBase = publicUrlBase.replaceAll("/+$", "");
  }

  private static String required(Environment env, String suffix) {
    String value = env.getRequiredProperty("pyeonit.community.r2." + suffix);
    if (value.isBlank()) throw new IllegalArgumentException("R2 property must not be blank: " + suffix);
    return value;
  }

  @Override
  public String store(byte[] bytes, String extension) throws IOException {
    String key = UUID.randomUUID() + "." + extension;
    try {
      client.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
          .contentType("image/" + ("jpg".equals(extension) ? "jpeg" : extension)).build(),
          RequestBody.fromBytes(bytes));
      return key;
    } catch (SdkException e) {
      throw new IOException("Image upload failed", e);
    }
  }

  @Override
  public Resource read(String key) {
    try {
      return new ByteArrayResource(client.getObjectAsBytes(
          GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray());
    } catch (NoSuchKeyException e) {
      throw CommunityService.missing();
    }
  }

  @Override
  public void delete(String key) throws IOException {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (SdkException e) {
      throw new IOException("Image deletion failed", e);
    }
  }

  @Override
  public String url(String key) {
    return publicUrlBase + "/" + key;
  }

  @Override
  public void destroy() {
    client.close();
  }
}
