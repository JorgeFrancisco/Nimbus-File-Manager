package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.util.function.Supplier;

import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.backup.application.dto.CatalogRestored;
import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * Points the saved ffmpeg and ffprobe paths at this machine after a restore
 * brought in another one's.
 *
 * <p>
 * A backup carries the whole settings table, and two of those rows describe the
 * installation rather than the catalog. Restored onto a machine whose tools
 * live elsewhere, they name a folder that does not exist here - which is how a
 * restored installation came to fail every ffmpeg call for seventeen hours,
 * against {@code ./tools/bin/ffprobe.exe}, a layout from the installation the
 * backup was taken on.
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
		realign(SettingsConstants.TOOL_FFMPEG, externalToolPaths::ffmpeg, event.name());
		realign(SettingsConstants.TOOL_FFPROBE, externalToolPaths::ffprobe, event.name());
	}

	/**
	 * Only a saved value that no longer resolves to itself is rewritten. Nothing
	 * saved stays nothing saved - that is the setting meaning "find it for me", and
	 * pinning a path onto it would answer a question the operator never asked. A
	 * path that still exists is a deliberate choice, and this has no business
	 * overruling it.
	 */
	private void realign(String settingKey, Supplier<String> resolved, String backup) {
		String saved = appSettingService.stringValue(settingKey, null);

		if (saved == null) {
			return;
		}

		String current = resolved.get();

		if (saved.equals(current)) {
			return;
		}

		appSettingService.update(settingKey, current, SYSTEM);

		log.info("{} came from {} naming {}, which is not where this machine keeps it; now {}", settingKey, backup,
				saved, current);
	}
}