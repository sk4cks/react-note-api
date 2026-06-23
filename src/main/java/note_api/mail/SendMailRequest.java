package note_api.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SendMailRequest(
        @NotBlank @Email String to,
        @NotBlank String subject,
        @NotBlank String body) {}
