package br.com.jorgemelo.nimbusfilemanager.execution.application;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.jorgemelo.nimbusfilemanager.organization.application.dto.OrganizationExecutePayload;
import br.com.jorgemelo.nimbusfilemanager.organization.domain.enums.OrganizationLayout;

class ExecutionPayloadCodecTest {

	private final ExecutionPayloadCodec codec = new ExecutionPayloadCodec(new ObjectMapper());

	@Test
	void carriesTheArgumentsThroughTheColumnUnchanged() {
		OrganizationExecutePayload payload = new OrganizationExecutePayload(1, OrganizationLayout.YEAR_MONTH_DAY, 250,
				null, null, null, null, null, null, null, true, false, null, null, null);

		Assertions.assertThat(codec.decode(codec.encode(payload), OrganizationExecutePayload.class)).isEqualTo(payload);
	}

	/** A type whose arguments are already columns stores no payload at all. */
	@Test
	void writesNothingForATypeThatCarriesNoArguments() {
		Assertions.assertThat(codec.encode(null)).isNull();
	}

	/**
	 * A version that no longer knows a field has to run the request rather than
	 * refuse it: the row may have been queued by the version before this one.
	 */
	@Test
	void ignoresAFieldThisVersionNoLongerKnows() {
		String json = """
				{"schemaVersion":1,"layout":"YEAR_MONTH_DAY","limit":10,"somethingRemovedLater":"x"}""";

		OrganizationExecutePayload payload = codec.decode(json, OrganizationExecutePayload.class);

		Assertions.assertThat(payload.layout()).isEqualTo(OrganizationLayout.YEAR_MONTH_DAY);
		Assertions.assertThat(payload.limit()).isEqualTo(10);
	}

	/**
	 * Refused as a bad request rather than a passing failure, because the retry
	 * policy reads the type: a payload that will not parse will not parse the
	 * second time either, and a job handed back forever is worse than one that ends
	 * where somebody can see it.
	 */
	@Test
	void refusesAPayloadItCannotRead() {
		Assertions.assertThatThrownBy(() -> codec.decode("{ not json", OrganizationExecutePayload.class))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OrganizationExecutePayload");
	}

	@Test
	void refusesARowThatCarriesNoPayloadWhenOneWasExpected() {
		Assertions.assertThatThrownBy(() -> codec.decode(" ", OrganizationExecutePayload.class))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("carries none");
	}

	@Test
	void refusesToQueueSomethingItCannotWriteDown() {
		Object unwritable = new Object();

		Assertions.assertThatThrownBy(() -> codec.encode(unwritable)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Could not encode");
	}
}