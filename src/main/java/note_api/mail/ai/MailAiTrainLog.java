package note_api.mail.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import note_api.mail.dto.SendMailRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** 발송에 성공한 메일을 사용자별 jsonl로 남기는 기록. */
@Component
public class MailAiTrainLog {

    private static final Logger log = LoggerFactory.getLogger(MailAiTrainLog.class);

    private final ObjectMapper objectMapper;
    private final Path trainDir;

    /** 학습 예시 디렉터리 주입. */
    public MailAiTrainLog(
            ObjectMapper objectMapper,
            @Value("${app.ai.train-dir:${user.home}/mail-ai-data}") String trainDir) {
        this.objectMapper = objectMapper;
        this.trainDir = Path.of(trainDir);
    }

    /** 발송 성공 메일을 train.jsonl에 한 줄 추가. 같은 줄이 있으면 생략. */
    public void record(String userId, SendMailRequest request) {
        try {
            String body = MailAiText.plain(request.body());

            if (!StringUtils.hasText(userId) || !StringUtils.hasText(request.subject()) || !StringUtils.hasText(body)) {
                return;
            }

            Path dir = trainDir.resolve(safe(userId));
            Files.createDirectories(dir);
            String line = objectMapper.writeValueAsString(example(request, body));
            Path train = dir.resolve("train.jsonl");
            Path valid = dir.resolve("valid.jsonl");

            synchronized (this) {
                if (Files.exists(train) && Files.readAllLines(train).contains(line)) {
                    return;
                }

                String row = line + "\n";
                Files.writeString(train, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                if (!Files.exists(valid) || Files.size(valid) == 0) {
                    Files.writeString(valid, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }

        } catch (Exception ex) {
            log.warn("sent mail train example skipped: {}", ex.toString());
        }
    }

    /** 수신자 안내와 실제 보낸 제목·본문으로 한 예시 구성. */
    private static TrainExample example(SendMailRequest request, String body) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("받는 사람: ").append(String.join(", ", request.to())).append('\n');

        if (request.cc() != null && !request.cc().isEmpty()) {
            prompt.append("참조: ").append(String.join(", ", request.cc())).append('\n');
        }

        prompt.append("이 수신자에게 보낼 메일의 제목과 본문을 작성.");

        List<TrainMessage> messages = new ArrayList<>();
        messages.add(new TrainMessage("user", prompt.toString()));
        messages.add(new TrainMessage("assistant", "제목: " + request.subject().trim() + "\n본문: " + body));
        return new TrainExample(messages);
    }

    /** 폴더 이름에 쓸 수 있게 사용자 id 정리. */
    private static String safe(String userId) {
        return userId.replaceAll("[^A-Za-z0-9._@-]", "_");
    }

    private record TrainMessage(String role, String content) {}

    private record TrainExample(List<TrainMessage> messages) {}
}
