package com.pyeonit.admin.community;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
class BlocklistHasher {
  private static final String DEV_KEY = "pyeonit-dev-only-blocklist-key";
  private final byte[] key;

  BlocklistHasher(Environment env, @Value("${PYEONIT_BLOCKLIST_HMAC_KEY:}") String configured) {
    // A dev key must never be selected when any production profile is also active.
    boolean local =
        env.acceptsProfiles(Profiles.of("dev", "test"))
            && java.util.Arrays.stream(env.getActiveProfiles())
                .allMatch(p -> p.equals("dev") || p.equals("test"));
    if (configured.isBlank() && !local) {
      throw new IllegalStateException("PYEONIT_BLOCKLIST_HMAC_KEY is required outside dev/test");
    }
    if (!local && configured.equals(DEV_KEY)) {
      throw new IllegalStateException("Development blocklist key is forbidden outside dev/test");
    }
    key = (configured.isBlank() ? DEV_KEY : configured).getBytes(StandardCharsets.UTF_8);
  }

  String hash(String provider, String providerUserId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(
              mac.doFinal(
                  (provider.toLowerCase(Locale.ROOT) + ":" + providerUserId)
                      .getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Cannot initialize blocklist HMAC", e);
    }
  }
}
