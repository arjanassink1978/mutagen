package dev.mutagen.output;

/** Extracted coordinates from a Maven pom.xml. */
public record PomInfo(String groupId, String artifactId, String version, String javaVersion,
                      String springBootVersion) {
}
