package com.pyeonit.community;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.stereotype.Component;

@Component
public class LocalImageStorage implements ImageStorage {
  private final Path root;

  public LocalImageStorage(
      @Value("${pyeonit.community.image-directory:${java.io.tmpdir}/pyeonit-post-images}")
          String directory) {
    root = Path.of(directory).toAbsolutePath().normalize();
  }

  private Path path(String key) {
    if (!key.matches("[a-f0-9-]{36}\\.(jpg|png|webp|gif)")) throw CommunityService.missing();
    return root.resolve(key);
  }

  public String store(byte[] bytes, String extension) throws IOException {
    Files.createDirectories(root);
    String key = UUID.randomUUID() + "." + extension;
    Files.write(path(key), bytes, StandardOpenOption.CREATE_NEW);
    return key;
  }

  public Resource read(String key) {
    Resource resource = new FileSystemResource(path(key));
    if (!resource.exists()) throw CommunityService.missing();
    return resource;
  }

  public void delete(String key) throws IOException {
    Files.deleteIfExists(path(key));
  }

  public String url(String key) {
    return "/api/post-images/files/" + key;
  }
}
