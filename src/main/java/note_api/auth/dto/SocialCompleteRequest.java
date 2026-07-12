package note_api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SocialCompleteRequest(
        @NotBlank(message = "아이디를 입력해 주세요")
        @Size(min = 3, max = 64, message = "아이디는 3자 이상 64자 이하여야 합니다")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "아이디는 영문, 숫자, 밑줄만 사용할 수 있습니다")
                String userId) {}
