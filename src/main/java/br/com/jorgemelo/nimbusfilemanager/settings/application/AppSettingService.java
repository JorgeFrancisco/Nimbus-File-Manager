package br.com.jorgemelo.nimbusfilemanager.settings.application;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.jorgemelo.nimbusfilemanager.settings.application.constants.SettingsConstants;
import br.com.jorgemelo.nimbusfilemanager.settings.application.dto.AppSettingDefinition;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.model.AppSetting;
import br.com.jorgemelo.nimbusfilemanager.settings.domain.repository.AppSettingRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Api;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Inventory;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Security;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.Tools;

@Service
public class AppSettingService implements ApplicationRunner {

	private static final String VALUE_TYPE_STRING = "STRING";
	private static final String VALUE_TYPE_INTEGER = "INTEGER";
	private static final String VALUE_TYPE_BOOLEAN = "BOOLEAN";
	private static final String VALUE_TYPE_ZONE_ID = "ZONE_ID";
	private static final String VALUE_TRUE = "true";
	private static final String VALUE_FALSE = "false";

	private final AppSettingRepository appSettingRepository;
	private final List<AppSettingDefinition> definitions;
	private final Map<String, AppSettingDefinition> definitionsByKey;

	/**
	 * In-memory cache of the raw stored value per key ({@code Optional.empty()} =
	 * not in the DB). Settings change rarely but are read in hot loops (the ffmpeg
	 * path per image, the scan-exclusion lists per file during a reconcile walk); a
	 * CPU profile showed those reads hammering the DB. Populated lazily on read,
	 * evicted on {@link #update}.
	 */
	private final Map<String, Optional<String>> valueCache = new ConcurrentHashMap<>();

	public AppSettingService(AppSettingRepository appSettingRepository, NimbusFileManagerProperties properties) {
		this.appSettingRepository = appSettingRepository;

		this.definitions = definitions(properties);

		this.definitionsByKey = definitions.stream()
				.collect(Collectors.toUnmodifiableMap(AppSettingDefinition::key, Function.identity()));
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		seedDefaults();
	}

	@Transactional(readOnly = true)
	public List<AppSetting> list() {
		return appSettingRepository.findAllByOrderBySettingKeyAsc();
	}

	@Transactional
	public AppSetting update(String key, String value, String username) {
		AppSettingDefinition definition = definition(key);

		String normalizedValue = normalize(value, definition.valueType());

		AppSetting setting = appSettingRepository.findBySettingKey(key)
				.orElseGet(() -> newSetting(definition, username));

		setting.setSettingValue(normalizedValue);

		setting.setUpdatedByUsername(normalizeUsername(username));

		AppSetting saved = appSettingRepository.save(setting);

		// Evict so the next read reloads the committed value instead of a stale cache
		// entry.
		valueCache.remove(key);

		return saved;
	}

	public String stringValue(String key, String fallback) {
		return cachedValue(key).filter(value -> !value.isBlank()).orElse(fallback);
	}

	public int intValue(String key, int fallback) {
		return cachedValue(key).map(value -> parseInt(value, fallback)).orElse(fallback);
	}

	public boolean booleanValue(String key, boolean fallback) {
		return cachedValue(key).map(value -> Boolean.parseBoolean(value.trim())).orElse(fallback);
	}

	/**
	 * The configured application time zone, or {@code America/Sao_Paulo} when unset
	 * or somehow invalid. Fully defensive on purpose: it is read from every
	 * {@code now(clock)} call (including entity {@code @PrePersist} during the very
	 * first startup, before the setting is seeded), so it must never fail.
	 */
	public ZoneId zoneId() {
		try {
			return ZoneId.of(stringValue(SettingsConstants.TIMEZONE, SettingsConstants.DEFAULT_TIMEZONE));
		} catch (RuntimeException _) {
			return ZoneId.of(SettingsConstants.DEFAULT_TIMEZONE);
		}
	}

	/**
	 * Cached raw stored value for {@code key}; hits the DB only on a cache miss.
	 */
	private Optional<String> cachedValue(String key) {
		return valueCache.computeIfAbsent(key,
				missing -> appSettingRepository.findBySettingKey(missing).map(AppSetting::getSettingValue));
	}

