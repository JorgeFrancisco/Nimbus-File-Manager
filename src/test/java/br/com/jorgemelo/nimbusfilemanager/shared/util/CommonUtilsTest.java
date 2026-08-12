package br.com.jorgemelo.nimbusfilemanager.shared.util;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileValidationUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.TestingAuthenticationToken;

class CommonUtilsTest {

	@TempDir
	Path tempDir;

	@Test
	void textUtilsShouldNormalizeBlankValues() {
		Assertions.assertThat(TextUtils.blankToNull("  value  ")).isEqualTo("value");
		Assertions.assertThat(TextUtils.blankToNull("   ")).isNull();
		Assertions.assertThat(TextUtils.blankToNull(null)).isNull();
		Assertions.assertThat(TextUtils.upperBlankToNull(" jpg ")).isEqualTo("JPG");
	}

	@Test
	void numberUtilsShouldApplyCommonNumericGuards() {
		Assertions.assertThat(NumberUtils.roundPercentage(12.345)).isEqualTo(12.35);
		Assertions.assertThat(NumberUtils.limit(null, 10, 100)).isEqualTo(10);
		Assertions.assertThat(NumberUtils.limit(200, 10, 100)).isEqualTo(100);
		Assertions.assertThat(NumberUtils.toInt((long) Integer.MAX_VALUE + 1)).isEqualTo(Integer.MAX_VALUE);
		Assertions.assertThat(NumberUtils.zeroIfNull(null)).isZero();
		Assertions.assertThat(NumberUtils.zeroIfNull(42L)).isEqualTo(42L);
	}

	@Test
	void sizeFormatterShouldUseReadableUnits() {
		Assertions.assertThat(SizeFormatter.format(512)).isEqualTo("512 B");
		Assertions.assertThat(SizeFormatter.format(1024)).isEqualTo("1.00 KB");
		Assertions.assertThat(SizeFormatter.format(1024 * 1024)).isEqualTo("1.00 MB");
		Assertions.assertThat(SizeFormatter.format(1024L * 1024 * 1024)).isEqualTo("1.00 GB");
		Assertions.assertThat(SizeFormatter.format(1024L * 1024 * 1024 * 1024)).isEqualTo("1.00 TB");
		Assertions.assertThat(SizeFormatter.format(1024L * 1024 * 1024 * 1024 * 1024)).isEqualTo("1.00 PB");
		Assertions.assertThat(SizeFormatter.format(Long.MAX_VALUE)).endsWith("EB");
	}

	@Test
	void pageUtilsShouldCreateFirstPageAndRemoveSort() {
		Assertions.assertThat(PageUtils.firstPage(25)).isEqualTo(PageRequest.of(0, 25));

		var sortedPage = PageRequest.of(2, 50, Sort.by("fileName"));

		Assertions.assertThat(PageUtils.withoutSort(sortedPage).getPageNumber()).isEqualTo(2);
		Assertions.assertThat(PageUtils.withoutSort(sortedPage).getPageSize()).isEqualTo(50);
		Assertions.assertThat(PageUtils.withoutSort(sortedPage).getSort().isUnsorted()).isTrue();
	}

	@Test
	void pageUtilsCappedShouldRemoveSortAndClampPageSize() {
		var requestedSmallPage = PageRequest.of(1, 20, Sort.by("fileName"));

		Assertions.assertThat(PageUtils.capped(requestedSmallPage, 500)).isEqualTo(PageRequest.of(1, 20));

		var requestedHugePage = PageRequest.of(0, 1_000_000);

		Assertions.assertThat(PageUtils.capped(requestedHugePage, 500)).isEqualTo(PageRequest.of(0, 500));
	}

	@Test
	void pageUtilsShouldValidateSavedPageSizeOrFallBackToDefault() {
		Assertions.assertThat(PageUtils.validSizeOrDefault("50", List.of(20, 50, 100), 20)).isEqualTo(50);
		Assertions.assertThat(PageUtils.validSizeOrDefault("999", List.of(20, 50, 100), 20)).isEqualTo(20);
		Assertions.assertThat(PageUtils.validSizeOrDefault("abc", List.of(20, 50, 100), 20)).isEqualTo(20);
		Assertions.assertThat(PageUtils.validSizeOrDefault("  ", List.of(20, 50, 100), 20)).isEqualTo(20);
		Assertions.assertThat(PageUtils.validSizeOrDefault(null, List.of(20, 50, 100), 20)).isEqualTo(20);
	}

	@Test
	void securityUtilsResolvesUsernameOrFallback() throws Exception {
		Assertions.assertThat(SecurityUtils.usernameOr(null, "system")).isEqualTo("system");
		Assertions.assertThat(SecurityUtils.usernameOr(null, null)).isNull();
		Assertions.assertThat(SecurityUtils.usernameOr(new TestingAuthenticationToken("alice", "pw"), "system"))
				.isEqualTo("alice");

		var constructor = SecurityUtils.class.getDeclaredConstructor();

		constructor.setAccessible(true);

		Assertions.assertThatThrownBy(constructor::newInstance).hasCauseInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void pathUtilsShouldNormalizePaths() {
		Path path = Path.of("folder", "..", "media", "file.jpg");

		Assertions.assertThat(PathUtils.normalize(path)).endsWith(Path.of("media", "file.jpg").toString());
		Assertions.assertThat(PathUtils.normalizePath(path.toString())).isAbsolute();
	}

	@Test
	void fileValidationShouldAcceptRegularFile() throws Exception {
		Path file = Files.writeString(tempDir.resolve("photo.jpg"), "content");

		FileValidationUtils.validateFile(file);
	}

	@Test
	void fileValidationShouldRejectInvalidPaths() {
		assertThatIllegalArgumentException().isThrownBy(() -> FileValidationUtils.validateFile(null))
				.withMessage("File path must not be null.");

		assertThatIllegalArgumentException()
				.isThrownBy(() -> FileValidationUtils.validateFile(tempDir.resolve("missing.jpg")))
				.withMessageContaining("File does not exist");

		assertThatIllegalArgumentException().isThrownBy(() -> FileValidationUtils.validateFile(tempDir))
				.withMessageContaining("Path is not a regular file");
	}
}