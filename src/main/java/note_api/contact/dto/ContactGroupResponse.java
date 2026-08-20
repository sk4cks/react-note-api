package note_api.contact.dto;

import java.util.List;

public record ContactGroupResponse(
        Long id,
        String name,
        boolean owned,
        String permission,
        List<ContactResponse> members) {}
