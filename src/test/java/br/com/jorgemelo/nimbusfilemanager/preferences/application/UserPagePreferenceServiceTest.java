package br.com.jorgemelo.nimbusfilemanager.preferences.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import br.com.jorgemelo.nimbusfilemanager.preferences.domain.model.UserPagePreference;
import br.com.jorgemelo.nimbusfilemanager.preferences.domain.repository.UserPagePreferenceRepository;
import br.com.jorgemelo.nimbusfilemanager.security.domain.model.AppUser;
import br.com.jorgemelo.nimbusfilemanager.security.domain.repository.AppUserRepository;

class UserPagePreferenceServiceTest {

	private final UserPagePreferenceRepository repository = mock(UserPagePreferenceRepository.class);
	private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
	private final UserPagePreferenceService service = new UserPagePreferenceService(repository, appUserRepository);

	private void userExists(String username, long id) {
		when(appUserRepository.findByUsernameIgnoreCase(username))
				.thenReturn(Optional.of(AppUser.builder().id(id).build()));
	}

	@Test
	void findResolvesUserIdAndMapsPreferences() {
		userExists("User@Example.COM", 7L);

		UserPagePreference preference = UserPagePreference.builder().preferenceKey("size").preferenceValue("50")
				.build();

		when(repository.findByUserIdAndPageKey(7L, "files")).thenReturn(List.of(preference));

		Assertions.assertThat(service.find(" User@Example.COM ", "files")).containsEntry("size", "50");
	}

	@Test
	void findReturnsEmptyWhenUserUnknown() {
		when(appUserRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

		Assertions.assertThat(service.find("ghost", "files")).isEmpty();

		verifyNoInteractions(repository);
	}

	@Test
	void saveCreatesPreferenceForResolvedUser() {
		userExists("admin", 3L);
		when(repository.findByUserIdAndPageKeyAndPreferenceKey(3L, "files", "view")).thenReturn(Optional.empty());

		service.save("  admin  ", "files", "view", " details ");

		ArgumentCaptor<UserPagePreference> captor = ArgumentCaptor.forClass(UserPagePreference.class);

		verify(repository).save(captor.capture());

		Assertions.assertThat(captor.getValue())
				.extracting(UserPagePreference::getUserId, UserPagePreference::getPageKey,
						UserPagePreference::getPreferenceKey, UserPagePreference::getPreferenceValue)
				.containsExactly(3L, "files", "view", "details");
	}

	@Test
	void saveUpdatesExistingPreference() {
		userExists("ADMIN", 3L);

		UserPagePreference existing = UserPagePreference.builder().preferenceValue("small").build();

		when(repository.findByUserIdAndPageKeyAndPreferenceKey(3L, "files", "view")).thenReturn(Optional.of(existing));

		service.save("ADMIN", "files", "view", "large");

		verify(repository).save(existing);

		Assertions.assertThat(existing.getPreferenceValue()).isEqualTo("large");
	}

	@Test
	void saveIgnoresNullAndBlankValues() {
		service.save("user", "files", "view", null);
		service.save("user", "files", "view", "  ");

		verifyNoInteractions(repository, appUserRepository);
	}

	@Test
	void saveNoOpsWhenUserUnknown() {
		when(appUserRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

		service.save("ghost", "files", "view", "details");

		verifyNoInteractions(repository);
	}

	/**
	 * A screen reached without an authenticated principal has no user to own a
	 * preference row: nothing is read and nothing is written for it.
	 */
	@Test
	void anAnonymousRequestOwnsNoPreferences() {
		Assertions.assertThat(service.find(null, "files")).isEmpty();
		Assertions.assertThat(service.find("  ", "files")).isEmpty();

		service.save(" ", "files", "view", "details");

		verifyNoInteractions(repository, appUserRepository);
	}
	/**
	 * The operation {@code save} cannot express. Clearing a field on a screen used
	 * to store an empty value, which save ignores - so the old preference survived
	 * and the screen came back filled on the next visit.
	 */
	@Test
	void removeDeletesTheStoredPreference() {
		userExists("user", 7L);

		UserPagePreference stored = UserPagePreference.builder().userId(7L).pageKey("timeline")
				.preferenceKey("filter.geo").preferenceValue("WITH_LOCATION").build();

		when(repository.findByUserIdAndPageKeyAndPreferenceKey(7L, "timeline", "filter.geo"))
				.thenReturn(Optional.of(stored));

		service.remove("user", "timeline", "filter.geo");

		verify(repository).delete(stored);
	}

	/** Nothing stored is not a failure: clearing what was never set is a no-op. */
	@Test
	void removeIsQuietWhenThereIsNothingStored() {
		userExists("user", 7L);

		when(repository.findByUserIdAndPageKeyAndPreferenceKey(7L, "timeline", "filter.geo"))
				.thenReturn(Optional.empty());

		service.remove("user", "timeline", "filter.geo");

		verify(repository, never()).delete(any());
	}

	@Test
	void removeNoOpsWhenUserUnknown() {
		when(appUserRepository.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

		service.remove("ghost", "timeline", "filter.geo");

		verifyNoInteractions(repository);
	}

}