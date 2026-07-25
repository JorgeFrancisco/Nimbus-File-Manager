package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserAccountService;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.security.domain.enums.Role;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

@Controller
public class UsersWebController {

	private static final List<String> ROLES = List.of(Role.USER.name(), Role.ADMIN.name());
	private static final List<Integer> PAGE_SIZES = List.of(20, 50, 100);
	private static final int DEFAULT_PAGE_SIZE = 20;
	static final String SIZE_KEY = "size";

	private final AppUserAccountService appUserAccountService;
	private final UserPagePreferenceService userPagePreferenceService;

	public UsersWebController(AppUserAccountService appUserAccountService,
			UserPagePreferenceService userPagePreferenceService) {
		this.appUserAccountService = appUserAccountService;
		this.userPagePreferenceService = userPagePreferenceService;
	}

	@GetMapping("/app/users")
	public String users(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(required = false) Integer size, Authentication authentication, Model model) {
		int pageSize = resolveSize(size, authentication);
		var usersPage = appUserAccountService.searchUsers(q, page, pageSize);

		model.addAttribute(SecurityConstants.PAGE_KEY, usersPage.getContent());
		model.addAttribute("usersPage", usersPage);
		model.addAttribute("q", q == null ? "" : q.trim());
		model.addAttribute("size", usersPage.getSize());
		model.addAttribute("sizes", PAGE_SIZES);
		model.addAttribute("roles", ROLES);

		return "app/users";
	}

	/**
	 * Resolves the page size from the request or the user's saved preference
	 * (persisted per user, per the project rule that screen selections are
	 * remembered), clamped to the allowed set.
	 */
	private int resolveSize(Integer requested, Authentication authentication) {
		String username = SecurityUtils.usernameOr(authentication, "system");

		if (requested != null && PAGE_SIZES.contains(requested)) {
			userPagePreferenceService.save(username, SecurityConstants.PAGE_KEY, SIZE_KEY, requested.toString());

			return requested;
		}

		return PageUtils.validSizeOrDefault(
				userPagePreferenceService.find(username, SecurityConstants.PAGE_KEY).get(SIZE_KEY), PAGE_SIZES,
				DEFAULT_PAGE_SIZE);
	}

	@PostMapping("/app/users")
	public String create(@RequestParam String email, @RequestParam String displayName, @RequestParam String password,
			@RequestParam(defaultValue = "USER") String role, RedirectAttributes redirectAttributes) {
		try {
			appUserAccountService.createUser(email, displayName, password, role);

			redirectAttributes.addFlashAttribute("userCreated", true);
		} catch (IllegalArgumentException exception) {
			redirectAttributes.addFlashAttribute("userError", exception.getMessage());
		}

		return "redirect:/app/users";
	}
}