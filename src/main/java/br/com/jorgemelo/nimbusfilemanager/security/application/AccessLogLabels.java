package br.com.jorgemelo.nimbusfilemanager.security.application;

import org.springframework.stereotype.Component;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;

/**
 * Localizes the access-log event type, status and message codes in the backend
 * so the access screen renders every audit row without translating any domain
 * code itself. The switches use literal bundle keys (event type and status) and
 * the {@link AccessMessages} code constants (whose value is the bundle key), so
 * every key stays visible to the i18n key-parity test. Unknown codes - e.g. a
 * legacy free-text message persisted before this contract - fall through to
 * their raw value instead of raising.
 */
@Component
public class AccessLogLabels extends LocalizedComponent {

	public String eventType(String eventType) {
		return switch (eventType) {
		case SecurityConstants.LOGIN_SUCCESS -> message("enum.accessEventType.LOGIN_SUCCESS");
		case SecurityConstants.LOGIN_FAILURE -> message("enum.accessEventType.LOGIN_FAILURE");
		case SecurityConstants.LOGIN_2FA_REQUIRED -> message("enum.accessEventType.LOGIN_2FA_REQUIRED");
		case SecurityConstants.LOGIN_2FA_SUCCESS -> message("enum.accessEventType.LOGIN_2FA_SUCCESS");
		case SecurityConstants.LOGIN_2FA_FAILURE -> message("enum.accessEventType.LOGIN_2FA_FAILURE");
		case SecurityConstants.ACCOUNT_LOCKED -> message("enum.accessEventType.ACCOUNT_LOCKED");
		case SecurityConstants.LOGOUT -> message("enum.accessEventType.LOGOUT");
		case SecurityConstants.LOGOUT_INACTIVITY -> message("enum.accessEventType.LOGOUT_INACTIVITY");
		default -> eventType;
		};
	}

	public String status(String status) {
		return switch (status) {
		case "SUCCESS" -> message("enum.accessStatus.SUCCESS");
		case "FAILURE" -> message("enum.accessStatus.FAILURE");
		default -> status;
		};
	}

	public String messageLabel(String messageCode) {
		if (messageCode == null || messageCode.isBlank()) {
			return null;
		}

		return switch (messageCode) {
		case AccessMessages.LOGIN_COMPLETED -> message(AccessMessages.LOGIN_COMPLETED);
		case AccessMessages.GOOGLE_LOGIN_COMPLETED -> message(AccessMessages.GOOGLE_LOGIN_COMPLETED);
		case AccessMessages.TWO_FACTOR_REQUIRED -> message(AccessMessages.TWO_FACTOR_REQUIRED);
		case AccessMessages.TWO_FACTOR_REQUIRED_GOOGLE -> message(AccessMessages.TWO_FACTOR_REQUIRED_GOOGLE);
		case AccessMessages.TWO_FACTOR_COMPLETED -> message(AccessMessages.TWO_FACTOR_COMPLETED);
		case AccessMessages.TWO_FACTOR_REJECTED_LOCKED -> message(AccessMessages.TWO_FACTOR_REJECTED_LOCKED);
		case AccessMessages.INVALID_TWO_FACTOR_CODE -> message(AccessMessages.INVALID_TWO_FACTOR_CODE);
		case AccessMessages.ACCOUNT_LOCKED -> message(AccessMessages.ACCOUNT_LOCKED);
		case AccessMessages.ACCOUNT_NOT_CONFIRMED -> message(AccessMessages.ACCOUNT_NOT_CONFIRMED);
		case AccessMessages.ACCOUNT_TEMPORARILY_LOCKED -> message(AccessMessages.ACCOUNT_TEMPORARILY_LOCKED);
		case AccessMessages.INVALID_CREDENTIALS -> message(AccessMessages.INVALID_CREDENTIALS);
		case AccessMessages.LOGOUT_COMPLETED -> message(AccessMessages.LOGOUT_COMPLETED);
		case AccessMessages.LOGOUT_BY_INACTIVITY -> message(AccessMessages.LOGOUT_BY_INACTIVITY);
		default -> messageCode;
		};
	}
}