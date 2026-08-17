package note_api.mail.imap;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeUtility;
import note_api.common.exception.ApiException;
import note_api.common.exception.ErrorCode;
import note_api.mail.dto.MailAttachmentContent;
import note_api.mail.dto.MailAttachmentDto;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MIME 트리를 훑어 본문 / 인라인 이미지 / 첨부파일을 분리한다.
 * <p>
 * 각 leaf part는 트리 상의 인덱스 경로({@code "1"}, {@code "0.2"})를 id로 갖고,
 * 첨부 다운로드 API가 이 id로 같은 part를 다시 찾는다.
 */
final class ImapMimeReader {

    /** 인라인 이미지를 data URL로 심을 때 이 크기를 넘으면 첨부로 돌린다. */
    private static final int MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024;

    private ImapMimeReader() {}

    /**
     * @param body            HTML이 있으면 HTML, 없으면 plain text
     * @param bodyContentType {@code text/html} 또는 {@code text/plain}
     */
    record Content(String body, String bodyContentType, List<MailAttachmentDto> attachments) {}

    static Content read(Part message) throws MessagingException, IOException {
        Walker walker = new Walker();
        walker.walk(message, "");

        return walker.toContent();
    }

    /** 인덱스 경로로 part를 찾아 내용을 읽는다. */
    static MailAttachmentContent readAttachment(Part message, String attachmentId)
            throws MessagingException, IOException {
        Part part = findByPath(message, attachmentId);
        if (part == null) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_NOT_FOUND, "Attachment not found: " + attachmentId);
        }

        return new MailAttachmentContent(
                filenameOf(part, attachmentId), baseMimeType(part), readBytes(part));
    }

    private static Part findByPath(Part part, String path) throws MessagingException, IOException {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        Part current = part;
        for (String segment : path.split("\\.")) {
            if (!current.isMimeType("multipart/*")) {
                return null;
            }
            int index;
            try {
                index = Integer.parseInt(segment);

            } catch (NumberFormatException ex) {
                return null;
            }
            Multipart multipart = (Multipart) current.getContent();
            if (index < 0 || index >= multipart.getCount()) {
                return null;
            }
            current = multipart.getBodyPart(index);
        }

        return current;
    }

    /** 트리를 훑으며 본문 후보와 첨부/인라인 이미지를 모은다. */
    private static final class Walker {

        private final List<MailAttachmentDto> attachments = new ArrayList<>();
        private final Map<String, String> inlineByCid = new LinkedHashMap<>();
        private String html;
        private String plain;

        private void walk(Part part, String path) throws MessagingException, IOException {
            if (part.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    walk(multipart.getBodyPart(i), path.isEmpty() ? String.valueOf(i) : path + "." + i);
                }

                return;
            }
            if (collectInlineImage(part)) {
                return;
            }
            if (isAttachment(part)) {
                attachments.add(new MailAttachmentDto(
                        path, filenameOf(part, fallbackFilename(part)), baseMimeType(part), decodedSizeOf(part)));

                return;
            }
            if (html == null && part.isMimeType("text/html") && part.getContent() instanceof String text) {
                html = text;

                return;
            }
            if (plain == null && part.isMimeType("text/plain") && part.getContent() instanceof String text) {
                plain = text;
            }
        }

        /**
         * Content-ID가 있는 이미지를 data URL로 만들어 둔다.
         * 너무 크면 본문에 심지 않고 호출부가 첨부로 처리하게 둔다.
         */
        private boolean collectInlineImage(Part part) throws MessagingException, IOException {
            String cid = contentId(part);
            if (cid == null || !part.isMimeType("image/*") || decodedSizeOf(part) > MAX_INLINE_IMAGE_BYTES) {
                return false;
            }
            byte[] bytes = readBytes(part);
            inlineByCid.put(cid, "data:" + baseMimeType(part) + ";base64," + Base64.getEncoder().encodeToString(bytes));

            return true;
        }

        private Content toContent() {
            if (StringUtils.hasText(html)) {
                return new Content(inlineCidUrls(html), "text/html", List.copyOf(attachments));
            }

            return new Content(plain != null ? plain : "", "text/plain", List.copyOf(attachments));
        }

        /** 본문의 {@code cid:} 참조를 data URL로 치환한다. */
        private String inlineCidUrls(String source) {
            String result = source;
            for (Map.Entry<String, String> entry : inlineByCid.entrySet()) {
                result = result.replace("cid:" + entry.getKey(), entry.getValue());
            }

            return result;
        }
    }

    /**
     * 본문으로 쓸 수 없는 leaf는 모두 첨부로 본다.
     * 본문에 심기엔 너무 큰 인라인 이미지도 여기로 떨어져 최소한 다운로드는 된다.
     */
    private static boolean isAttachment(Part part) throws MessagingException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || StringUtils.hasText(part.getFileName())) {
            return true;
        }

        return !part.isMimeType("text/plain") && !part.isMimeType("text/html");
    }

    /** 파일명 없는 part용 이름. {@code image/png} → {@code image.png}. */
    private static String fallbackFilename(Part part) throws MessagingException {
        String[] type = baseMimeType(part).split("/");

        return type.length == 2 ? type[0] + "." + type[1] : "attachment";
    }

    private static String contentId(Part part) throws MessagingException {
        String[] header = part.getHeader("Content-ID");
        if (header == null || header.length == 0 || !StringUtils.hasText(header[0])) {
            return null;
        }

        return header[0].trim().replaceAll("^<|>$", "");
    }

    /**
     * 파일명을 읽는다. {@link Part#getFileName()}은 RFC 2231만 풀고
     * {@code =?UTF-8?B?...?=} 형태의 encoded-word는 그대로 두므로 한 번 더 디코딩한다.
     * macOS가 만든 NFD 한글은 NFC로 합쳐 표시가 깨지지 않게 한다.
     */
    private static String filenameOf(Part part, String fallback) throws MessagingException {
        String filename = part.getFileName();
        if (!StringUtils.hasText(filename)) {
            return fallback;
        }
        try {
            return Normalizer.normalize(MimeUtility.decodeText(filename), Normalizer.Form.NFC);

        } catch (UnsupportedEncodingException ex) {
            return filename;
        }
    }

    /** {@code text/html; charset=UTF-8} → {@code text/html}. */
    private static String baseMimeType(Part part) throws MessagingException {
        String contentType = part.getContentType();
        if (!StringUtils.hasText(contentType)) {
            return "application/octet-stream";
        }
        int separator = contentType.indexOf(';');
        String base = separator < 0 ? contentType : contentType.substring(0, separator);

        return base.trim().toLowerCase();
    }

    /**
     * 디코딩 후 바이트 수. {@link Part#getSize()}는 base64 인코딩된 크기라 3/4을 곱해 보정한다.
     */
    private static long decodedSizeOf(Part part) throws MessagingException {
        int size = part.getSize();
        if (size < 0) {
            return 0;
        }
        String[] encoding = part.getHeader("Content-Transfer-Encoding");
        if (encoding != null && encoding.length > 0 && "base64".equalsIgnoreCase(encoding[0].trim())) {
            return size / 4L * 3L;
        }

        return size;
    }

    private static byte[] readBytes(Part part) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);

            return out.toByteArray();
        }
    }
}
