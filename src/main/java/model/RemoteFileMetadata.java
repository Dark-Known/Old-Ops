package model;

import java.time.Instant;

public record RemoteFileMetadata(
        String fileName,
        Instant lastModified,
        long size
) {}