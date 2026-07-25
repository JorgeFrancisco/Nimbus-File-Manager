package br.com.jorgemelo.nimbusfilemanager.security.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.security.application.constants.AccessMessages;
import br.com.jorgemelo.nimbusfilemanager.security.application.constants.SecurityConstants;

class AccessLogLabelsTest {

	private final AccessLogLabels labels = new AccessLogLabels();

	@Test
	void eventTypeResolvesEveryKnownCodeToItsLabel() {
		Assertions.assertThat(labels.eventType(SecurityConstants.LOGIN_SUCCESS)).isEqualTo("Login");
		Assertions.assertThat(labels.eventType(SecurityConstants.LOGIN_FAILURE)).isEqualTo("Falha de login");
		Assertions.assertThat(labels.eventType(SecurityConstants.LOGIN_2FA_REQUIRED)).isEqualTo("2FA necessário");
		Assertions.assertThat(labels.eventType(SecurityConstants.LOGIN_2FA_SUCCESS)).isEqualTo("2FA bem-sucedido");
		Assertions.assertThat(labels.eventType(SecurityConstants.LOGIN_2FA_FAILURE)).isEqualTo("Falha no 2FA");
		Assertions.assertThat(labels.eventType(SecurityConstants.ACCOUNT_LOCKED)).isEqualTo("Conta bloqueada");
		Assertions.assertThat(labels.eventType(SecurityConstants.LOGOUT)).isEqualTo("Logout");
		Assertions.assertThat(labels.eventType(SecurityConstants.LOGOUT_INACTIVITY)).isEqualTo("Logout por inatividade");
	}

	@Test
	void eventTypeFallsBackToRawCodeWhenUnknown() {
		Assertions.assertThat(labels.eventType("SOMETHING_ELSE")).isEqualTo("SOMETHING_ELSE");
	}

	@Test
	void statusResolvesSuccessAndFailureAndFallsBackOtherwise() {
		Assertions.assertThat(labels.status("SUCCESS")).isEqualTo("Sucesso");
		Assertions.assertThat(labels.status("FAILURE")).isEqualTo("Falha");
		Assertions.assertThat(labels.status("PENDING")).isEqualTo("PENDING");
	}

	@Test
	void messageLabelResolvesEveryKnownCodeToItsText() {
		Assertions.assertThat(labels.messageLabel(AccessMessages.LOGIN_COMPLETED)).isEqualTo("Login concluído.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.GOOGLE_LOGIN_COMPLETED))
				.isEqualTo("Login com Google concluído.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.TWO_FACTOR_REQUIRED))
				.isEqualTo("Autenticação de dois fatores necessária.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.TWO_FACTOR_REQUIRED_GOOGLE))
				.isEqualTo("Autenticação de dois fatores necessária após o login com Google.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.TWO_FACTOR_COMPLETED))
				.isEqualTo("Autenticação de dois fatores concluída.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.TWO_FACTOR_REJECTED_LOCKED))
				.isEqualTo("Código de dois fatores rejeitado: conta temporariamente bloqueada.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.INVALID_TWO_FACTOR_CODE))
				.isEqualTo("Código de autenticação de dois fatores inválido.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.ACCOUNT_LOCKED))
				.isEqualTo("Conta bloqueada por tentativas inválidas.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.ACCOUNT_NOT_CONFIRMED))
				.isEqualTo("Conta ainda não confirmada.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.ACCOUNT_TEMPORARILY_LOCKED))
				.isEqualTo("Conta temporariamente bloqueada após muitas tentativas malsucedidas.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.INVALID_CREDENTIALS)).isEqualTo("Credenciais inválidas.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.LOGOUT_COMPLETED)).isEqualTo("Logout concluído.");
		Assertions.assertThat(labels.messageLabel(AccessMessages.LOGOUT_BY_INACTIVITY))
				.isEqualTo("Logout por inatividade.");
	}

	@Test
	void messageLabelReturnsNullForBlankAndRawValueForUnknownCode() {
		Assertions.assertThat(labels.messageLabel(null)).isNull();
		Assertions.assertThat(labels.messageLabel("  ")).isNull();
		Assertions.assertThat(labels.messageLabel("Legacy free text")).isEqualTo("Legacy free text");
	}
}