package note_api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        // SimpleClientHttpRequestFactory(HttpURLConnection) 는 401 body 를 비우는 경우가 있음
        RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());
        restTemplate.getMessageConverters().stream()
                .filter(StringHttpMessageConverter.class::isInstance)
                .map(StringHttpMessageConverter.class::cast)
                .forEach(converter -> converter.setDefaultCharset(StandardCharsets.UTF_8));

        return restTemplate;
    }
}
