package br.com.jorgemelo.nimbusfilemanager.diagnostics.application;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.PageImpl;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.model.AppSetting;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;

/**
 * What the support archive carries, and - just as important - what it must not.
 */
class DiagnosticsBundleServiceTest {

	private static final Clock CLOCK = Clock.fixed(LocalDateTime.parse("2026-08-01T06:00:00").toInstant(ZoneOffset.UTC),
			ZoneOffset.UTC);

	private final InstallationSummaryService summaryService = mock(InstallationSummaryService.class);
	private final AppSettingService appSettingService = mock(AppSettingService.class);
	private final ExecutionQueryService executionQueryService = mock(ExecutionQueryService.class);
	private final WorkspaceManager workspaceManager = mock(WorkspaceManager.class);

	private DiagnosticsBundleService service() {
		when(summaryService.summary()).thenReturn("Application version: 5.0.0.1\n");

		return new DiagnosticsBundleService(summaryService, appSettingService, executionQueryService, workspaceManager,
				CLOCK);
	}

	private AppSetting setting(String key, String value) {
		return AppSetting.builder().settingKey(key).settingValue(value).valueType("STRING").createdByUsername("system")
				.build();
	}

	private Map<String, String> entries(DiagnosticsBundleService service) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		service.export().body().writeTo(bytes);

		Map<String, String> files = new HashMap<>();

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			ZipEntry entry;

			while ((entry = zip.getNextEntry()) != null) {
				files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
			}
		}

		return files;
	}

	private void noExecutions() {
		when(executionQueryService.page(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
	}

	@Test
	void carriesTheSummaryTheSettingsTheExecutionsAndTheLog(@TempDir Path workspace) throws IOException {
		Path logs = Files.createDirectories(workspace.resolve(WorkspaceFolders.LOGS));

		Files.writeString(logs.resolve("nimbus-file-manager.log"), "started the inventory\n");

		when(workspaceManager.resolve(WorkspaceFolders.LOGS, "nimbus-file-manager.log"))
				.thenReturn(logs.resolve("nimbus-file-manager.log"));
		when(appSettingService.list()).thenReturn(List.of(setting("nimbus-file-manager.timezone", "UTC")));
		when(executionQueryService.page(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(execution())));

		Map<String, String> files = entries(service());

		Assertions.assertThat(files).containsOnlyKeys("summary.txt", "settings.csv", "executions.csv",
				"application.log");
		Assertions.assertThat(files.get("summary.txt")).contains("5.0.0.1");
		Assertions.assertThat(files.get("settings.csv")).contains("nimbus-file-manager.timezone;UTC");
		Assertions.assertThat(files.get("executions.csv")).contains("INVENTORY").contains("FINISHED");
		Assertions.assertThat(files.get("application.log")).contains("started the inventory");
	}

	private ExecutionResponse execution() {
		return new ExecutionResponse(UUID.randomUUID(), "INVENTORY", "FINISHED", LocalDateTime.now(CLOCK),
				LocalDateTime.now(CLOCK), "D:/library", null, 10, 10, 0, 0, 0, 0, 10, 100D, "done", false);
	}

	/**
	 * The whole point of masking by key: a value that names itself a secret never
	 * travels, whatever it holds. The archive is meant to be forwarded, and the
	 * operator has no way to inspect a zip before sending it.
	 */
	@Test
	void masksAnythingThatNamesItselfASecret(@TempDir Path workspace) throws IOException {
		when(workspaceManager.resolve(WorkspaceFolders.LOGS, "nimbus-file-manager.log"))
				.thenReturn(workspace.resolve("absent.log"));
		when(appSettingService.list())
				.thenReturn(List.of(setting("nimbus-file-manager.email.gmail.password", "hunter2"),
						setting("nimbus-file-manager.integration.api-token", "abc123"),
						setting("nimbus-file-manager.timezone", "America/Sao_Paulo")));

		noExecutions();

		String settings = entries(service()).get("settings.csv");

		Assertions.assertThat(settings).doesNotContain("hunter2").doesNotContain("abc123")
				.contains("nimbus-file-manager.email.gmail.password;***")
				.contains("nimbus-file-manager.integration.api-token;***").contains("America/Sao_Paulo");
	}

	/**
	 * A missing log is the state of a fresh installation, and an unreadable one the
	 * state of a broken one. Neither may cost the whole archive, where the rest of
	 * the evidence lives - the reason takes the log place instead.
	 */
	@Test
	void survivesAnInstallationWithNoLogYet(@TempDir Path workspace) throws IOException {
		when(workspaceManager.resolve(WorkspaceFolders.LOGS, "nimbus-file-manager.log"))
				.thenReturn(workspace.resolve("nothing-here.log"));
		when(appSettingService.list()).thenReturn(List.of());

		noExecutions();

		Map<String, String> files = entries(service());

		Assertions.assertThat(files).containsKey("application.log");
		Assertions.assertThat(files.get("application.log")).contains("Could not read").contains("NoSuchFileException");
	}

	/** Only the tail: a log of weeks is not something anyone will send. */
	@Test
	void carriesOnlyTheEndOfALongLog(@TempDir Path workspace) throws IOException {
		Path log = workspace.resolve("big.log");

		Files.writeString(log, "x".repeat(600 * 1024) + "the last line\n");

		when(workspaceManager.resolve(WorkspaceFolders.LOGS, "nimbus-file-manager.log")).thenReturn(log);
		when(appSettingService.list()).thenReturn(List.of());

		noExecutions();

		String tail = entries(service()).get("application.log");

		Assertions.assertThat(tail).endsWith("the last line\n");
		Assertions.assertThat(tail.length()).isLessThan(600 * 1024);
	}

	/** The name carries the moment, so two exports never overwrite each other. */
	@Test
	void namesTheArchiveAfterTheMomentItWasTaken() {
		Assertions.assertThat(service().export().fileName()).isEqualTo("nimbus-diagnostics-20260801-060000.zip");
	}

	/**
	 * Half the rows of a real installation carry nulls: a setting never edited, an
	 * execution still running, one that ended without a message. None of them may
	 * turn the archive into a stack trace.
	 */
	@Test
	void writesRowsThatCarryNoValueWithoutFailing(@TempDir Path workspace) throws IOException {
		when(workspaceManager.resolve(WorkspaceFolders.LOGS, "nimbus-file-manager.log"))
				.thenReturn(workspace.resolve("absent.log"));

		AppSetting edited = setting("nimbus-file-manager.timezone", null);

		edited.setUpdatedAt(LocalDateTime.now(CLOCK));

		when(appSettingService.list()).thenReturn(List.of(edited));
		when(executionQueryService.page(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(running())));

		Map<String, String> files = entries(service());

		Assertions.assertThat(files.get("settings.csv")).contains("nimbus-file-manager.timezone;;2026-08-01");
		Assertions.assertThat(files.get("executions.csv")).contains("INVENTORY;RUNNING");
	}

	/** An execution still in flight: no end, no message. */
	private ExecutionResponse running() {
		return new ExecutionResponse(UUID.randomUUID(), "INVENTORY", "RUNNING", LocalDateTime.now(CLOCK), null,
				"D:/library", null, 3, 1, 0, 0, 0, 0, 3, 33D, null, false);
	}
}