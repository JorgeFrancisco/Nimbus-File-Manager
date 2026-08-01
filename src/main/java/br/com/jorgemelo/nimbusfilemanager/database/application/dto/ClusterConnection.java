package br.com.jorgemelo.nimbusfilemanager.database.application.dto;

/**
 * How to reach the cluster this run started. The password is generated once,
 * when the cluster is created, and kept beside it - there is no account for a
 * person here, only the one the application uses to reach a server that listens
 * on loopback alone.
 *
 * @param port the port the server actually bound, which is not necessarily the
 * one first chosen
 * @param password the generated password for the application's role
 */
public record ClusterConnection(int port, String password) {
}