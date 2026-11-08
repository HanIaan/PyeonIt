package com.pyeonit.community;

import static org.assertj.core.api.Assertions.*;

import com.pyeonit.config.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageMetadataTests {
  @ParameterizedTest
  @ValueSource(strings = {"jpg", "png", "gif", "webp"})
  void removesMetadataWithoutChangingEncodedPixelsOrAnimation(String ext) throws Exception {
    byte[] source = ImageFixtures.read("metadata." + ext);
    byte[] result = ImageMetadata.strip(source, ext);
    assertThat(result).isEqualTo(ImageFixtures.read("stripped." + ext));
    assertThat(CommunityService.detect(result)).isEqualTo(ext);
    assertThat(new String(result, StandardCharsets.ISO_8859_1)).doesNotContain("GPS-SECRET", "Exif", "XMP DataXMP", "ICC_PROFILE");
    assertThat(ImageMetadata.strip(result, ext)).isEqualTo(result);
    if (!ext.equals("webp")) {
      try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(result))) {
        var reader = ImageIO.getImageReaders(input).next();
        try {
          reader.setInput(input);
          assertThat(reader.getNumImages(true)).isEqualTo(ext.equals("gif") ? 2 : 1);
          for (int i = 0; i < reader.getNumImages(true); i++) {
            var image = reader.read(i);
            assertThat(image.getWidth()).isEqualTo(3);
            assertThat(image.getHeight()).isEqualTo(2);
          }
        } finally { reader.dispose(); }
      }
    } else {
      assertThat(result[20] & 0x2c).isZero();
      assertThat(result[20] & 2).isEqualTo(2); // animation flag survives
      String container = new String(result, StandardCharsets.ISO_8859_1);
      assertThat(container).contains("ANIM", "ANMF").doesNotContain("EXIF", "XMP ");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"jpg", "png", "gif", "webp"})
  void rejectsTruncatedContainersRatherThanReturningOriginal(String ext) {
    byte[] source = ImageFixtures.read("metadata." + ext);
    for (int size : new int[] {12, source.length / 2, source.length - 1}) {
      assertThatThrownBy(() -> ImageMetadata.strip(Arrays.copyOf(source, size), ext))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED"));
    }
  }

  @Test
  void rejectsInvalidChunkCrcAndRiffSizeAndStripsTrailingData() {
    byte[] png = ImageFixtures.read("metadata.png");
    png[32] ^= 1;
    assertThatThrownBy(() -> ImageMetadata.strip(png, "png")).isInstanceOf(ApiException.class);
    byte[] webp = ImageFixtures.read("metadata.webp");
    webp[4] ^= 1;
    assertThatThrownBy(() -> ImageMetadata.strip(webp, "webp")).isInstanceOf(ApiException.class);
    for (String ext : new String[] {"jpg", "png", "gif"}) {
      byte[] clean = ImageFixtures.read("stripped." + ext);
      byte[] tailed = Arrays.copyOf(clean, clean.length + 10);
      System.arraycopy("GPS-SECRET".getBytes(StandardCharsets.US_ASCII), 0, tailed, clean.length, 10);
      assertThat(ImageMetadata.strip(tailed, ext)).isEqualTo(clean);
    }
  }
}
