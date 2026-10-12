package note_api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class MainApplication {

	/** Spring Boot 기동. */
	public static void main(String[] args) {
		SpringApplication.run(MainApplication.class, args);
	}

}
