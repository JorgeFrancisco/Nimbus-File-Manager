package br.com.jorgemelo.nimbusfilemanager.preferences.application;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.preferences.domain.model.UserPagePreference;
import br.com.jorgemelo.nimbusfilemanager.preferences.domain.repository.UserPagePreferenceRepository;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;

@Service
public class UserPagePreferenceService {

	private final UserPagePreferenceRepository userPagePreferenceRepository;
	private final AppUserRepository appUserRepository;

	public UserPagePreferenceService(UserPagePreferenceRepository userPagePreferenceRepository,
			AppUserRepository appUserRepository) {
		this.userPagePreferenceRepository = userPagePreferenceRepository;
		this.appUserRepository = appUserRepository;
	}

	public Map<String, String> find(String username, String pageKey) {
		return resolveUserId(username)
				.map(userId -> userPagePreferenceRepository.findByUserIdAndPageKey(userId, pageKey).stream().collect(
						Collectors.toMap(UserPagePreference::getPreferenceKey, UserPagePreference::getPreferenceValue)))
				.orElseGet(Map::of);
	}

	public void save(String username, String pageKey, String preferenceKey, String preferenceValue) {
		if (preferenceValue == null || preferenceValue.isBlank()) {
			return;
		}

		// Preferences are live data owned by a real user. Without a
		// resolvable user there is nothing to own the row, so we no-op instead of
		// persisting an orphan (e.g. the defensive anonymous path).
		Optional<Long> userId = resolveUserId(username);

		if (userId.isEmpty()) {
			return;
		}

		UserPagePreference preference = userPagePreferenceRepository
				.findByUserIdAndPageKeyAndPreferenceKey(userId.get(), pageKey, preferenceKey)
				.orElseGet(() -> UserPagePreference.builder().userId(userId.get()).pageKey(pageKey)
						.preferenceKey(preferenceKey).build());

		preference.setPreferenceValue(preferenceValue.trim());

		userPagePreferenceRepository.save(preference);
	}

	private Optional<Long> resolveUserId(String username) {
		if (username == null || username.isBlank()) {
			return Optional.empty();
		}

		return appUserRepository.findByUsernameIgnoreCase(username.trim()).map(AppUser::getId);
	}
}