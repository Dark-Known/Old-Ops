package service.sftp;

/** One entry of a remote directory listing. */
public record RemoteEntry(String name, long size, long modifiedEpochSeconds, boolean directory) {}
