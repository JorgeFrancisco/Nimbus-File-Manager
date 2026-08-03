package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.FileHashes;

@Service
public class FileHashService {

	private static final int BUFFER_SIZE = 1024 * 1024;

	private final FileInputStreamReader fileInputStreamReader;
	private final MessageDigestFactory messageDigestFactory;

	@Autowired
	public FileHashService() {
		this(Files::newInputStream, MessageDigest::getInstance);
	}

	FileHashService(FileInputStreamReader fileInputStreamReader) {
		this(fileInputStreamReader, MessageDigest::getInstance);
	}

	FileHashService(FileInputStreamReader fileInputStreamReader, MessageDigestFactory messageDigestFactory) {
		this.fileInputStreamReader = fileInputStreamReader;
		this.messageDigestFactory = messageDigestFactory;
	}

	public String sha256(Path file) {
		return hash(file, "SHA-256");
	}

	public String md5(Path file) {
		return hash(file, "MD5");
	}

	public FileHashes hashes(Path file) {
		FileValidationUtils.validateFile(file);

		try {
			MessageDigest sha256 = messageDigestFactory.getInstance("SHA-256");

			MessageDigest md5 = messageDigestFactory.getInstance("MD5");

			try (InputStream input = fileInputStreamReader.open(file)) {
				byte[] buffer = new byte[BUFFER_SIZE];

				int read;

				while ((read = input.read(buffer)) != -1) {
					sha256.update(buffer, 0, read);
					md5.update(buffer, 0, read);
				}
			}

			return new FileHashes(toHex(sha256.digest()), toHex(md5.digest()));
		} catch (IOException e) {
			throw new IllegalStateException(
					"Could not read file to calculate hash: " + file + ". Cause: " + e.getMessage(), e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Hash algorithm not available: " + e.getMessage(), e);
		}
	}

	private String hash(Path file, String algorithm) {
		FileValidationUtils.validateFile(file);

		try {
			MessageDigest digest = messageDigestFactory.getInstance(algorithm);

			try (InputStream input = fileInputStreamReader.open(file)) {
				byte[] buffer = new byte[BUFFER_SIZE];

				int read;

				while ((read = input.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}

			return toHex(digest.digest());
		} catch (IOException e) {
			throw new IllegalStateException(
					"Could not read file to calculate hash: " + file + ". Cause: " + e.getMessage(), e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Hash algorithm not available: " + algorithm, e);
		}
	}

	private String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);

		for (byte value : bytes) {
			builder.append(String.format("%02x", value));
		}

		return builder.toString();
	}
}