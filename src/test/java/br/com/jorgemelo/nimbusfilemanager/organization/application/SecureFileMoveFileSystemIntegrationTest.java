package br.com.jorgemelo.nimbusfilemanager.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.FileHashService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.InMemorySelfWrittenPaths;
import br.com.jorgemelo.nimbusfilemanager.shared.application.SelfWrittenPathRegistry;

/**
 * The secure move against a real file system, with the real verifier behind it.
 *
 * <p>
 * {@code SecureFileMoveTest} mocks the verifier to exercise the orchestration;
 * here nothing is mocked, because what is under test is the promise the product
 * is built on: <b>every move of a user's file either lands verified, or leaves
 * the original exactly where it was.</b> A hash comparison against a stub
 * cannot show that.
 *
 * <p>
 * The cases are the ones that break on somebody else's disk rather than on the
 * developer's: a name that is not ASCII, a read-only attribute that came off a
 * phone or a camera card, a file another process is holding open, a path past
 * the Windows limit. None of them appear in a green suite that only ever moves
 * {@code a.txt} between two temporary folders.
 *
 * <p>
 * The Windows-only cases are skipped elsewhere rather than failed: the
 * behaviours they pin down - mandatory locking, the read-only attribute, the
 * 260-character path - are the operating system's, and the CI runner is Linux.
 * The portable ones run everywhere, which is the point of keeping them apart.
 */
class SecureFileMoveFileSystemIntegrationTest {

	private final SecureFileMove secureFileMove = new SecureFileMove(
			new OrganizationMoveVerifier(new FileHashService()),
			new SelfWrittenPathRegistry(new InMemorySelfWrittenPaths(), Clock.systemDefaultZone()));

	/**
	 * A library is full of names nobody would type: accents, cedillas, and the
	 * emoji a phone puts in an album name. The move has to carry the bytes through
	 * unchanged, and the check is the file's own hash rather than its size.
	 */
	@Test
	void movesAFileWhoseNameIsNotAscii(@TempDir Path dir) throws IOException {
		Path source = write(dir.resolve("Aniversário da Conceição — 2008 ✅.jpg"), "conteúdo com acentuação");
		Path target = dir.resolve("organizado").resolve("Aniversário da Conceição — 2008 ✅.jpg");

		String before = sha256(source);

		secureFileMove.move(source, target, false);

		assertThat(source).doesNotExist();
		assertThat(target).exists();
		assertThat(sha256(target)).isEqualTo(before);
	}

