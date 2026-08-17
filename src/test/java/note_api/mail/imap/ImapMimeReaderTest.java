package note_api.mail.imap;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import note_api.mail.MailMimeFactory;
import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailAttachmentRequest;
import note_api.mail.dto.SendMailRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보낸 메일을 그대로 다시 읽는 왕복 테스트.
 * {@link MailMimeFactory}가 만든 MIME을 {@link ImapMimeReader}가 원래 내용으로 복원해야 한다.
 */
class ImapMimeReaderTest {

    private static final String PNG_BASE64 =
            Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});

    @Test
    void read_keepsHtmlBodyInsteadOfStrippingTags() throws Exception {
        MimeMessage message = roundTrip(new SendMailRequest("to@example.com", "Hi", "<p>Hello <b>world</b></p>"));

        ImapMimeReader.Content content = ImapMimeReader.read(message);

        assertThat(content.bodyContentType()).isEqualTo("text/html");
        assertThat(content.body()).contains("<b>world</b>");
        assertThat(content.attachments()).isEmpty();
    }

    @Test
    void read_restoresInlineImageAsDataUrl() throws Exception {
        MimeMessage message = roundTrip(new SendMailRequest(
                "to@example.com", "Hi", "<p><img src=\"data:image/png;base64," + PNG_BASE64 + "\"></p>"));

        ImapMimeReader.Content content = ImapMimeReader.read(message);

        assertThat(content.body()).doesNotContain("cid:");
        assertThat(content.body()).contains("data:image/png;base64," + PNG_BASE64);
        assertThat(content.attachments()).isEmpty();
    }

    @Test
    void read_listsAttachmentsSeparatelyFromInlineImages() throws Exception {
        String payload = Base64.getEncoder().encodeToString("hello-file".getBytes(StandardCharsets.UTF_8));
        MimeMessage message = roundTrip(new SendMailRequest(
                "to@example.com",
                "Hi",
                "<p><img src=\"data:image/png;base64," + PNG_BASE64 + "\"></p>",
                List.of(new MailAttachmentRequest("notes.txt", "text/plain", payload))));

        ImapMimeReader.Content content = ImapMimeReader.read(message);

        assertThat(content.body()).contains("data:image/png;base64");
        assertThat(content.attachments())
                .singleElement()
                .satisfies(attachment -> {
                    assertThat(attachment.filename()).isEqualTo("notes.txt");
                    assertThat(attachment.contentType()).isEqualTo("text/plain");
                });
    }

    /** text 첨부가 본문 자리를 뺏으면 안 된다. */
    @Test
    void read_doesNotUseTextAttachmentAsBody() throws Exception {
        String payload = Base64.getEncoder().encodeToString("파일 내용".getBytes(StandardCharsets.UTF_8));
        MimeMessage message = roundTrip(new SendMailRequest(
                "to@example.com",
                "Hi",
                "<p>본문</p>",
                List.of(new MailAttachmentRequest("notes.txt", "text/plain", payload))));

        ImapMimeReader.Content content = ImapMimeReader.read(message);

        assertThat(content.body()).contains("본문").doesNotContain("파일 내용");
        assertThat(content.attachments()).singleElement().satisfies(
                attachment -> assertThat(attachment.filename()).isEqualTo("notes.txt"));
    }

    /** 한글 파일명이 RFC 2231로 인코딩됐다가 그대로 복원되는지 (이중 인코딩 회귀 방지). */
    @Test
    void readAttachment_restoresKoreanFilenameAndBytes() throws Exception {
        String payload = Base64.getEncoder().encodeToString("보고서 내용".getBytes(StandardCharsets.UTF_8));
        MimeMessage message = roundTrip(new SendMailRequest(
                "to@example.com",
                "Hi",
                "<p>See attached</p>",
                List.of(new MailAttachmentRequest("나의 보고서.pdf", "application/pdf", payload))));
        String attachmentId = ImapMimeReader.read(message).attachments().get(0).id();

        MailAttachmentContent attachment = ImapMimeReader.readAttachment(message, attachmentId);

        assertThat(attachment.filename()).isEqualTo("나의 보고서.pdf");
        assertThat(attachment.contentType()).isEqualTo("application/pdf");
        assertThat(new String(attachment.content(), StandardCharsets.UTF_8)).isEqualTo("보고서 내용");
    }

    /**
     * Outlook 등이 쓰는 encoded-word 파일명은 {@code getFileName()}이 풀어주지 않는다.
     * macOS가 만든 NFD 한글도 NFC로 합쳐야 한다.
     */
    @Test
    void read_decodesEncodedWordFilenameAndNormalizesNfd() throws Exception {
        String nfdName = Normalizer.normalize("보고서.pdf", Normalizer.Form.NFD);
        MimeMessage message = parse(
                """
                From: from@example.com
                To: to@example.com
                Subject: Hi
                Content-Type: multipart/mixed; boundary="b1"

                --b1
                Content-Type: text/html; charset=UTF-8

                <p>Body</p>
                --b1
                Content-Type: application/pdf
                Content-Disposition: attachment; filename="%s"
                Content-Transfer-Encoding: base64

                aGVsbG8=
                --b1--
                """
                        .formatted(MimeUtility.encodeText(nfdName, "UTF-8", "B")));

        ImapMimeReader.Content content = ImapMimeReader.read(message);

        assertThat(content.attachments())
                .singleElement()
                .satisfies(attachment -> assertThat(attachment.filename()).isEqualTo("보고서.pdf"));
    }

    private static MimeMessage parse(String raw) throws Exception {
        byte[] mime = raw.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);

        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime));
    }

    /** 발송용 MIME을 직렬화한 뒤 수신 측처럼 다시 파싱한다. */
    private static MimeMessage roundTrip(SendMailRequest request) throws Exception {
        Session session = Session.getInstance(new Properties());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MailMimeFactory.create(session, "from@example.com", request).writeTo(out);

        return new MimeMessage(session, new ByteArrayInputStream(out.toByteArray()));
    }
}