	List<AppSettingDefinition> definitions() {
		return definitions;
	}

	private void seedDefaults() {
		for (AppSettingDefinition definition : definitions) {
			if (appSettingRepository.findBySettingKey(definition.key()).isEmpty()) {
				appSettingRepository.save(newSetting(definition, "system"));
			}
		}

		discardUndefined();
	}

	/**
	 * Drops rows whose key no longer exists among the definitions. A setting
	 * dropped from the code stayed in the table forever, and the settings screen
	 * still listed it - with no message bundle entry behind it, the label rendered
	 * as the raw {@code ??setting.<key>_pt_BR??} marker, and editing it changed
	 * nothing anywhere.
	 */
	private void discardUndefined() {
		List<AppSetting> undefined = appSettingRepository.findAllByOrderBySettingKeyAsc().stream()
				.filter(setting -> !definitionsByKey.containsKey(setting.getSettingKey())).toList();

		if (undefined.isEmpty()) {
			return;
		}

		appSettingRepository.deleteAll(undefined);

		undefined.forEach(setting -> valueCache.remove(setting.getSettingKey()));
	}

	private AppSetting newSetting(AppSettingDefinition definition, String username) {
		return AppSetting.builder().settingKey(definition.key()).settingValue(definition.defaultValue())
				.valueType(definition.valueType()).description(definition.description()).editable(true)
				.createdByUsername(normalizeUsername(username)).build();
	}

	private AppSettingDefinition definition(String key) {
		AppSettingDefinition definition = definitionsByKey.get(key);

		if (definition == null) {
			throw new IllegalArgumentException("Unknown setting: " + key);
		}

		return definition;
	}

	private String normalize(String value, String valueType) {
		String normalizedValue = value == null ? "" : value.trim();

		return switch (valueType) {
		case VALUE_TYPE_INTEGER -> Integer.toString(parseRequiredInt(normalizedValue));
		case VALUE_TYPE_BOOLEAN -> Boolean.toString(parseRequiredBoolean(normalizedValue));
		case VALUE_TYPE_ZONE_ID -> parseRequiredZoneId(normalizedValue);
		default -> normalizedValue;
		};
	}

	private String parseRequiredZoneId(String value) {
		try {
			return ZoneId.of(value).getId();
		} catch (RuntimeException _) {
			throw new IllegalArgumentException("Value must be a valid IANA time zone id.");
		}
	}

