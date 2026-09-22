package note_api.mail.dto;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.util.StringUtils;

import java.util.List;

/** 폴더 안 메일을 지운다. 휴지통이 아니면 휴지통으로 옮긴다. */
public record DeleteMessagesRequest(
        String folder,
        @NotEmpty(message = "삭제할 메일을 선택해 주세요.") List<String> ids) {

    public DeleteMessagesRequest {
        folder = StringUtils.hasText(folder) ? folder.trim() : "inbox";
        ids = ids == null
                ? List.of()
                : ids.stream().filter(StringUtils::hasText).distinct().toList();
    }
}