	/**
	 * Folders arriving from a phone or a camera card routinely carry the read-only
	 * attribute. Moving such a file is legitimate - the bytes do not change - and
	 * the attribute has to survive the move, or the next tool to look at the
	 * library sees a file that changed for no reason.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void movesAReadOnlyFileAndKeepsItReadOnly(@TempDir Path dir) throws IOException {
		Path source = write(dir.resolve("read-only.jpg"), "bytes");
		Path target = dir.resolve("destino").resolve("read-only.jpg");

		Files.setAttribute(source, "dos:readonly", true);

		String before = sha256(source);

		secureFileMove.move(source, target, false);

		assertThat(target).exists();
		assertThat(sha256(target)).isEqualTo(before);
		assertThat(Files.getAttribute(target, "dos:readonly")).isEqualTo(true);
	}

	/**
	 * The case that matters most: another process is holding the file open, which
	 * is what a photo viewer, an antivirus or a sync client does all day. The move
	 * must fail - and the user's file must still be there, byte for byte.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void refusesToMoveAFileAnotherProcessHoldsOpenAndLeavesItIntact(@TempDir Path dir) throws IOException {
		Path source = write(dir.resolve("em-uso.mp4"), "conteúdo que não pode se perder");
		Path target = dir.resolve("destino").resolve("em-uso.mp4");

		String before = sha256(source);

		try (RandomAccessFile handle = new RandomAccessFile(source.toFile(), "rw");
				FileChannel channel = handle.getChannel();
				var _ = channel.lock()) {
			assertThatThrownBy(() -> secureFileMove.move(source, target, false)).isInstanceOf(IOException.class);
		}

		assertThat(source).exists();
		assertThat(sha256(source)).isEqualTo(before);
		assertThat(target).doesNotExist();
	}

	/**
	 * A read-only file at the destination is not a file to be silently replaced -
	 * Windows refuses, and the right outcome is that the source survives so the
	 * operator can decide. What must never happen is losing both ends.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void leavesTheSourceIntactWhenTheReadOnlyTargetCannotBeReplaced(@TempDir Path dir) throws IOException {
		Path source = write(dir.resolve("novo.jpg"), "o arquivo que seria movido");
		Path target = write(dir.resolve("antigo.jpg"), "o que ja estava la");

		Files.setAttribute(target, "dos:readonly", true);

		String before = sha256(source);

		assertThatThrownBy(() -> secureFileMove.move(source, target, true)).isInstanceOf(IOException.class);

		assertThat(source).exists();
		assertThat(sha256(source)).isEqualTo(before);
	}

	/**
	 * Windows refuses a path over 260 characters unless long paths are enabled for
	 * the machine, and a deep library reaches that on its own - year, event,
	 * camera, original file name. Either outcome is acceptable; what is not
	 * acceptable is the move reporting success with the file gone from both ends,
	 * so that is what this pins down.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void eitherMovesOrRefusesAPathPastTheWindowsLimitButNeverLosesTheFile(@TempDir Path dir) throws IOException {
		Path source = write(dir.resolve("origem.jpg"), "bytes que precisam sobreviver");

		String before = sha256(source);

		Path deep = dir;

		for (int level = 0; level < 12; level++) {
			deep = deep.resolve("pasta-com-nome-suficientemente-longo-" + level);
		}

		Path target = deep.resolve("origem.jpg");

		try {
			secureFileMove.move(source, target, false);

			assertThat(target).exists();
			assertThat(sha256(target)).isEqualTo(before);
			assertThat(source).doesNotExist();
		} catch (IOException _) {
			// Refused by the file system: then nothing may have been taken away.
			assertThat(source).exists();
			assertThat(sha256(source)).isEqualTo(before);
		}
	}

	/**
	 * The library on an external disk is the normal case for this product, and a
	 * disk that is not there is what an unplugged cable looks like from inside the
	 * application. Pulling one mid-move cannot be staged in a test, but its
	 * observable end - a target on a volume that does not answer - can, and the
	 * requirement is the same one: fail, and do not take the file with you.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void refusesATargetOnAVolumeThatIsNotThereAndKeepsTheSource(@TempDir Path dir) throws IOException {
		Path absentVolume = volumeThatIsNotMounted();

		// Every letter answering means there is no absent volume to point at. Skipped
		// rather than failed: what would fail is the machine, not the code.
		Assumptions.assumeTrue(absentVolume != null, "no unmounted drive letter to aim at");

		Path source = write(dir.resolve("na-origem.jpg"), "bytes que ficam onde estao");

		String before = sha256(source);

		Path target = absentVolume.resolve("biblioteca").resolve("na-origem.jpg");

		assertThatThrownBy(() -> secureFileMove.move(source, target, false)).isInstanceOf(IOException.class);

		assertThat(source).exists();
		assertThat(sha256(source)).isEqualTo(before);
	}

	/** The highest drive letter nothing answers on, or {@code null} if all do. */
	private Path volumeThatIsNotMounted() {
		for (char letter = 'Z'; letter >= 'R'; letter--) {
			Path root = Path.of(letter + ":\\");

			if (!Files.exists(root)) {
				return root;
			}
		}

		return null;
	}

	/**
	 * The target folder does not exist yet on a first organization, and creating
	 * it is part of the move rather than a precondition the caller has to
	 * remember.
	 */
	@Test
	void createsEveryMissingFolderOfTheTargetPath(@TempDir Path dir) throws IOException {
		Path source = write(dir.resolve("foto.jpg"), "bytes");
		Path target = dir.resolve("2008").resolve("07").resolve("Aniversário").resolve("foto.jpg");

		secureFileMove.move(source, target, false);

		assertThat(target).exists();
		assertThat(target.getParent()).isDirectory();
	}

	private Path write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());

		return Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private String sha256(Path file) throws IOException {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every JVM", e);
		}
	}
}