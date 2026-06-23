package note_api.mail;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailController {

    private final MailService mailService;

    @GetMapping("/messages")
    public List<MailMessageSummaryDto> listMessages(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "inbox") String folder) {
        return mailService.listMessages(jwt.getSubject(), folder);
    }

    @GetMapping("/messages/{id}")
    public MailMessageDetailDto getMessage(
            @AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return mailService.getMessage(jwt.getSubject(), id);
    }

    @PostMapping("/send")
    public void sendMail(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendMailRequest request) {
        mailService.sendMessage(jwt.getSubject(), request);
    }
}
