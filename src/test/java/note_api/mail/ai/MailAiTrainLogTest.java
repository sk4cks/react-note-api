package note_api.mail.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import note_api.mail.dto.SendMailRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MailAiTrainLogTest {

    @TempDir
    Path trainDir;

    @Test
    void recordsSentMailAsTrainAndValid() throws Exception {
        MailAiTrainLog log = new MailAiTrainLog(new ObjectMapper(), trainDir.toString());
        SendMailRequest request = new SendMailRequest(
                java.util.List.of("a@note.local"),
                java.util.List.of("b@note.local"),
                java.util.List.of(),
                "회의 일정 조정",
                "<p>회의를 미루고 싶습니다.</p>",
                java.util.List.of(),
                null);
        log.record("draft chk", request);
        log.record("draft chk", request);

        Path userDir = trainDir.resolve("draft_chk");
        String train = Files.readString(userDir.resolve("train.jsonl"));
        String valid = Files.readString(userDir.resolve("valid.jsonl"));
        JsonNode message = new ObjectMapper().readTree(train).path("messages");

        assertThat(train.lines().count()).isEqualTo(1);
        assertThat(train).isEqualTo(valid);
        assertThat(message.get(0).path("content").asText()).contains("a@note.local").contains("b@note.local");
        assertThat(message.get(1).path("content").asText()).contains("회의 일정 조정").contains("회의를 미루고 싶습니다.");
    }
}
