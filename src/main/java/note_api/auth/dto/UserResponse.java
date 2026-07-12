package note_api.auth.dto;

public record UserResponse(
        Long userSeq,
        String userId,
        String mailAddress,
        String authProvider,
        String status) {}
