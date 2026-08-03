package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class OpenApiConfig {

	@Value("${application.version}")
	private String version;

	@Bean
	public OpenAPI nimbusFileManagerOpenApi() {
		return new OpenAPI().info(new Info().title("Nimbus File Manager API").version(version).description("""
				API for managing personal digital media collections.

				Features:
				- Media inventory and indexing
				- Metadata extraction from EXIF, video streams and the file system
				- Automatic media organization
				- Duplicate detection
				- Media statistics and reports
				""").license(new License().name("PolyForm Noncommercial License 1.0.0").url(
						"https://polyformproject.org/licenses/noncommercial/1.0.0"))
				.contact(new Contact().name("Jorge Melo").url("https://github.com/JorgeFrancisco")
						.email("jorgefrancisco.melo@gmail.com")));
	}
}