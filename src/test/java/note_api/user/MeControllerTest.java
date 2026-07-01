package note_api.user;

import note_api.config.CorsProperties;
import note_api.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Objects;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MeController 슬라이스 테스트.
 * <p>
 * @SpringBootTest(전체 앱 기동) 대신 @WebMvcTest를 쓰는 이유:
 * - MeController + Security 설정만 올려서 빠르게 검증
 * - MailService, GmailClient 등 다른 Bean은 올리지 않음
 * <p>
 * /api/me 는 SecurityConfig에서 authenticated — JWT 없으면 401.
 */
@WebMvcTest(controllers = MeController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties(CorsProperties.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:8080")
class MeControllerTest {

  @Autowired private MockMvc mockMvc;

  /**
   * 유효한 JWT가 있으면 토큰 claim을 MeResponse JSON으로 반환.
   * jwt() PostProcessor로 Auth Server 없이도 SecurityContext에 인증 정보 주입.
   */
  @Test
  void me_returnsUserClaims_whenAuthenticated() throws Exception {
    mockMvc
        .perform(
            get("/api/me")
                .with(
                    Objects.requireNonNull(
                        jwt()
                            .jwt(
                                token ->
                                    token
                                        .subject("sk4cks@gmail.com")
                                        .claim("preferred_username", "sk4cks")
                                        .claim("scope", "openid profile email")),
                        "jwt RequestPostProcessor")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value("sk4cks@gmail.com"))
        .andExpect(jsonPath("$.preferredUsername").value("sk4cks"))
        .andExpect(jsonPath("$.scope").value("openid profile email"));
  }

  /** Authorization 헤더·JWT 없이 호출하면 401 Unauthorized */
  @Test
  void me_returnsUnauthorized_whenNotAuthenticated() throws Exception {
    mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }
}
