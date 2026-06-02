package note_api.photo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PhotoController {

    @GetMapping("/photos")
    public List<String> photos() {
        return List.of("sample-photo-1", "sample-photo-2");
    }
}
