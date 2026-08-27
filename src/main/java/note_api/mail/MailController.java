package note_api.mail;

import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailFolderDto;
import note_api.mail.dto.MailMessageDetailDto;
import note_api.mail.dto.MailMessageListDto;
import note_api.mail.dto.SendMailRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 프론트 메일 API — 목록·상세·첨부·발송·폴더 건수. */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailController {

    private final MailService mailService;

    /** 폴더 메일 목록. */
    @GetMapping("/messages")
    public MailMessageListDto listMessages(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "inbox") String folder, @RequestParam(required = false) String pageToken) {
        return mailService.listMessages(jwt.getSubject(), folder, pageToken);
    }

    /** 메일 한 통. IMAP은 folder가 필요하다. */
    @GetMapping("/messages/{id}")
    public MailMessageDetailDto getMessage(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @RequestParam(defaultValue = "inbox") String folder) {
        return mailService.getMessage(jwt.getSubject(), folder, id);
    }

    /** 첨부 다운로드. */
    @GetMapping("/messages/{id}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> getAttachment(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String attachmentId, @RequestParam(defaultValue = "inbox") String folder) {
        MailAttachmentContent attachment =
                mailService.getAttachment(jwt.getSubject(), folder, id, attachmentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(attachment.filename(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(attachment.contentType()))
                .body(attachment.content());
    }

    /** 편지함 건수(뱃지). */
    @GetMapping("/folders")
    public List<MailFolderDto> getFolders(@AuthenticationPrincipal Jwt jwt) {
        return mailService.getFolderStats(jwt.getSubject());
    }

    /** 메일 발송. */
    @PostMapping("/send")
    public void sendMail(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendMailRequest request) {
        mailService.sendMessage(jwt.getSubject(), request);
    }
}
