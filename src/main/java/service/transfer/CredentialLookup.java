package service.transfer;

import model.Credential;

import java.util.function.Consumer;

/** Finds a stored credential by name, logging an error line when it does not exist. */
@FunctionalInterface
public interface CredentialLookup {
    /** @param sideLabel which side of the task asked ("source", "watch", ...), used in the error line */
    Credential find(String credentialName, String sideLabel, Consumer<String> logLine);
}
