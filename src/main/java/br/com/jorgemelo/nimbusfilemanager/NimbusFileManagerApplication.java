package br.com.jorgemelo.nimbusfilemanager;

import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import br.com.jorgemelo.nimbusfilemanager.inventory.infrastructure.watch.source.windows.WindowsUsnElevation;
import br.com.jorgemelo.nimbusfilemanager.shared.application.StartupArguments;
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
		// Before the workspace is resolved, because a command line that arrived split
		// would resolve to the wrong one - and creating a database under it is not
		// something a later check could undo.
		StartupArguments.requireOptionsOnly(args);

		// First of all, because everything below already depends on it. A restart with
		// administrator rights is given a fresh environment by Windows, so the variable
		// that may have chosen this workspace does not survive it; the process that did
		// see it writes the answer on the command line, and this is where that answer is
		// picked up.
		WorkspaceLocation.adoptFrom(args);

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

		// Read from the arguments because both decisions below are made before there is
		// an environment to ask, and both are about which of the two processes this is.
		boolean standaloneWorker = StartupRole.isStandaloneWorker(args);

		// A worker has no screens to open and no shutdown of its own to offer, so a
		// second icon in the tray would be one the application did not put there and
		// nothing behind it.
		if (!standaloneWorker) {
			ApplicationTray.install(workspace.resolve("logs"), workspace);
		}

		SpringApplication application = new SpringApplication(NimbusFileManagerApplication.class);

		// Spring Boot assumes headless, which is right for a server and wrong here:
		// the tray icon is the only thing this application shows.
		application.setHeadless(false);

		// And a worker of its own has nothing at all that would hold this JVM open.
		// The application has Tomcat and the tray thread; a worker serves no port and
		// installs no tray, its loop runs on virtual threads and every executor here is
		// a daemon one - so the moment startup finished, the last non-daemon thread was
		// this method returning, and the JVM ended normally with status 0 about a
		// second after the worker announced it was ready. The supervisor read that as a
		// crash and restarted it, eight times, before giving up: the installed product
		// ran no background work at all.
		//
		// Spring Boot's own keep-alive rather than a latch or a thread of this
		// application's: it holds a non-daemon thread for exactly as long as the
		// context is open, so an ordered shutdown still ends the process and nothing
		// has to be woken up or interrupted to let it.
		application.setKeepAlive(standaloneWorker);

		application.addListeners(new WorkspaceBootstrapListener());

		application.run(args);
	}
}