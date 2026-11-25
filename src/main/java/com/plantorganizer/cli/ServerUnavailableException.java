package com.plantorganizer.cli;

/** No Plant Organizer server answers at the --server address (not started, stopped, or wrong URL). */
public class ServerUnavailableException extends CliException {

    public ServerUnavailableException(String serverUrl) {
        super("No Plant Organizer server found at " + serverUrl + ". Start it first with ./mvnw spring-boot:run "
                + "(or give the right address with --server).");
    }
}
