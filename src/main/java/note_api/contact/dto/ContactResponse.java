package note_api.contact.dto;

public record ContactResponse(
        Long id, Long accountUserSeq, String displayName, String email, boolean fromAccount) {}
