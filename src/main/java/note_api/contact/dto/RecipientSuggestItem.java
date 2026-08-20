package note_api.contact.dto;

import java.util.List;

public record RecipientSuggestItem(
        String type, Long id, String displayName, String email, List<String> emails) {}
