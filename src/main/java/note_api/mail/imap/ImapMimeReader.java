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
 * MIME 트리를 훑어 본문 / 인라인 이미지 / 첨부파일을 나누는 분리.
 * <p>
 * 각 leaf part는 트리 상의 인덱스 경로({@code "1"}, {@code "0.2"})를 id로 갖고,
 * 첨부 다운로드 API가 이 id로 같은 part를 다시 찾는 키.
 */
final class ImapMimeReader {

    /** 인라인 이미지를 data URL로 심을 때 이 크기를 넘으면 첨부로 돌리는 한도. */
    private static final int MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024;

    /** 인스턴스 없이 static만 쓰는 유틸. */
    private ImapMimeReader() {}

    /**
     * @param body            HTML이 있으면 HTML, 없으면 plain text
     * @param bodyContentType {@code text/html} 또는 {@code text/plain}
     * @param preview         상세 목록 카드용 텍스트. cid 이미지를 넣기 전의 본문에서 뽑는 미리보기.
     */
    record Content(String body, String bodyContentType, String preview, List<MailAttachmentDto> attachments) {}

    static Content read(Part message) throws MessagingException, IOException {
        // 트리 순회 후 본문·미리보기·첨부 조립.
        Walker walker = new Walker();
        walker.walk(message, "");

        return walker.toContent();
    }

    /**
     * 목록 preview용. 이미지는 읽지 않고 text/plain을 우선, 없으면 HTML 태그만 벗기는 추출.
     * 상세 {@link #read}와 달리 첨부·cid 이미지는 디코딩하지 않음.
     */
    static String previewText(Part message) throws MessagingException, IOException {
        Object content = message.getContent();
        // plain은 그대로, HTML은 태그만 제거.
        if (content instanceof String text) {
            return message.isMimeType("text/html") ? stripTags(text) : text;
        }
        if (content instanceof Multipart multipart) {
            return previewFromMultipart(multipart);
        }

        return "";
    }

    /** multipart에서 plain을 먼저, 없으면 HTML 태그를 벗기는 미리보기. */
    private static String previewFromMultipart(Multipart multipart) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            var part = multipart.getBodyPart(i);
            if (part.isMimeType("text/plain") && !isAttachment(part) && part.getContent() instanceof String text) {
                return text;
            }
            if (part.getContent() instanceof Multipart nested) {
                String nestedText = previewFromMultipart(nested);
                if (StringUtils.hasText(nestedText)) {
                    return nestedText;
                }
            }
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            var part = multipart.getBodyPart(i);
            if (part.isMimeType("text/html") && !isAttachment(part) && part.getContent() instanceof String html) {
                return stripTags(html);
            }
        }

        return "";
    }

    /** 인덱스 경로로 part를 찾아 내용을 읽는 다운로드. */
    static MailAttachmentContent readAttachment(Part message, String attachmentId)
            throws MessagingException, IOException {
        // 경로가 없으면 없는 첨부.
        Part part = findByPath(message, attachmentId);
        if (part == null) {
            throw new ApiException(ErrorCode.MAIL_ATTACHMENT_NOT_FOUND, attachmentId);
        }

        return new MailAttachmentContent(
                filenameOf(part, attachmentId), baseMimeType(part), readBytes(part));
    }

    /** "1", "0.2" 같은 인덱스 경로로 part 탐색. */
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

    /** 트리를 훑으며 본문 후보와 첨부/인라인 이미지를 모으는 순회. */
    private static final class Walker {

        private final List<MailAttachmentDto> attachments = new ArrayList<>();
        private final Map<String, String> inlineByCid = new LinkedHashMap<>();
        private String html;
        private String plain;

        /** multipart는 내려가고, leaf는 본문·인라인·첨부로 나누는 순회. */
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
         * Content-ID가 있는 이미지를 data URL로 만들어 두는 준비.
         * 너무 크면 본문에 심지 않고 호출부가 첨부로 처리.
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

        /** HTML이 있으면 cid를 심은 HTML, 없으면 plain을 본문으로 고르는 조립. */
        private Content toContent() {
            String preview = StringUtils.hasText(plain) ? plain : stripTags(html);

            if (StringUtils.hasText(html)) {
                // cid: 이미지를 data URL로 심은 HTML을 본문으로 사용.
                return new Content(inlineCidUrls(html), "text/html", preview, List.copyOf(attachments));
            }

            return new Content(plain != null ? plain : "", "text/plain", preview, List.copyOf(attachments));
        }

        /** 본문의 {@code cid:} 참조를 data URL로 바꾸는 치환. */
        private String inlineCidUrls(String source) {
            String result = source;
            for (Map.Entry<String, String> entry : inlineByCid.entrySet()) {
                result = result.replace("cid:" + entry.getKey(), entry.getValue());
            }

            return result;
        }
    }

    /**
     * 본문으로 쓸 수 없는 leaf는 모두 첨부로 보는 분류.
     * 본문에 심기엔 너무 큰 인라인 이미지도 여기로 떨어져 다운로드는 가능.
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

    /** HTML 태그를 공백으로 바꿔 미리보기 문장으로 만드는 정리. */
    private static String stripTags(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }

        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    /** Content-ID 헤더에서 꺾쇠를 뺀 cid. 없으면 null. */
    private static String contentId(Part part) throws MessagingException {
        String[] header = part.getHeader("Content-ID");
        if (header == null || header.length == 0 || !StringUtils.hasText(header[0])) {
            return null;
        }

        return header[0].trim().replaceAll("^<|>$", "");
    }

    /**
     * 파일명을 읽는다. {@link Part#getFileName()}은 RFC 2231만 풀고
     * {@code =?UTF-8?B?...?=} 형태의 encoded-word는 그대로 두므로 한 번 더 디코딩.
     * macOS가 만든 NFD 한글은 NFC로 합쳐 표시가 깨지지 않게 하는 정리.
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
     * 디코딩 후 바이트 수. {@link Part#getSize()}는 base64 인코딩된 크기라 3/4을 곱해 보정.
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

    /** part 본문 바이트 읽기. */
    private static byte[] readBytes(Part part) throws MessagingException, IOException {
        try (InputStream in = part.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);

            return out.toByteArray();
        }
    }
}
