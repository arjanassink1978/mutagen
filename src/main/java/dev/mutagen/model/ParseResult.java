package dev.mutagen.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The result of scanning a repository: all discovered endpoints plus scan metadata. */
public class ParseResult {

    private String repoPath;
    private Instant scannedAt = Instant.now();
    private int filesScanned;
    private int controllersFound;
    private List<EndpointInfo> endpoints = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public String getRepoPath()                              { return repoPath; }
    public void setRepoPath(String repoPath)                 { this.repoPath = repoPath; }
    public Instant getScannedAt()                            { return scannedAt; }
    public void setScannedAt(Instant scannedAt)              { this.scannedAt = scannedAt; }
    public int getFilesScanned()                             { return filesScanned; }
    public void setFilesScanned(int filesScanned)            { this.filesScanned = filesScanned; }
    public int getControllersFound()                         { return controllersFound; }
    public void setControllersFound(int controllersFound)    { this.controllersFound = controllersFound; }
    public List<EndpointInfo> getEndpoints()                 { return endpoints; }
    public void setEndpoints(List<EndpointInfo> endpoints)   { this.endpoints = endpoints; }
    public List<String> getWarnings()                        { return warnings; }
    public void setWarnings(List<String> warnings)           { this.warnings = warnings; }
}
