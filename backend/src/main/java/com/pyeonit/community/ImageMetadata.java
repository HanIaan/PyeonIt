package com.pyeonit.community;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.CRC32;

/** Container surgery only: compressed pixels and animation frames are never re-encoded. */
final class ImageMetadata {
  private ImageMetadata() {}

  static byte[] strip(byte[] bytes, String format) {
    try {
      return switch (format) {
        case "jpg" -> jpeg(bytes);
        case "png" -> png(bytes);
        case "webp" -> webp(bytes);
        case "gif" -> gif(bytes);
        default -> throw new IllegalArgumentException();
      };
    } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
      // Never return the original on a parsing error or expose its metadata in an error.
      throw CommunityService.invalid();
    }
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("Invalid image container");
  }

  private static int u(byte[] b, int p) { return b[p] & 255; }

  private static int be16(byte[] b, int p) { return (u(b, p) << 8) | u(b, p + 1); }

  private static long uint32(byte[] b, int p, boolean little) {
    long result = 0;
    for (int i = 0; i < 4; i++) result = (result << 8) | u(b, p + (little ? 3 - i : i));
    return result;
  }

  private static int length(byte[] b, int p, boolean little) {
    long size = uint32(b, p, little);
    require(size <= b.length);
    return (int) size;
  }

  private static String ascii(byte[] b, int p, int size) {
    return new String(b, p, size, StandardCharsets.US_ASCII);
  }

  private static void copy(ByteArrayOutputStream out, byte[] b, int start, int end) {
    require(start >= 0 && end >= start && end <= b.length);
    out.write(b, start, end - start);
  }

  private static byte[] jpeg(byte[] b) {
    require(be16(b, 0) == 0xffd8);
    var out = new ByteArrayOutputStream(b.length);
    copy(out, b, 0, 2);
    int p = 2;
    boolean frame = false, scan = false;
    while (p < b.length) {
      int start = p;
      require(u(b, p++) == 255);
      while (u(b, p) == 255) p++;
      int marker = u(b, p++);
      if (marker == 0xd9) {
        require(frame && scan);
        out.write(255); out.write(marker);
        return out.toByteArray(); // Discard trailing data too.
      }
      require(marker != 0 && marker != 0xd8 && !(marker >= 0xd0 && marker <= 0xd7));
      if (marker == 1) { copy(out, b, start, p); continue; }
      int size = be16(b, p);
      require(size >= 2 && p + size <= b.length);
      // Drop every APP segment, including ICC (APP2) and thumbnails; Adobe's fixed color
      // transform segment is needed for CMYK/YCCK and carries no free-form metadata.
      boolean adobe = marker == 0xee && size == 14 && ascii(b, p + 2, 5).equals("Adobe");
      boolean metadata = marker == 0xfe || (marker >= 0xe0 && marker <= 0xef && !adobe);
      if (!metadata) copy(out, b, start, p + size);
      if (Set.of(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
          .contains(marker)) {
        require(size >= 8 && be16(b, p + 3) > 0 && be16(b, p + 5) > 0);
        frame = true;
      }
      p += size;
      if (marker == 0xda) {
        require(frame && size >= 6);
        int entropy = p;
        while (p < b.length) {
          if (u(b, p) != 255) { p++; continue; }
          int next = p + 1;
          while (u(b, next) == 255) next++;
          int code = u(b, next);
          if (code == 0 || (code >= 0xd0 && code <= 0xd7)) { p = next + 1; continue; }
          break;
        }
        require(p > entropy);
        scan = true;
        copy(out, b, entropy, p);
      }
    }
    throw new IllegalArgumentException();
  }

  private static byte[] png(byte[] b) {
    require(CommunityService.detect(b).equals("png"));
    var out = new ByteArrayOutputStream(b.length);
    copy(out, b, 0, 8);
    int p = 8;
    boolean header = false, pixels = false;
    Set<String> keep = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "cHRM", "gAMA",
        "sRGB", "sBIT", "cICP", "mDCV", "cLLI", "acTL", "fcTL", "fdAT");
    while (p < b.length) {
      int size = length(b, p, false);
      require((long) p + size + 12 <= b.length);
      String type = ascii(b, p + 4, 4);
      var crc = new CRC32();
      crc.update(b, p + 4, size + 4);
      require(crc.getValue() == uint32(b, p + 8 + size, false));
      if (!header) {
        require(type.equals("IHDR") && size == 13
            && uint32(b, p + 8, false) > 0 && uint32(b, p + 12, false) > 0);
        header = true;
      } else require(!type.equals("IHDR"));
      // Unknown critical chunks cannot safely be removed. All non-rendering ancillary chunks
      // (including eXIf, text and ICC profiles) are discarded, APNG frame chunks are preserved.
      require(keep.contains(type) || (u(b, p + 4) & 32) != 0);
      if (keep.contains(type)) copy(out, b, p, p + size + 12);
      if (type.equals("IDAT")) pixels = true;
      if (type.equals("IEND")) {
        require(size == 0 && pixels);
        return out.toByteArray();
      }
      p += size + 12;
    }
    throw new IllegalArgumentException();
  }

  private static byte[] webp(byte[] b) {
    require(CommunityService.detect(b).equals("webp") && uint32(b, 4, true) == b.length - 8);
    var out = new ByteArrayOutputStream(b.length);
    copy(out, b, 0, 12);
    int p = 12;
    boolean pixels = false;
    while (p < b.length) {
      String type = ascii(b, p, 4);
      int size = length(b, p + 4, true);
      int end = p + 8 + size + (size & 1);
      require(end <= b.length);
      if ((size & 1) != 0) require(b[end - 1] == 0);
      if (type.equals("VP8X")) {
        require(p == 12 && size == 10);
        copy(out, b, p, p + 8);
        out.write(u(b, p + 8) & ~0x2c); // Clear ICC, EXIF and XMP flags; keep alpha/animation.
        copy(out, b, p + 9, end);
      } else if (Set.of("VP8 ", "VP8L", "ALPH", "ANIM", "ANMF").contains(type)) {
        require(size > 0);
        if (type.equals("ANMF")) validateWebpFrame(b, p + 8, p + 8 + size);
        if (!type.equals("ALPH") && !type.equals("ANIM")) pixels = true;
        copy(out, b, p, end);
      } // EXIF, XMP, ICCP and unknown non-rendering chunks are removed.
      p = end;
    }
    require(pixels && p == b.length);
    byte[] result = out.toByteArray();
    int size = result.length - 8;
    for (int i = 0; i < 4; i++) result[4 + i] = (byte) (size >>> (i * 8));
    return result;
  }

  private static void validateWebpFrame(byte[] b, int p, int end) {
    require(end - p >= 16);
    p += 16;
    boolean pixels = false;
    while (p < end) {
      String type = ascii(b, p, 4);
      int size = length(b, p + 4, true);
      require(Set.of("ALPH", "VP8 ", "VP8L").contains(type) && size > 0);
      pixels |= !type.equals("ALPH");
      p += 8 + size + (size & 1);
      require(p <= end);
    }
    require(p == end && pixels);
  }

  private static int subBlocks(byte[] b, int p) {
    while (true) {
      int size = u(b, p++);
      if (size == 0) return p;
      require(p + size <= b.length);
      p += size;
    }
  }

  private static byte[] gif(byte[] b) {
    require(CommunityService.detect(b).equals("gif") && be16(b, 6) != 0 && be16(b, 8) != 0);
    int p = 13;
    if ((u(b, 10) & 128) != 0) p += 3 * (1 << ((u(b, 10) & 7) + 1));
    var out = new ByteArrayOutputStream(b.length);
    copy(out, b, 0, p);
    boolean frame = false;
    while (p < b.length) {
      int start = p;
      int tag = u(b, p++);
      if (tag == 0x3b) {
        require(frame);
        out.write(tag);
        return out.toByteArray();
      }
      if (tag == 0x2c) {
        require(p + 9 < b.length);
        int packed = u(b, p + 8);
        p += 9;
        if ((packed & 128) != 0) p += 3 * (1 << ((packed & 7) + 1));
        require(u(b, p) >= 2 && u(b, p) <= 8);
        p = subBlocks(b, p + 1);
        copy(out, b, start, p);
        frame = true;
      } else {
        require(tag == 0x21);
        int label = u(b, p++);
        int data = p;
        p = subBlocks(b, p);
        boolean control = label == 0xf9 && p - data == 6 && u(b, data) == 4;
        boolean loop = label == 0xff && p - data == 17 && u(b, data) == 11
            && Set.of("NETSCAPE2.0", "ANIMEXTS1.0").contains(ascii(b, data + 1, 11))
            && u(b, data + 12) == 3 && u(b, data + 13) == 1;
        require(label != 0xf9 || control);
        // Drop comments, plain text, XMP and other applications. Keep timing/transparency
        // controls and the fixed animation loop extension, without changing any image frame.
        if (control || loop) copy(out, b, start, p);
      }
    }
    throw new IllegalArgumentException();
  }
}
