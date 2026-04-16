package org.docksnap.docker;

import com.github.dockerjava.api.DockerClient;
import java.util.List;
import java.util.stream.Collectors;

public class DockerJavaGateway implements DockerGateway {
    private final DockerClient docker;

    public DockerJavaGateway(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public List<ContainerInfo> listContainers() {
        return docker.listContainersCmd().withShowAll(true).exec().stream().map(c -> {
            String name = (c.getNames() != null && c.getNames().length > 0)
                    ? c.getNames()[0].replaceFirst("^/", "")
                    : c.getId();
            return new ContainerInfo(c.getId(), name, c.getImage(), c.getState(), c.getStatus());
        }).collect(Collectors.toList());
    }

    @Override
    public void stopContainerByName(String name, int timeoutSeconds) {
        docker.stopContainerCmd(name).withTimeout(timeoutSeconds).exec();
    }

    @Override
    public void startContainerByName(String name) {
        docker.startContainerCmd(name).exec();
    }
}
