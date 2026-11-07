package com.pyeonit.admin.community;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BlocklistKeyTests {
  private ApplicationContextRunner context(String profiles, String key) {
    return new ApplicationContextRunner()
        .withPropertyValues(
            "spring.profiles.active=" + profiles, "PYEONIT_BLOCKLIST_HMAC_KEY=" + key)
        .withUserConfiguration(BlocklistHasher.class);
  }

  @Test
  void missingKeyFailsStartupOutsideDevAndTestIncludingMixedProfiles() {
    for (String profile : new String[] {"", "prod", "staging", "prod,dev", "prod,test"}) {
      context(profile, "").run(ctx -> assertThat(ctx).hasFailed());
    }
  }

  @Test
  void developmentKeyIsNeverAcceptedInProduction() {
    context("dev", "")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              String devHash = ctx.getBean(BlocklistHasher.class).hash("KAKAO", "123");
              context("prod", "production-test-key")
                  .run(
                      prod -> {
                        assertThat(prod).hasNotFailed();
                        assertThat(prod.getBean(BlocklistHasher.class).hash("kakao", "123"))
                            .isNotEqualTo(devHash);
                      });
            });
    context("test", "").run(ctx -> assertThat(ctx).hasNotFailed());
    context("prod", "pyeonit-dev-only-blocklist-key").run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void hashesAreProviderScopedAndCaseNormalized() {
    context("test", "")
        .run(
            ctx -> {
              var hasher = ctx.getBean(BlocklistHasher.class);
              assertThat(hasher.hash("KAKAO", "123"))
                  .matches("[0-9a-f]{64}")
                  .isEqualTo(hasher.hash("kakao", "123"))
                  .isNotEqualTo(hasher.hash("google", "123"));
            });
  }
}
