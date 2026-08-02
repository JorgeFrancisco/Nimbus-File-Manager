package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.io.File;
import java.util.function.Supplier;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * Points the saved ffmpeg and ffprobe paths at this machine whenever they stop
 * describing it.
 *
 * <p>
 * Those two rows are settings about the installation, not about the catalog,
 * and two things detach them from reality. A backup carries the whole settings
 * table, so a restore lands another machine's paths here - which is how a
 * restored installation came to fail every ffmpeg call for seventeen hours,
 * against {@code ./tools/bin/ffprobe.exe}. And this project has renamed the
 * folder its tools live in, so an installation older than that rename holds the
 * same wrong value having never restored anything.
 *
 * <p>
 * {@link ExternalToolPaths} already refuses to use a saved path whose file is
 * gone, so nothing is broken by the time this runs. What is left is the screen:
 * the tools section shows the resolved path in its status and the stored value
 * in the row below it, and after a restore those two disagreed - the status
 * reporting the working binary while the row underneath named a folder from
 * another machine. Writing the resolved path back is what makes the screen say
 * one thing.
 *
 * <p>
 * The resolved value rather than a blank: a blank would also work, since an
 * unset path falls through to discovery, but it leaves an empty field where a
 * path used to be, with nothing to say what is actually running.
 */
@Slf4j
@Component
public class ExternalToolPathRefresh {

	/**
	 * The username stamped on the rows this rewrites: nobody asked for them, and
	 * crediting whoever happened to trigger the restore would be a lie in the audit
	 * trail.
	 */
	private static final String SYSTEM = "system";

	private final AppSettingService appSettingService;
	private final ExternalToolPaths externalToolPaths;

	public ExternalToolPathRefresh(AppSettingService appSettingService, ExternalToolPaths externalToolPaths) {
		this.appSettingService = appSettingService;
		this.externalToolPaths = externalToolPaths;
	}

	/**
	 * After {@link SettingsCacheRefresh}, and the ordering matters: the values read
	 * here have to be the restored ones, not what the cache still held from before.
	 */
	@Order(Ordered.HIGHEST_PRECEDENCE + 1)
	@EventListener
	public void onCatalogRestored(CatalogRestored event) {
		realign("the backup " + event.name());
	}

	/**
	 * Also at every start, because a restore is not the only way a saved path stops
	 * describing this machine. This project renamed the folder its tools live in,
	 * and every installation older than that rename kept pointing at the previous
	 * one - a catalog that was never restored anywhere, showing a path that has not
	 * existed for versions. Waiting for a restore to correct that would mean
	 * waiting for something most installations never do.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		realign("an earlier layout of this installation");
	}

	private void realign(String origin) {
		realign(SettingsConstants.TOOL_FFMPEG, externalToolPaths::ffmpeg, origin);
		realign(SettingsConstants.TOOL_FFPROBE, externalToolPaths::ffprobe, origin);
	}

	/**
	 * Only a saved value that no longer resolves to itself is rewritten. Nothing
	 * saved stays nothing saved - that is the setting meaning "find it for me", and
	 * pinning a path onto it would answer a question the operator never asked. A
	 * path that still exists is a deliberate choice, and this has no business
	 * overruling it.
	 */
	private void realign(String settingKey, Supplier<String> resolved, String origin) {
		String saved = appSettingService.stringValue(settingKey, null);

		if (saved == null) {
			return;
		}

		String current = resolved.get();

		if (saved.equals(current) || !isInstalledFile(current)) {
			return;
		}

		appSettingService.update(settingKey, current, SYSTEM);

		log.info("{} named {}, from {}, which is not where this machine keeps it; now {}", settingKey, saved, origin,
				current);
	}

	/**
	 * Whether what resolution came up with is a binary that is actually here.
	 *
	 * <p>
	 * A different question from the one {@code ExternalToolPaths} asks of a saved
	 * value: there, a bare command name is a perfectly good answer, because PATH
	 * resolves it. Here it is not good enough to write down. At start-up this runs
	 * beside the bootstrap installer, on the same event and in no defined order, so
	 * it can easily be asked before ffmpeg has been downloaded - and pinning
	 * {@code ffmpeg} into the setting at that moment would replace a wrong path
	 * with a lookup that fails on every Windows machine without one on PATH. When
	 * there is nothing installed yet, the honest move is to leave the row alone and
	 * let a later start correct it.
	 */
	private boolean isInstalledFile(String resolved) {
		return new File(resolved).isFile();
	}
}