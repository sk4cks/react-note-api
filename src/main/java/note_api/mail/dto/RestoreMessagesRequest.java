package note_api.mail.dto;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.util.StringUtils;

import java.util.List;

/** 휴지통 메일을 원래 편지함으로 되돌린다. */
public record RestoreMessagesRequest(
        @NotEmpty(message = "복원할 메일을 선택해 주세요.") List<String> ids) {

    public RestoreMessagesRequest {
        ids = ids == null
                ? List.of()
                : ids.stream().filter(StringUtils::hasText).distinct().toList();
    }
}
