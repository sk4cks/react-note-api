package note_api.contact;

import note_api.auth.AuthServerClient;
import note_api.contact.dto.ContactGroupResponse;
import note_api.contact.dto.ContactGroupShareResponse;
import note_api.contact.dto.ContactResponse;
import note_api.contact.dto.RecipientSuggestItem;
import note_api.mail.MailService;
import note_api.mail.dto.MailRecipientSuggestion;
import lombok.RequiredArgsConstructor;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 주소록·그룹·공유. Auth Server 내부 API를 프록시하고, 수신자 제안은 메일 히스토리와 합친다. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ContactController {

    private final AuthServerClient authServerClient;
    private final MailService mailService;

    @GetMapping("/contacts")
    public List<ContactResponse> listContacts(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String q) {
        // JWT sub = Auth userId. 주소록은 Auth 내부 API.
        return authServerClient.listContacts(jwt.getSubject(), q);
    }

    /** 개인 연락처 추가. Auth 내부 API. */
    @PostMapping("/contacts")
    public ContactResponse createContact(
            @AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        return authServerClient.createContact(jwt.getSubject(), body);
    }

    /** 개인 연락처 삭제. Auth 내부 API. */
    @PostMapping("/contacts/{id}/delete")
    public ResponseEntity<Void> deleteContact(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        authServerClient.deleteContact(jwt.getSubject(), id);

        return ResponseEntity.noContent().build();
    }

    /** 그룹 목록. 내 그룹 + 공유받은 그룹. */
    @GetMapping("/contact-groups")
    public List<ContactGroupResponse> listGroups(@AuthenticationPrincipal Jwt jwt) {
        return authServerClient.listContactGroups(jwt.getSubject());
    }

    /** 그룹 생성. */
    @PostMapping("/contact-groups")
    public ContactGroupResponse createGroup(
            @AuthenticationPrincipal Jwt jwt, @RequestBody Map<String, Object> body) {
        return authServerClient.createContactGroup(jwt.getSubject(), body);
    }

    /** 그룹 이름 변경. */
    @PostMapping("/contact-groups/{id}/update")
    public ContactGroupResponse renameGroup(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return authServerClient.updateContactGroup(jwt.getSubject(), id, body);
    }

    /** 그룹 삭제. */
    @PostMapping("/contact-groups/{id}/delete")
    public ResponseEntity<Void> deleteGroup(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        authServerClient.deleteContactGroup(jwt.getSubject(), id);

        return ResponseEntity.noContent().build();
    }

    /** 그룹 멤버를 통째로 교체한다. */
    @PostMapping("/contact-groups/{id}/members")
    public ContactGroupResponse replaceMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return authServerClient.replaceContactGroupMembers(jwt.getSubject(), id, body);
    }

    /** 그룹 공유 목록. */
    @GetMapping("/contact-groups/{id}/shares")
    public List<ContactGroupShareResponse> listShares(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return authServerClient.listContactGroupShares(jwt.getSubject(), id);
    }

    /** 그룹을 다른 USER_ID에 공유한다. */
    @PostMapping("/contact-groups/{id}/shares")
    public ContactGroupShareResponse shareGroup(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return authServerClient.shareContactGroup(jwt.getSubject(), id, body);
    }

    /** 공유 회수. 공유받은 쪽에서 나가기에도 쓴다. */
    @PostMapping("/contact-groups/{id}/shares/{shareId}/delete")
    public ResponseEntity<Void> revokeShare(
            @AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable Long shareId) {
        authServerClient.revokeContactGroupShare(jwt.getSubject(), id, shareId);

        return ResponseEntity.noContent().build();
    }

    /** 주소록(그룹·연락처) + 메일 히스토리를 합친 자동완성. */
    @GetMapping("/mail/recipients/suggest")
    public List<RecipientSuggestItem> suggestRecipients(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String q) {
        String userId = jwt.getSubject();

        // Auth 주소록(그룹·연락처) + 이 BFF의 메일 히스토리.
        List<RecipientSuggestItem> fromContacts = authServerClient.suggestContacts(userId, q);
        List<MailRecipientSuggestion> fromHistory = mailService.suggestRecipients(userId, q);

        List<RecipientSuggestItem> merged = new ArrayList<>(fromContacts);
        Set<String> seenEmails = new LinkedHashSet<>();

        // 주소록에 이미 있는 메일은 히스토리에서 빼서 중복 칩을 막는다.
        for (RecipientSuggestItem item : fromContacts) {
            if ("contact".equals(item.type()) && item.email() != null) {
                seenEmails.add(item.email().toLowerCase(Locale.ROOT));
            }

            if ("group".equals(item.type()) && item.emails() != null) {
                for (String email : item.emails()) {
                    seenEmails.add(email.toLowerCase(Locale.ROOT));
                }
            }
        }

        for (MailRecipientSuggestion suggestion : fromHistory) {
            String key = suggestion.email().toLowerCase(Locale.ROOT);

            if (seenEmails.add(key)) {
                merged.add(new RecipientSuggestItem(
                        "history", null, suggestion.displayName(), suggestion.email(), List.of()));
            }
        }

        return merged.stream().limit(30).toList();
    }
}
