package br.com.jorgemelo.nimbusfilemanager.security.infrastructure.web;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserAccountService;
import br.com.jorgemelo.nimbusfilemanager.security.application.AppUserDetailsService;
import br.com.jorgemelo.nimbusfilemanager.security.application.RequestClientInfo;
import br.com.jorgemelo.nimbusfilemanager.security.application.TwoFactorLoginResult;
import br.com.jorgemelo.nimbusfilemanager.security.application.TwoFactorLoginService;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import jakarta.servlet.http.HttpServletRequest;

@Controller
public class AuthWebController extends LocalizedComponent {

	private static final String REDIRECT_LOGIN = "redirect:/login";

	private final AppUserAccountService appUserAccountService;
	private final AppUserDetailsService appUserDetailsService;
	private final TwoFactorLoginService twoFactorLoginService;
	private final GoogleLoginStatus googleLoginStatus;

	public AuthWebController(AppUserAccountService appUserAccountService, AppUserDetailsService appUserDetailsService,
			TwoFactorLoginService twoFactorLoginService, GoogleLoginStatus googleLoginStatus) {
		this.appUserAccountService = appUserAccountService;
		this.appUserDetailsService = appUserDetailsService;
		this.twoFactorLoginService = twoFactorLoginService;
		this.googleLoginStatus = googleLoginStatus;
	}

	@GetMapping("/login")
	public String login(Model model) {
		googleLoginStatus.addAttributes(model);

		return "auth/login";
	}

	@GetMapping("/change-password")
	public String changePassword(Model model) {
		googleLoginStatus.addAttributes(model);

		return "auth/change-password";
	}

	@PostMapping("/change-password")
	public String changePassword(@RequestParam String email, @RequestParam String currentPassword,
			@RequestParam String newPassword, @RequestParam String confirmPassword,
			RedirectAttributes redirectAttributes) {
		if (!newPassword.equals(confirmPassword)) {
			redirectAttributes.addFlashAttribute("passwordError", message("backend.passwordsMismatch"));

			return "redirect:/change-password";
		}

		try {
			appUserAccountService.changePassword(email, currentPassword, newPassword);

			redirectAttributes.addFlashAttribute("passwordChanged", true);

			return REDIRECT_LOGIN;
		} catch (IllegalArgumentException exception) {
			redirectAttributes.addFlashAttribute("passwordError", exception.getMessage());

			return "redirect:/change-password";
		}
	}

	@GetMapping("/login/2fa")
	public String twoFactor(HttpServletRequest request, Model model) {
		String username = pendingUsername(request);

		if (username == null) {
			return REDIRECT_LOGIN;
		}

		model.addAttribute("username", username);

		googleLoginStatus.addAttributes(model);

		return "auth/two-factor";
	}

	@PostMapping("/login/2fa")
	public String verifyTwoFactor(@RequestParam String code, HttpServletRequest request, Model model) {
		String username = pendingUsername(request);

		if (username == null) {
			return REDIRECT_LOGIN;
		}

		RequestClientInfo client = RequestClientInfo.from(request);

		TwoFactorLoginResult result = twoFactorLoginService.verify(username, code, client.ipAddress(),
				client.userAgent());

		return switch (result) {
		case LOCKED -> {
			request.getSession().removeAttribute(SecurityConstants.PENDING_USERNAME);

			yield "redirect:/login?locked";
		}
		case INVALID -> {
			model.addAttribute("username", username);
			model.addAttribute("error", true);

			yield "auth/two-factor";
		}
		case SUCCESS -> {
			establishAuthenticatedSession(request, username);

			yield "redirect:/app";
		}
		};
	}

	/**
	 * Web-security infrastructure only: builds the authenticated
	 * {@link SecurityContext} from the resolved user details and stores it in both
	 * the {@link SecurityContextHolder} and the HTTP session, then clears the
	 * pending-login marker so the 2FA step can't be replayed.
	 */
	private void establishAuthenticatedSession(HttpServletRequest request, String username) {
		UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);

		var authentication = UsernamePasswordAuthenticationToken.authenticated(userDetails, null,
				userDetails.getAuthorities());

		SecurityContext context = SecurityContextHolder.createEmptyContext();

		context.setAuthentication(authentication);

		SecurityContextHolder.setContext(context);

		request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		request.getSession().removeAttribute(SecurityConstants.PENDING_USERNAME);
	}

	private String pendingUsername(HttpServletRequest request) {
		Object value = request.getSession().getAttribute(SecurityConstants.PENDING_USERNAME);

		return value instanceof String username ? username : null;
	}
}