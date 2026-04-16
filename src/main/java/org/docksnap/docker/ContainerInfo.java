package org.docksnap.docker;

public record ContainerInfo(String id, String name, String image, String state, String status) {}
