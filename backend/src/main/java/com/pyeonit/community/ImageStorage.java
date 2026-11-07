package com.pyeonit.community;

import java.io.IOException;
import org.springframework.core.io.Resource;

public interface ImageStorage {
  String store(byte[] bytes, String extension) throws IOException;

  Resource read(String key);

  void delete(String key) throws IOException;

  String url(String key);
}
