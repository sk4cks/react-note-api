package note_api.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import note_api.mail.dto.MailAttachmentRequest;
import note_api.mail.dto.SendMailRequest;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailMimeFactoryTest {

    @Test
    void create_keepsSimpleHtmlWithoutMultipart() throws Exception {
        SendMailRequest request = new SendMailRequest("to@example.com", "Hi", "<p>Hello</p>");
        String mime = toString(MailMimeFactory.create(Session.getInstance(new Properties()), "from@example.com", request));

        assertThat(mime).contains("To: to@example.com");
        assertThat(mime).contains("text/html");
        assertThat(mime).contains("Hello");
        assertThat(mime).doesNotContain("multipart/mixed");
        assertThat(mime).doesNotContain("multipart/related");
    }

    @Test
    void create_rewritesDataUrlImagesToCid() throws Exception {
        String png = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        SendMailRequest request =
                new SendMailRequest("to@example.com", "Hi", "<p><img src=\"data:image/png;base64," + png + "\"></p>");
        String mime = toString(MailMimeFactory.create(Session.getInstance(new Properties()), null, request));

        assertThat(mime).contains("multipart/related");
        assertThat(mime).contains("cid:inline-img-1@note");
        assertThat(mime).doesNotContain("data:image/png;base64");
        assertThat(mime).contains("Content-ID: <inline-img-1@note>");
    }

    @Test
    void create_addsAttachmentPart() throws Exception {
        String payload = Base64.getEncoder().encodeToString("hello-file".getBytes(StandardCharsets.UTF_8));
        SendMailRequest request = new SendMailRequest(
                "to@example.com",
                "Hi",
                "<p>See attached</p>",
                List.of(new MailAttachmentRequest("notes.txt", "text/plain", payload)));
        String mime = toString(MailMimeFactory.create(Session.getInstance(new Properties()), null, request));

        assertThat(mime).contains("multipart/mixed");
        assertThat(mime).contains("notes.txt");
        assertThat(mime).contains("See attached");
    }

    /**
     * 한글 파일명은 RFC 2231({@code filename*=UTF-8''..})로만 인코딩돼야 한다.
     * encoded-word로 미리 바꾸면 파라미터 분할과 겹쳐 base64가 잘린다.
     */
    @Test
    void create_encodesKoreanFilenameAsRfc2231() throws Exception {
        String payload = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        SendMailRequest request = new SendMailRequest(
                "to@example.com",
                "Hi",
                "<p>x</p>",
                List.of(new MailAttachmentRequest("나의 보고서.pdf", "application/pdf", payload)));

        String mime = toString(MailMimeFactory.create(Session.getInstance(new Properties()), null, request));

        assertThat(mime).contains("UTF-8''%EB%82%98%EC%9D%98%20%EB%B3%B4%EA%B3%A0%EC%84%9C.pdf");
        assertThat(mime).doesNotContain("=?UTF-8?B?");
    }

    /** macOS NFD 파일명도 헤더에는 NFC percent-encoding으로 나가야 한다. */
    @Test
    void create_normalizesNfdFilenameToNfc() throws Exception {
        String nfdName = Normalizer.normalize("보고서.pdf", Normalizer.Form.NFD);
        String payload = Base64.getEncoder().encodeToString("x".getBytes(StandardCharsets.UTF_8));
        SendMailRequest request = new SendMailRequest(
                "to@example.com",
                "Hi",
                "<p>x</p>",
                List.of(new MailAttachmentRequest(nfdName, "application/pdf", payload)));

        String mime = toString(MailMimeFactory.create(Session.getInstance(new Properties()), null, request));

        assertThat(mime).contains("UTF-8''%EB%B3%B4%EA%B3%A0%EC%84%9C.pdf");
        assertThat(mime).doesNotContain("%E1%84%");
    }

    @Test
    void create_rejectsOversizedPayload() {
        byte[] tooBig = new byte[MailMimeFactory.MAX_TOTAL_BYTES + 1];
        String payload = Base64.getEncoder().encodeToString(tooBig);
        SendMailRequest request = new SendMailRequest(
                "to@example.com",
                "Hi",
                "<p>x</p>",
                List.of(new MailAttachmentRequest("big.bin", "application/octet-stream", payload)));

        assertThatThrownBy(() -> MailMimeFactory.create(Session.getInstance(new Properties()), null, request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10MB")
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.MAIL_ATTACHMENT_TOO_LARGE);
    }

    private static String toString(MimeMessage message) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);

        return out.toString(StandardCharsets.UTF_8);
    }
}
