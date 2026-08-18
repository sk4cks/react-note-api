package note_api.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import note_api.mail.dto.MailAttachmentRequest;
import note_api.mail.dto.SendMailRequest;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 본문 HTML + 인라인 이미지(data URL) + 첨부파일을 multipart MIME으로 만든다.
 * Gmail raw send와 IMAP SMTP가 같은 경로를 쓴다.
 */
public final class MailMimeFactory {

    static final int MAX_TOTAL_BYTES = 10 * 1024 * 1024;
    static final int MAX_ATTACHMENTS = 20;

    private static final Pattern DATA_IMG = Pattern.compile(
            "src=(['\"])data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\\r\\n]+)\\1",
            Pattern.CASE_INSENSITIVE);

    private MailMimeFactory() {}

    public static MimeMessage create(Session session, String from, SendMailRequest request)
            throws MessagingException {
        MimeMessage message = new MimeMessage(session);
        if (StringUtils.hasText(from)) {
            message.setFrom(new InternetAddress(from));
        }
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(request.to(), false));
        message.setSubject(request.subject() != null ? request.subject() : "", "UTF-8");

        InlineHtml inline = rewriteDataUrls(request.body() != null ? request.body() : "");
        List<DecodedAttachment> files = decodeAttachments(request.attachments());
        if (files.size() > MAX_ATTACHMENTS) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_TOO_MANY, MAX_ATTACHMENTS);
        }

        int totalBytes = inline.totalBytes();
        for (DecodedAttachment attachment : files) {
            totalBytes += attachment.bytes().length;
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_TOO_LARGE, MAX_TOTAL_BYTES / (1024 * 1024));
        }

        boolean hasInline = !inline.parts().isEmpty();
        boolean hasFiles = !files.isEmpty();
        if (!hasInline && !hasFiles) {
            message.setContent(inline.html(), "text/html; charset=UTF-8");

            return message;
        }

        MimeBodyPart htmlPart = htmlBodyPart(inline.html());
        if (!hasFiles) {
            message.setContent(relatedMultipart(htmlPart, inline.parts()));

            return message;
        }

        MimeMultipart mixed = new MimeMultipart("mixed");
        if (hasInline) {
            MimeBodyPart relatedWrapper = new MimeBodyPart();
            relatedWrapper.setContent(relatedMultipart(htmlPart, inline.parts()));
            mixed.addBodyPart(relatedWrapper);
        } else {
            mixed.addBodyPart(htmlPart);
        }
        addAttachments(mixed, files);
        message.setContent(mixed);

        return message;
    }

    public static byte[] toRfc822Bytes(String from, SendMailRequest request) {
        try {
            MimeMessage message = create(Session.getInstance(new Properties()), from, request);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);

            return out.toByteArray();

        } catch (MessagingException | IOException ex) {
            throw new IllegalStateException("Failed to build MIME message", ex);
        }
    }

    private static MimeBodyPart htmlBodyPart(String html) throws MessagingException {
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(html, "text/html; charset=UTF-8");

        return htmlPart;
    }

    private static MimeMultipart relatedMultipart(MimeBodyPart htmlPart, List<InlineImage> images)
            throws MessagingException {
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(htmlPart);
        for (InlineImage image : images) {
            MimeBodyPart imagePart = new MimeBodyPart();
            imagePart.setContentID("<" + image.cid() + ">");
            imagePart.setDisposition(Part.INLINE);
            imagePart.setDataHandler(new DataHandler(new ByteArrayDataSource(image.bytes(), image.contentType())));
            related.addBodyPart(imagePart);
        }

        return related;
    }

    private static void addAttachments(MimeMultipart mixed, List<DecodedAttachment> files)
            throws MessagingException {
        for (DecodedAttachment attachment : files) {
            mixed.addBodyPart(toAttachmentPart(attachment));
        }
    }

    /**
     * 파일명은 raw로 넘긴다. Jakarta Mail이 RFC 2231로 인코딩하므로
     * 미리 encoded-word로 바꾸면 파라미터 분할과 겹쳐 이름이 깨진다.
     */
    private static MimeBodyPart toAttachmentPart(DecodedAttachment attachment) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(
                new DataHandler(new ByteArrayDataSource(attachment.bytes(), safeContentType(attachment.contentType()))));
        part.setFileName(safeFilename(attachment.filename()));
        part.setDisposition(Part.ATTACHMENT);

        return part;
    }

    private static InlineHtml rewriteDataUrls(String html) {
        Matcher matcher = DATA_IMG.matcher(html);
        StringBuilder rewritten = new StringBuilder();
        List<InlineImage> parts = new ArrayList<>();
        int index = 1;
        int totalBytes = 0;
        while (matcher.find()) {
            String mime = matcher.group(2).toLowerCase(Locale.ROOT);
            byte[] bytes = decodeBase64(matcher.group(3));
            totalBytes += bytes.length;
            String cid = "inline-img-" + index++ + "@note";
            parts.add(new InlineImage(cid, mime, bytes));
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement("src=\"cid:" + cid + "\""));
        }
        matcher.appendTail(rewritten);

        return new InlineHtml(rewritten.toString(), parts, totalBytes);
    }

    private static List<DecodedAttachment> decodeAttachments(List<MailAttachmentRequest> files) {
        List<DecodedAttachment> decoded = new ArrayList<>(files.size());
        for (MailAttachmentRequest attachment : files) {
            decoded.add(new DecodedAttachment(
                    attachment.filename(),
                    attachment.contentType(),
                    decodeBase64(attachment.contentBase64())));
        }

        return decoded;
    }

    private static byte[] decodeBase64(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_EMPTY);
        }
        try {
            return Base64.getMimeDecoder().decode(value);

        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_ENCODING);
        }
    }

    /**
     * 경로 분리자·헤더 위험 문자를 걷어내고, macOS NFD 한글은 NFC로 합친다.
     * 외부 클라이언트가 자모 분해 형태로 받지 않도록 발송 시점에 정규화한다.
     */
    private static String safeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_FILENAME);
        }
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = Normalizer.normalize(name.replaceAll("[\\r\\n\"]", "_"), Normalizer.Form.NFC);
        if (!StringUtils.hasText(name)) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_FILENAME);
        }

        return name;
    }

    private static String safeContentType(String contentType) {
        if (!StringUtils.hasText(contentType) || contentType.indexOf('\r') >= 0 || contentType.indexOf('\n') >= 0) {
            return "application/octet-stream";
        }

        return contentType;
    }

    private record InlineImage(String cid, String contentType, byte[] bytes) {}

    private record InlineHtml(String html, List<InlineImage> parts, int totalBytes) {}

    private record DecodedAttachment(String filename, String contentType, byte[] bytes) {}
}
