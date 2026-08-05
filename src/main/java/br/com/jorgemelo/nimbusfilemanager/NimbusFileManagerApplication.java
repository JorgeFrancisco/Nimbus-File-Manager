package br.com.jorgemelo.nimbusfilemanager;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows.WindowsUsnElevation;
import br.com.jorgemelo.nimbusfilemanager.shared.application.StartupRole;
import br.com.jorgemelo.nimbusfilemanager.shared.application.WorkspaceLocation;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.WorkspaceBootstrapListener;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.OrganizationPlanProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.BoundaryDatasetProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.InventoryWatchProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.LocationRebuildProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UpdateProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.UsnJournalProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.VideoSimilarityProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.NimbusFileManagerProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto.ProcessingProperties;
import br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.desktop.ApplicationTray;
import br.com.jorgemelo.nimbusfilemanager.worker.application.WorkerProperties;

@SpringBootApplication
@EnableConfigurationProperties({ NimbusFileManagerProperties.class, BoundaryDatasetProperties.class,
		ProcessingProperties.class, InventoryWatchProperties.class, LocationRebuildProperties.class,
		UsnJournalProperties.class, VideoSimilarityProperties.class, UpdateProperties.class,
		OrganizationPlanProperties.class,
		WorkerProperties.class })
public class NimbusFileManagerApplication {

	public static void main(String[] args) {
		// Before anything else, and before Spring: an elevated restart is only cheap
		// while there is nothing to throw away. Answers false unless it actually
		// started one - including when the person declined the prompt, which is a
		// perfectly good answer that leaves the application starting as it always did.
		if (WindowsUsnElevation.relaunchIfNeeded(args)) {
			// Exit rather than return: this process has handed the application over, and
			// what it must not do is carry on into anything that assumes it is the one
			// running. Returning would leave that to whatever main does next.
			System.exit(0);
		}

		Path workspace = Path.of(WorkspaceLocation.resolve());

		// A worker has no screens to open and no shutdown of its own to offer, so a
		// second icon in the tray would be one the application did not put there and
		// nothing behind it. Decided from the arguments because the tray is installed
		// before there is an environment to ask.
		if (!StartupRole.isStandaloneWorker(args)) {
			ApplicationTray.install(workspace.resolve("logs"), workspace);
		}

		SpringApplication application = new SpringApplication(NimbusFileManagerApplication.class);

		// Spring Boot assumes headless, which is right for a server and wrong here:
		// the tray icon is the only thing this application shows.
		application.setHeadless(false);

		application.addListeners(new WorkspaceBootstrapListener());

		application.run(args);
	}
}