package br.com.jorgemelo.nimbusfilemanager.organization.application.resolver;

import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Month;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationDate;
import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationRuleResult;
import br.com.jorgemelo.nimbusfilemanager.organization.application.rule.OrganizationRuleEngine;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleReason;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationRuleType;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection.OrganizationCandidate;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileCategory;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

@ExtendWith(MockitoExtension.class)
class OrganizationDestinationResolverTest {

	@Mock
	private OrganizationDateResolver dateResolver;

	@Mock
	private OrganizationRuleEngine ruleEngine;

	@Mock
	private OrganizationLayoutResolver layoutResolver;

	@Test
	void resolveShouldRejectFileNameWithPathSegments() {
		OrganizationCandidate candidate = candidate("../evil.jpg");

		OrganizationDate date = new OrganizationDate("202405", "09", false);

		OrganizationRuleResult rule = new OrganizationRuleResult(OrganizationRuleType.CAMERA,
				OrganizationRuleReason.FILE_NAME, FileCategory.MEDIA, MediaSubcategory.CAMERA, FileType.PHOTO);

		Path folder = Path.of("C:/target/202405/09/CAMERA/IMAGENS");

		when(dateResolver.resolve(candidate)).thenReturn(date);
		when(ruleEngine.classify(candidate)).thenReturn(rule);
		when(layoutResolver.resolveFolder(Path.of("C:/target"), "DEFAULT", "202405", "09", "CAMERA", "IMAGENS"))
				.thenReturn(folder);

		var resolver = service();

		Path target = Path.of("C:/target");

		Assertions.assertThatThrownBy(() -> resolver.resolve(target, "DEFAULT", candidate))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("não pode conter segmentos de caminho");
	}

	@Test
	void resolveShouldCombineDateRuleAndLayoutIntoDestination() {
		OrganizationCandidate candidate = candidate();

		OrganizationDate date = new OrganizationDate("202405", "09", false);

		OrganizationRuleResult rule = new OrganizationRuleResult(OrganizationRuleType.CAMERA,
				OrganizationRuleReason.FILE_NAME, FileCategory.MEDIA, MediaSubcategory.CAMERA, FileType.PHOTO);

		Path folder = Path.of("C:/target/202405/09/CAMERA/IMAGENS");

		when(dateResolver.resolve(candidate)).thenReturn(date);
		when(ruleEngine.classify(candidate)).thenReturn(rule);
		when(layoutResolver.resolveFolder(Path.of("C:/target"), "DEFAULT", "202405", "09", "CAMERA", "IMAGENS"))
				.thenReturn(folder);

		var destination = service().resolve(Path.of("C:/target"), "DEFAULT", candidate);

		Assertions.assertThat(destination.folder()).isEqualTo(folder);
		Assertions.assertThat(destination.file()).isEqualTo(folder.resolve("photo.jpg").normalize());
		Assertions.assertThat(destination.date()).isSameAs(date);
		Assertions.assertThat(destination.ruleResult()).isSameAs(rule);
	}

	private OrganizationDestinationResolver service() {
		return new OrganizationDestinationResolver(dateResolver, ruleEngine, layoutResolver);
	}

	private OrganizationCandidate candidate() {
		return candidate("photo.jpg");
	}

	private OrganizationCandidate candidate(String fileName) {
		return new OrganizationCandidate(1L, fileName, "jpg", FileType.PHOTO, 100L, "C:/input/photo.jpg", "C:/input",
				2024, 5, 9, "202405", LocalDateTime.of(2024, Month.MAY, 9, 10, 30), FileCategory.MEDIA,
				MediaSubcategory.CAMERA);
	}
}