package com.pyeonit.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pyeonit.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"pyeonit.oauth2.registration.kakao.client-id=test-kakao",
    "GOOGLE_CLIENT_ID=legacy-google", "GOOGLE_CLIENT_SECRET=legacy-test",
    "pyeonit.push.scheduling-enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class KakaoOnlyLoginTests {
  @Autowired MockMvc mvc;
  @Autowired ClientRegistrationRepository clients;

  @Test
  void googleAuthorizationFailsEvenWithLegacyEnvironmentProperties() throws Exception {
    assertThat(clients.findByRegistrationId("google")).isNull();
    mvc.perform(get("/oauth2/authorization/google"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void unknownProviderAlsoReturns404UnderContextPath() throws Exception {
    mvc.perform(get("/b7/oauth2/authorization/unknown").contextPath("/b7"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void kakaoAuthorizationStillRedirectsToKakao() throws Exception {
    var response = mvc.perform(get("/oauth2/authorization/kakao"))
        .andExpect(status().is3xxRedirection()).andReturn().getResponse();
    assertThat(response.getRedirectedUrl()).startsWith("https://kauth.kakao.com/oauth/authorize?");
  }
}
