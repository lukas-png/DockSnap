package org.docksnap.docker;

import java.util.List;

public interface DockerGateway {
    List<ContainerInfo> listContainers();
    void stopContainerByName(String name, int timeoutSeconds);
    void startContainerByName(String name);
}
