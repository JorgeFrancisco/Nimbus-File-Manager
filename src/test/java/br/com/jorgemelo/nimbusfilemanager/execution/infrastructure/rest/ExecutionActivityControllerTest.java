package br.com.jorgemelo.nimbusfilemanager.execution.infrastructure.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.jorgemelo.nimbusfilemanager.execution.application.ExecutionActivityService;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionActivity;
import br.com.jorgemelo.nimbusfilemanager.execution.application.dto.ExecutionActivitySnapshot;

/**
 * The wire contract, asserted over real HTTP because that is what the banner
 * reads. A renamed component of the record still compiles everywhere in Java
 * and silently empties the banner in the browser, so the field names are the
 * thing worth pinning down.
 */
class ExecutionActivityControllerTest {

	private static final UUID PRIMARY = UUID.fromString("018f2c00-0000-7000-8000-00000000000a");

	private final ExecutionActivityService executionActivityService = mock(ExecutionActivityService.class);
	private final MockMvc mockMvc = MockMvcBuilders
			.standaloneSetup(new ExecutionActivityController(executionActivityService)).build();

	@Test
	void publishesEveryFieldTheBannerDraws() throws Exception {
		when(executionActivityService.current()).thenReturn(new ExecutionActivitySnapshot(
				new ExecutionActivity(PRIMARY, "INVENTORY", "Inventário", "RUNNING", "Em execução", "C:\\midia", 35.8,
						62, 43, 120, false, "/app/executions/" + PRIMARY),
				List.of(new ExecutionActivity(UUID.randomUUID(), "CONVERSION", "Conversão", "PENDING", "Na fila", null,
						null, null, null, null, false, "/app/executions/x")),
				2));

		mockMvc.perform(get("/api/execution-activity")).andExpect(status().isOk())
				.andExpect(jsonPath("$.primary.executionId").value(PRIMARY.toString()))
				.andExpect(jsonPath("$.primary.executionType").value("INVENTORY"))
				.andExpect(jsonPath("$.primary.typeLabel").value("Inventário"))
				.andExpect(jsonPath("$.primary.status").value("RUNNING"))
				.andExpect(jsonPath("$.primary.statusLabel").value("Em execução"))
				.andExpect(jsonPath("$.primary.sourcePath").value("C:\\midia"))
				.andExpect(jsonPath("$.primary.percentComplete").value(35.8))
				.andExpect(jsonPath("$.primary.currentItemPercent").value(62))
				.andExpect(jsonPath("$.primary.filesFound").value(43))
				.andExpect(jsonPath("$.primary.totalExpected").value(120))
				.andExpect(jsonPath("$.primary.cancelRequested").value(false))
				.andExpect(jsonPath("$.primary.href").value("/app/executions/" + PRIMARY))
				.andExpect(jsonPath("$.others.length()").value(1)).andExpect(jsonPath("$.totalActive").value(2));
	}

	/**
	 * Nothing running is an answer with a shape, not an empty body or a 204: the
	 * banner has to be able to tell "no work" from "the request failed", and it
	 * treats those two differently - one hides the banner, the other leaves it
	 * alone.
	 */
	@Test
	void anIdleMachineStillAnswersWithASnapshot() throws Exception {
		when(executionActivityService.current()).thenReturn(ExecutionActivitySnapshot.idle());

		mockMvc.perform(get("/api/execution-activity")).andExpect(status().isOk())
				.andExpect(jsonPath("$.primary").doesNotExist()).andExpect(jsonPath("$.others").isEmpty())
				.andExpect(jsonPath("$.totalActive").value(0));
	}

	@Test
	void workWithNoDenominatorPublishesANullPercentageRatherThanZero() throws Exception {
		when(executionActivityService.current()).thenReturn(new ExecutionActivitySnapshot(
				new ExecutionActivity(PRIMARY, "QUARANTINE_PURGE", "Expurgo", "RUNNING", "Em execução", null, null,
						null, 37, null, true, "/app/executions/" + PRIMARY),
				List.of(), 1));

		mockMvc.perform(get("/api/execution-activity")).andExpect(status().isOk())
				.andExpect(jsonPath("$.primary.percentComplete").doesNotExist())
				.andExpect(jsonPath("$.primary.currentItemPercent").doesNotExist())
				.andExpect(jsonPath("$.primary.totalExpected").doesNotExist())
				.andExpect(jsonPath("$.primary.filesFound").value(37))
				.andExpect(jsonPath("$.primary.cancelRequested").value(true));
	}
}