package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Boot-time configuration of the Windows NTFS USN Change Journal change source.
 *
 * <p>
 * {@code enabled} only <em>allows</em> the USN source; it is still chosen only
 * when the platform actually supports it (Windows + an NTFS volume whose
 * journal can be opened). On any other platform, or when disabled, the watcher
 * falls back to the portable {@code WatchService} source. Kept in code (not
 * app_setting) because the source is selected before the settings screen is
 * reachable.
 *
 * @param enabled whether the USN source may be used when the platform supports
 * it (default true).
 * @param readBufferBytes size of the buffer handed to
 * {@code FSCTL_READ_USN_JOURNAL} per read (default 64 KiB); larger drains more
 * records per call.
 */
@ConfigurationProperties(prefix = "nimbus-file-manager.inventory.usn")
public record UsnJournalProperties(@DefaultValue("true") boolean enabled, @DefaultValue("65536") int readBufferBytes) {
}