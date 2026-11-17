package service.transfer;

/** A remote file's last-modified time (epoch ms) and size (bytes), as reported by a directory listing. */
public final class RemoteFileStat {
    public final long epochMillis;
    public final long size;

    public RemoteFileStat(long epochMillis, long size) {
        this.epochMillis = epochMillis;
        this.size = size;
    }
}