	private int parseRequiredInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (Exception _) {
			throw new IllegalArgumentException("Value must be an integer.");
		}
	}

	private boolean parseRequiredBoolean(String value) {
		if (VALUE_TRUE.equalsIgnoreCase(value) || VALUE_FALSE.equalsIgnoreCase(value)) {
			return Boolean.parseBoolean(value);
		}

		throw new IllegalArgumentException("Value must be true or false.");
	}

	private int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (Exception _) {
			return fallback;
		}
	}

	private String normalizeUsername(String username) {
		return username == null || username.isBlank() ? "system" : username;
	}

	private List<AppSettingDefinition> definitions(NimbusFileManagerProperties properties) {
		return List.of(
				new AppSettingDefinition(SettingsConstants.TIMEZONE, SettingsConstants.DEFAULT_TIMEZONE,
						VALUE_TYPE_ZONE_ID,
						"Fuso horário da aplicação, usado para carimbar as datas/horas registradas."),
				new AppSettingDefinition(SettingsConstants.TOOL_FFPROBE, value(properties.tools(), Tools::ffprobe),
						VALUE_TYPE_STRING,
						"Caminho do executável ffprobe."),
				new AppSettingDefinition(SettingsConstants.TOOL_FFMPEG, value(properties.tools(), Tools::ffmpeg),
						VALUE_TYPE_STRING,
						"Caminho do executável ffmpeg."),
				new AppSettingDefinition(SettingsConstants.API_MAX_PAGE_SIZE, intDefault(properties.api(),
						Api::maxPageSize),
						VALUE_TYPE_INTEGER, "Tamanho máximo de página nas consultas, em registros."),
				new AppSettingDefinition(SettingsConstants.API_DEFAULT_FOLDER_LIMIT,
						intDefault(properties.api(), Api::defaultFolderLimit), VALUE_TYPE_INTEGER,
						"Limite padrão de pastas nas estatísticas, em pastas."),
				new AppSettingDefinition(SettingsConstants.API_MAX_FOLDER_LIMIT, intDefault(properties.api(),
						Api::maxFolderLimit),
						VALUE_TYPE_INTEGER, "Limite máximo de pastas nas estatísticas, em pastas."),
				new AppSettingDefinition(SettingsConstants.TRASH_FOLDER, "", VALUE_TYPE_STRING,
						"Pasta de quarentena para onde os duplicados excluídos são movidos. Vazio = exclusão desabilitada até configurar."),
				new AppSettingDefinition(SettingsConstants.TRASH_RETENTION_DAYS, "90", VALUE_TYPE_INTEGER,
						"Dias que os arquivos ficam na quarentena antes do expurgo automático."),
				new AppSettingDefinition(SettingsConstants.CATALOG_MISSING_RETENTION_DAYS, "90", VALUE_TYPE_INTEGER,
						"Dias que um arquivo ausente do disco (MISSING) permanece no catálogo antes da remoção automática. Vazio ou não positivo desabilita a limpeza."),
				new AppSettingDefinition(SettingsConstants.SCAN_EXCLUDED_EXTENSIONS,
						String.join(",", ScanExclusionService.DEFAULT_EXCLUDED_EXTENSIONS), VALUE_TYPE_STRING,
						"Extensões ignoradas em inventário, reconcile, arquivos e organização. Separe por vírgula, ponto e vírgula ou quebra de linha."),
				new AppSettingDefinition(SettingsConstants.SCAN_EXCLUDED_FOLDERS,
						String.join(",", ScanExclusionService.DEFAULT_EXCLUDED_FOLDERS), VALUE_TYPE_STRING,
						"Pastas ignoradas em inventário, reconcile, arquivos e organização. Use nome exato ou coringa com * e ?. Separe por vírgula, ponto e vírgula ou quebra de linha."),
				new AppSettingDefinition(SettingsConstants.IDLE_TIMEOUT_MINUTES,
						intDefault(properties.security(), Security::idleTimeoutMinutes), VALUE_TYPE_INTEGER,
						"Minutos de inatividade antes de encerrar a sessão automaticamente."),
				new AppSettingDefinition(SettingsConstants.MAX_FAILED_LOGIN_ATTEMPTS,
						intDefault(properties.security(), Security::maxFailedLoginAttempts), VALUE_TYPE_INTEGER,
						"Tentativas inválidas de senha/2FA permitidas antes de bloquear a conta temporariamente."),
				new AppSettingDefinition(SettingsConstants.LOCKOUT_DURATION_MINUTES,
						intDefault(properties.security(), Security::lockoutDurationMinutes), VALUE_TYPE_INTEGER,
						"Minutos que a conta fica bloqueada após exceder as tentativas inválidas."),
				new AppSettingDefinition(SettingsConstants.WATCH_FOLDER, "", VALUE_TYPE_STRING,
						"Pasta monitorada continuamente pelo inventário automático. Vazio = ainda não configurado (onboarding)."),
				new AppSettingDefinition(SettingsConstants.WATCH_RECURSIVE,
						booleanDefault(properties.inventory(), Inventory::recursiveWatchDefault), VALUE_TYPE_BOOLEAN,
						"Inventário automático: incluir subpastas."),
				new AppSettingDefinition(SettingsConstants.WATCH_INCLUDE_HIDDEN, VALUE_FALSE, VALUE_TYPE_BOOLEAN,
						"Inventário automático: incluir arquivos/pastas ocultos."),
				new AppSettingDefinition(SettingsConstants.WATCH_CALCULATE_HASHES, VALUE_TRUE, VALUE_TYPE_BOOLEAN,
						"Inventário automático: calcular hashes dos arquivos."),
				new AppSettingDefinition(SettingsConstants.WATCH_FORCE_ANALYSIS, VALUE_FALSE, VALUE_TYPE_BOOLEAN,
						"Inventário automático: forçar reanálise de arquivos já catalogados."),
				new AppSettingDefinition(SettingsConstants.LOCATION_ENABLED, VALUE_TRUE, VALUE_TYPE_BOOLEAN,
						"Localização offline: resolver país/estado/cidade a partir do GPS durante o inventário."),
				new AppSettingDefinition(SettingsConstants.LOCATION_PROVIDER, "ADMIN_BOUNDARIES", VALUE_TYPE_STRING,
						"Localização offline: provedor de resolução, entre os que a aplicação implementa."),
				new AppSettingDefinition(SettingsConstants.BOUNDARY_ADM0_URL,
						"https://media.githubusercontent.com/media/wmgeolab/geoBoundaries/main/releaseData/CGAZ/geoBoundariesCGAZ_ADM0.geojson",
						VALUE_TYPE_STRING,
						"Base geográfica: URL do GeoJSON global de países (ADM0). Vazio desativa o nível."),
				new AppSettingDefinition(SettingsConstants.BOUNDARY_ADM1_URL,
						"https://media.githubusercontent.com/media/wmgeolab/geoBoundaries/main/releaseData/CGAZ/geoBoundariesCGAZ_ADM1.geojson",
						VALUE_TYPE_STRING,
						"Base geográfica: URL do GeoJSON global de estados (ADM1). Vazio desativa o nível."),
				new AppSettingDefinition(SettingsConstants.BOUNDARY_ADM2_URL,
						"https://media.githubusercontent.com/media/wmgeolab/geoBoundaries/main/releaseData/CGAZ/geoBoundariesCGAZ_ADM2.geojson",
						VALUE_TYPE_STRING,
						"Base geográfica: URL do GeoJSON global de municípios (ADM2). Vazio desativa o nível."),
				new AppSettingDefinition(SettingsConstants.BOUNDARY_GBOPEN_API_URL,
						"https://www.geoboundaries.org/api/current/gbOpen/",
						VALUE_TYPE_STRING,
						"Base geográfica: URL da API usada para completar territórios sem polígono próprio (ex.: Aruba). Vazio desativa a consulta."),
				new AppSettingDefinition(SettingsConstants.BOUNDARY_AUTO_TERRITORIES, VALUE_TRUE, VALUE_TYPE_BOOLEAN,
						"Base geográfica: após importar, buscar automaticamente o polígono de cada país ISO ausente (territórios dissolvidos no soberano)."),
				new AppSettingDefinition(SettingsConstants.MAP_ENABLED, VALUE_TRUE, VALUE_TYPE_BOOLEAN,
						"Mapa: habilita a tela de mapa das mídias georreferenciadas."),
				new AppSettingDefinition(SettingsConstants.MAP_TILE_URL,
						"https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
						VALUE_TYPE_STRING,
						"Mapa: URL do tile server (padrão OpenStreetMap). Pode apontar para outro provedor ou servidor próprio."),
				new AppSettingDefinition(SettingsConstants.MAP_TILE_ATTRIBUTION, "© OpenStreetMap contributors",
						VALUE_TYPE_STRING,
						"Mapa: texto de atribuição exibido no rodapé do mapa."),
				new AppSettingDefinition(SettingsConstants.MAP_MAX_ZOOM, "19", VALUE_TYPE_INTEGER,
						"Mapa: nível máximo de zoom."));
	}

	/**
	 * Seed value of a text setting: an absent section and an absent property inside
	 * it mean the same thing to the settings table - no configured default - so
	 * both collapse to the empty string a screen can show and an admin can fill in.
	 */
	private <T> String value(T object, Function<T, String> getter) {
		String configured = object == null ? null : getter.apply(object);

		return configured == null ? "" : configured;
	}

	private <T> String intDefault(T object, ToIntFunction<T> getter) {
		return object == null ? "0" : Integer.toString(getter.applyAsInt(object));
	}

	private <T> String booleanDefault(T object, Predicate<T> getter) {
		return object == null ? VALUE_FALSE : Boolean.toString(getter.test(object));
	}
}