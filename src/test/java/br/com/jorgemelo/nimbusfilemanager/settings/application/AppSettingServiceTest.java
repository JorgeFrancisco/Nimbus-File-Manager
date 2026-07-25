package br.com.jorgemelo.nimbusfilemanager.settings.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.model.AppSetting;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.repository.AppSettingRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Duplicates;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Exiftool;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Ffprobe;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Mediainfo;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Metadata;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Security;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;

class AppSettingServiceTest {

	@Test
	void runShouldSeedMissingSettingsFromProperties() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.run(null);

		ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);

		verify(repository, Mockito.atLeast(1)).save(captor.capture());

		Assertions.assertThat(captor.getAllValues()).anySatisfy(setting -> {
			Assertions.assertThat(setting.getSettingKey()).isEqualTo(SettingsConstants.TOOL_FFPROBE);
			Assertions.assertThat(setting.getSettingValue()).isEqualTo("C:/tools/ffprobe.exe");
			Assertions.assertThat(setting.getCreatedByUsername()).isEqualTo("system");
		}).anySatisfy(setting -> {
			Assertions.assertThat(setting.getSettingKey()).isEqualTo(SettingsConstants.API_MAX_FOLDER_LIMIT);
			Assertions.assertThat(setting.getSettingValue()).isEqualTo("100");
		}).anySatisfy(setting -> {
			Assertions.assertThat(setting.getSettingKey()).isEqualTo(SettingsConstants.IDLE_TIMEOUT_MINUTES);
			Assertions.assertThat(setting.getSettingValue()).isEqualTo("5");
		}).anySatisfy(setting -> {
			Assertions.assertThat(setting.getSettingKey()).isEqualTo(SettingsConstants.WATCH_FOLDER);
			Assertions.assertThat(setting.getSettingValue()).isEmpty();
		}).anySatisfy(setting -> {
			Assertions.assertThat(setting.getSettingKey()).isEqualTo(SettingsConstants.WATCH_RECURSIVE);
			Assertions.assertThat(setting.getSettingValue()).isEqualTo("true");
		});
	}

	@Test
	void updateShouldValidateTypeAndAuditUser() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSetting setting = AppSetting.builder().settingKey(SettingsConstants.INVENTORY_PROGRESS_INTERVAL)
				.settingValue("100").valueType("INTEGER").createdByUsername("system").build();

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.INVENTORY_PROGRESS_INTERVAL))
				.thenReturn(Optional.of(setting));
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.update(SettingsConstants.INVENTORY_PROGRESS_INTERVAL, "250", "admin");

		Assertions.assertThat(setting.getSettingValue()).isEqualTo("250");
		Assertions.assertThat(setting.getUpdatedByUsername()).isEqualTo("admin");

		verify(repository).save(setting);
	}

	@Test
	void updateShouldRejectInvalidInteger() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties());

		Assertions.assertThatThrownBy(() -> service.update(SettingsConstants.API_MAX_PAGE_SIZE, "invalid", "admin"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Value must be an integer.");
	}

	/**
	 * Boolean settings come from a form, so only the two spellings the type admits
	 * are accepted; anything else is a rejection rather than a silent false.
	 */
	@Test
	void updateShouldAcceptOnlyRealBooleanSpellings() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.WATCH_RECURSIVE)).thenReturn(Optional.empty());
		when(repository.save(any(AppSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Assertions.assertThat(service.update(SettingsConstants.WATCH_RECURSIVE, " TRUE ", "admin").getSettingValue())
				.isEqualTo("true");
		Assertions.assertThat(service.update(SettingsConstants.WATCH_RECURSIVE, "False", "admin").getSettingValue())
				.isEqualTo("false");

		Assertions.assertThatThrownBy(() -> service.update(SettingsConstants.WATCH_RECURSIVE, "1", "admin"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Value must be true or false.");
		Assertions.assertThatThrownBy(() -> service.update(SettingsConstants.WATCH_RECURSIVE, null, "admin"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Value must be true or false.");
	}

	@Test
	void updateShouldRejectAKeyThatIsNotAKnownSetting() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties());

		Assertions.assertThatThrownBy(() -> service.update("nimbus-file-manager.made.up", "x", "admin"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown setting");
	}

	/**
	 * The audit column must never be blank: an unauthenticated or anonymous change
	 * is recorded as "system" rather than as an empty author.
	 */
	@Test
	void updateShouldRecordAnAbsentUsernameAsSystem() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.WATCH_RECURSIVE)).thenReturn(Optional.empty());
		when(repository.save(any(AppSetting.class))).thenAnswer(invocation -> invocation.getArgument(0));

		Assertions.assertThat(service.update(SettingsConstants.WATCH_RECURSIVE, "true", "   ").getCreatedByUsername())
				.isEqualTo("system");
		Assertions.assertThat(service.update(SettingsConstants.WATCH_RECURSIVE, "true", null).getCreatedByUsername())
				.isEqualTo("system");
	}

	@Test
	void valueMethodsShouldFallbackWhenSettingIsMissingOrInvalid() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSetting invalidInteger = AppSetting.builder().settingValue("nope").build();
		AppSetting booleanSetting = AppSetting.builder().settingValue("true").build();
		AppSetting stringSetting = AppSetting.builder().settingValue("custom").build();

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey("missing")).thenReturn(Optional.empty());
		when(repository.findBySettingKey("invalid")).thenReturn(Optional.of(invalidInteger));
		when(repository.findBySettingKey("boolean")).thenReturn(Optional.of(booleanSetting));
		when(repository.findBySettingKey("string")).thenReturn(Optional.of(stringSetting));

		Assertions.assertThat(service.intValue("missing", 10)).isEqualTo(10);
		Assertions.assertThat(service.intValue("invalid", 20)).isEqualTo(20);
		Assertions.assertThat(service.booleanValue("boolean", false)).isTrue();
		Assertions.assertThat(service.stringValue("string", "fallback")).isEqualTo("custom");
	}

	@Test
	void readsAreCachedUntilTheSettingIsUpdated() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSetting setting = AppSetting.builder().settingKey(SettingsConstants.DEFAULT_LAYOUT).settingValue("old")
				.valueType("STRING").createdByUsername("system").build();

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.DEFAULT_LAYOUT)).thenReturn(Optional.of(setting));
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		// Second read is served from the cache: the DB is hit only once.
		Assertions.assertThat(service.stringValue(SettingsConstants.DEFAULT_LAYOUT, "fb")).isEqualTo("old");
		Assertions.assertThat(service.stringValue(SettingsConstants.DEFAULT_LAYOUT, "fb")).isEqualTo("old");

		verify(repository, times(1)).findBySettingKey(SettingsConstants.DEFAULT_LAYOUT);

		// update() evicts, so the next read reflects the new committed value.
		service.update(SettingsConstants.DEFAULT_LAYOUT, "new", "admin");

		Assertions.assertThat(service.stringValue(SettingsConstants.DEFAULT_LAYOUT, "fb")).isEqualTo("new");
	}

	@Test
	void runShouldSeedWatchRecursiveFromTheConfiguredDefault() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties(false));

		when(repository.findBySettingKey(any())).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.run(null);

		ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);

		verify(repository, Mockito.atLeast(1)).save(captor.capture());

		Assertions.assertThat(captor.getAllValues()).anySatisfy(setting -> {
			Assertions.assertThat(setting.getSettingKey()).isEqualTo(SettingsConstants.WATCH_RECURSIVE);
			Assertions.assertThat(setting.getSettingValue()).isEqualTo("false");
		});
	}

	@Test
	void zoneIdShouldReturnTheConfiguredZone() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSetting setting = AppSetting.builder().settingKey(SettingsConstants.TIMEZONE).settingValue("Europe/Zurich")
				.valueType("ZONE_ID").createdByUsername("system").build();

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.TIMEZONE)).thenReturn(Optional.of(setting));

		Assertions.assertThat(service.zoneId()).isEqualTo(ZoneId.of("Europe/Zurich"));
	}

	@Test
	void zoneIdShouldFallBackToSaoPauloWhenMissing() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.TIMEZONE)).thenReturn(Optional.empty());

		Assertions.assertThat(service.zoneId()).isEqualTo(ZoneId.of(SettingsConstants.DEFAULT_TIMEZONE));
	}

	@Test
	void zoneIdShouldFallBackToSaoPauloWhenStoredValueIsInvalid() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSetting invalid = AppSetting.builder().settingValue("Not/AZone").build();

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.TIMEZONE)).thenReturn(Optional.of(invalid));

		Assertions.assertThat(service.zoneId()).isEqualTo(ZoneId.of(SettingsConstants.DEFAULT_TIMEZONE));
	}

	@Test
	void updateShouldAcceptAValidTimeZone() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSetting setting = AppSetting.builder().settingKey(SettingsConstants.TIMEZONE)
				.settingValue("America/Sao_Paulo").valueType("ZONE_ID").createdByUsername("system").build();

		AppSettingService service = new AppSettingService(repository, properties());

		when(repository.findBySettingKey(SettingsConstants.TIMEZONE)).thenReturn(Optional.of(setting));
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.update(SettingsConstants.TIMEZONE, "Europe/Zurich", "admin");

		Assertions.assertThat(setting.getSettingValue()).isEqualTo("Europe/Zurich");
	}

	@Test
	void updateShouldRejectAnInvalidTimeZone() {
		AppSettingRepository repository = mock(AppSettingRepository.class);

		AppSettingService service = new AppSettingService(repository, properties());

		Assertions.assertThatThrownBy(() -> service.update(SettingsConstants.TIMEZONE, "Mars/Olympus", "admin"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Value must be a valid IANA time zone id.");
	}

	private NimbusFileManagerProperties properties() {
		return properties(true);
	}

	private NimbusFileManagerProperties properties(boolean recursiveWatchDefault) {
		return new NimbusFileManagerProperties("C:/workspace", List.of("database", "temp"), "DEFAULT",
				new Tools("C:/tools/ffprobe.exe", "C:/tools/ffmpeg.exe", "C:/tools/exiftool.exe"),
				new Inventory(100, recursiveWatchDefault, 60_000L), new Api(500, 20, 100),
				new Metadata(new Exiftool(true), new Mediainfo(true), new Ffprobe(true)),
				new Duplicates("OLDEST_MODIFIED"), new Security(5, 5, 15, true, "admin", "admin"), null);
	}
}