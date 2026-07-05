package note_api.mail.dto;

import java.util.List;

public record MailMessageListDto(List<MailMessageSummaryDto> messages, String nextPageToken) {}
