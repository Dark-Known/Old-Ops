package persistence;

import model.Credential;

import java.util.List;

/**
 * Port: where saved server credentials are stored. Passwords are plain text on the way in and out;
 * protecting them at rest is the implementation's job.
 */
public interface CredentialRepository {

    boolean isConnected();

    String getConnectionError();

    /** Short reason the last save/delete failed although connected (including a duplicate name); null if it succeeded. */
    String getLastSaveError();

    /** Lets the caller record a failure it detected itself, so it surfaces through {@link #getLastSaveError()}. */
    void setLastSaveError(String message);

    Credential loadByName(String name);

    Credential loadById(String id);

    /** True when another credential (not {@code exceptId}) already has this name, ignoring case. */
    boolean isNameTaken(String name, String exceptId);

    /** Inserts or updates by id. False if not persisted (see {@link #getLastSaveError()}). */
    boolean save(Credential credential);

    boolean delete(String name);

    List<Credential> loadAll();

    void close();
}
