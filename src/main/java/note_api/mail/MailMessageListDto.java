package note_api.mail;

import java.util.List;

public record MailMessageListDto(List<MailMessageSummaryDto> messages, String nextPageToken) {}
