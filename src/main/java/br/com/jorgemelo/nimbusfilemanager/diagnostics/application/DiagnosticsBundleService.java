package br.com.jorgemelo.nimbusfilemanager.diagnostics.application;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.diagnostics.application.dto.DiagnosticsBundle;
import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionQueryService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionResponse;
import br.com.jorgemelo.nimbusfilemanager.settings.application.AppSettingService;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.model.AppSetting;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.WorkspaceFolders;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;

/**
 * Collects, in one downloadable archive, the evidence a failure report needs.
 * Today that evidence is scattered: the log lives in the workspace, the
 * executions in the database, the configuration on a screen and the tool paths
 * on disk. Finding the cause of the last real defect meant reading all four and
 * cross-checking them by hand, which is not something a user can be asked to do
 * - so a vague report stayed vague.
 *
 * <p>
 * What goes in is deliberately narrow: enough to diagnose, never more than the
 * operator would knowingly send. Setting values that look like a secret are
 * masked, and the access log - who signed in, from which address - is left out
 * entirely; it answers no diagnostic question and is the most personal data the
 * application holds.
 */
@Slf4j
@Service
public class DiagnosticsBundleService {

	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** Enough log to cover the operation that failed, small enough to e-mail. */
	private static final long LOG_TAIL_BYTES = 512L * 1024;

	private static final int EXECUTIONS = 50;

	private static final String LOG_FILE = "nimbus-file-manager.log";

	private static final List<String> SECRET_MARKERS = List.of("password", "secret", "token", "credential");

	private final InstallationSummaryService installationSummaryService;
	private final AppSettingService appSettingService;
	private final ExecutionQueryService executionQueryService;
	private final WorkspaceManager workspaceManager;
	private final Clock clock;

	public DiagnosticsBundleService(InstallationSummaryService installationSummaryService,
			AppSettingService appSettingService, ExecutionQueryService executionQueryService,
			WorkspaceManager workspaceManager, Clock clock) {
		this.installationSummaryService = installationSummaryService;
		this.appSettingService = appSettingService;
		this.executionQueryService = executionQueryService;
		this.workspaceManager = workspaceManager;
		this.clock = clock;
	}

	public DiagnosticsBundle export() {
		String fileName = "nimbus-diagnostics-" + LocalDateTime.now(clock).format(FILE_TIMESTAMP) + ".zip";

		return new DiagnosticsBundle(fileName, "application/zip", this::write);
	}

	private void write(OutputStream output) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
			put(zip, "summary.txt", installationSummaryService.summary());
			put(zip, "settings.csv", settings());
			put(zip, "executions.csv", executions());
			put(zip, "application.log", logTail());
		}
	}

	private void put(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	/**
	 * Every stored parameter, because a misconfiguration is the likeliest cause of
	 * a report - with anything that names itself a secret replaced by a marker.
	 * Masking by key, not by value, so a new sensitive setting is covered the day
	 * it is added instead of the day someone remembers this method.
	 */
	private String settings() {
		StringBuilder csv = new StringBuilder("key;value;updatedAt\n");

		for (AppSetting setting : appSettingService.list()) {
			csv.append(setting.getSettingKey()).append(';').append(masked(setting)).append(';')
					.append(setting.getUpdatedAt() == null ? "" : setting.getUpdatedAt()).append('\n');
		}

		return csv.toString();
	}

	private String masked(AppSetting setting) {
		String key = setting.getSettingKey().toLowerCase(Locale.ROOT);

		if (SECRET_MARKERS.stream().anyMatch(key::contains)) {
			return "***";
		}

		return setting.getSettingValue() == null ? "" : setting.getSettingValue().replace(';', ',');
	}

	private String executions() {
		StringBuilder csv = new StringBuilder(
				"startedAt;finishedAt;type;status;filesFound;filesMoved;errors;message\n");

		List<ExecutionResponse> recent = executionQueryService.page(0, EXECUTIONS).getContent();

		for (ExecutionResponse execution : recent) {
			csv.append(text(execution.startedAt())).append(';').append(text(execution.finishedAt())).append(';')
					.append(execution.executionType()).append(';').append(execution.status()).append(';')
					.append(execution.filesFound()).append(';').append(execution.filesMoved()).append(';')
					.append(execution.errors()).append(';').append(clean(execution.message())).append('\n');
		}

		return csv.toString();
	}

	/**
	 * The end of the current log file. The tail rather than the whole thing: what
	 * explains a failure just reported is at the end, and a rotated log of tens of
	 * megabytes is something nobody will send.
	 */
	private String logTail() {
		Path logFile = workspaceManager.resolve(WorkspaceFolders.LOGS, LOG_FILE);

		// No guard on the file existing: a first run with no log yet and a log that
		// cannot be opened are the same answer to whoever reads the archive - the
		// reason, in the file's own place. One path instead of two.
		try (SeekableByteChannel channel = Files.newByteChannel(logFile)) {
			long from = Math.max(0, channel.size() - LOG_TAIL_BYTES);

			// Seek instead of reading the file: a log that has been running for weeks
			// is tens of megabytes, and only its end explains what was just reported.
			ByteBuffer buffer = ByteBuffer.allocate((int) (channel.size() - from));

			channel.position(from).read(buffer);

			return new String(buffer.array(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			// A bundle without the log is worth more than no bundle at all.
			log.warn("Could not read the log file for the diagnostics bundle", e);

			return "Could not read " + logFile + ": " + e + "\n";
		}
	}

	private String text(LocalDateTime moment) {
		return moment == null ? "" : moment.toString();
	}

	private String clean(String message) {
		return message == null ? "" : message.replace(';', ',').replace('\n', ' ').replace('\r', ' ');
	}
}