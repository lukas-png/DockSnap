package org.docksnap.domain;

import java.util.List;

public record Job(
        String id,
        String name,
        BackupMode mode,
        List<String> paths,
        List<String> stopContainers,
        List<String> startContainers,
        String filenamePrefix,
        BorgOptions borg,
        UploadTarget upload,
        String schedule
) {
    public record BorgOptions(
            String repo,
            String archivePrefix,
            String compression,
            String sshKeyPath,
            String knownHostsPath,
            Integer sshPort,
            Integer keep,
            String encryption
    ) {}

    public record UploadTarget(
            String type,
            String uri
    ) {}
}
