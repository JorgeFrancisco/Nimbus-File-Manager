package br.com.jorgemelo.nimbusfilemanager.backup.application.dto;

/**
 * How to reach the database from outside the JVM.
 *
 * <p>
 * The dump tools take a host, a port and a database name rather than a JDBC
 * url, so the url configured for the driver is parsed once into this and passed
 * around already split.
 */
public record DatabaseConnection(String host, int port, String database, String username, String password) {
}