package com.pyeonit.community;

import java.io.IOException;

final class ImageFixtures {
  static byte[] read(String name) {
    try (var in = ImageFixtures.class.getResourceAsStream("/images/b8/" + name)) {
      if (in == null) throw new IllegalArgumentException("Missing image fixture");
      return in.readAllBytes();
    } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
  }
}
