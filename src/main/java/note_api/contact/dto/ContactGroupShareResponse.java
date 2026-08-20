package note_api.contact.dto;

public record ContactGroupShareResponse(
        Long id, Long groupId, String sharedWithUserId, String permission) {}
