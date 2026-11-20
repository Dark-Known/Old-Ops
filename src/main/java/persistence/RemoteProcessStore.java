package persistence;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Where the remote processes that this installation's listeners reported are kept, so they are still known after a restart.
 * Technology-neutral: obtain one through {@link RemoteProcessStores}.
 */
public interface RemoteProcessStore {

    /**
     * One remote process: which listener it belongs to, the host and user, its role ("listener" or "shell"), its pid and
     * start time (a Windows FILETIME) and when it was recorded.
     */
    record Row(String listenerKey, String host, String user, String role, long pid, long startFileTime, long recordedAt) { }

    List<Row> loadAll() throws IOException;

    /** Adds the rows; a row with the same key, pid and start time is kept once. */
    void insert(Collection<Row> rows) throws IOException;

    void delete(Collection<Row> rows) throws IOException;

    void purgeOlderThan(long recordedAtMillis) throws IOException;
}
