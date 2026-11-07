package com.pyeonit.community;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class CommunityUploadConfiguration {
  @Bean
  MultipartConfigElement multipartConfigElement(
      @Value("${pyeonit.community.max-image-bytes:5242880}") long maxBytes) {
    return new MultipartConfigElement("", maxBytes, maxBytes + 1048576, 0);
  }
}
