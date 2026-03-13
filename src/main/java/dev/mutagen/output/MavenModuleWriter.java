package dev.mutagen.output;

import dev.mutagen.generator.GeneratedTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes generated tests as a Maven module ({@code rest-assured-tests}).
 *
 * <p>The module is always placed as a <em>direct child of the nearest pom-packaging ancestor</em>
 * (i.e. a sibling of the target project, not nested inside it). This avoids the Maven restriction
 * that a {@code <parent>} pom must have {@code packaging=pom}.
 *
 * <p>The Spring Boot version is read from the target pom's {@code <dependencyManagement>} and
 * imported via a BOM in the module pom, so {@code spring-boot-starter-test} resolves correctly.
 */
public class MavenModuleWriter {

    private static final Logger log = LoggerFactory.getLogger(MavenModuleWriter.class);
    static final String MODULE_NAME = "rest-assured-tests";

    public void write(Path repoRoot, List<GeneratedTest> tests) throws IOException {
        Path targetPomPath = repoRoot.resolve("pom.xml");
        PomInfo targetPom = readPomInfo(targetPomPath);
        log.info("Target project: {}:{}:{} (Java {}, Spring Boot {})",
                targetPom.groupId(), targetPom.artifactId(), targetPom.version(),
                targetPom.javaVersion(), targetPom.springBootVersion().isBlank() ? "?" : targetPom.springBootVersion());

        // Walk up to nearest pom-packaging ancestor — this becomes the parent of our module
        Path parentPomPath = findPomPackagingAncestor(targetPomPath);
        PomInfo parentPom = readPomInfo(parentPomPath);
        log.info("Using as parent: {}:{}:{}", parentPom.groupId(), parentPom.artifactId(), parentPom.version());

        // Module lives directly under the parent pom directory (sibling of the target)
        Path moduleDir = parentPomPath.getParent().resolve(MODULE_NAME);
        Files.createDirectories(moduleDir.resolve("src/test/java"));

        String parentRelativePath = moduleDir.relativize(parentPomPath).toString();
        String moduleRef = parentPomPath.getParent().relativize(moduleDir).toString();

        writeModulePom(moduleDir, parentPom, parentRelativePath, targetPom);
        writeTestFiles(moduleDir, tests);
        addModuleToParentPom(parentPomPath, moduleRef);
    }

    /**
     * Returns the path to the test module directory for a given repo root.
     * This is the directory where the generated tests live and where Pitest should run.
     */
    public Path resolveModuleDir(Path repoRoot) throws IOException {
        Path targetPomPath = repoRoot.resolve("pom.xml");
        Path parentPomPath = findPomPackagingAncestor(targetPomPath);
        return parentPomPath.getParent().resolve(MODULE_NAME);
    }

    // -------------------------------------------------------------------------

    /**
     * Walks up from {@code pomPath} until it finds a pom with {@code <packaging>pom</packaging>}.
     * Falls back to the target itself if no ancestor is found on disk.
     */
    Path findPomPackagingAncestor(Path pomPath) throws IOException {
        String packaging = readXpath(pomPath, "/project/packaging");
        if ("pom".equals(packaging)) {
            return pomPath;
        }

        String relPath = readXpath(pomPath, "/project/parent/relativePath");
        if (relPath.isBlank()) {
            relPath = "../pom.xml";
        }

        Path parentPomPath = pomPath.getParent().resolve(relPath).normalize();
        if (Files.exists(parentPomPath)) {
            return parentPomPath;
        }

        log.warn("Parent pom not found at {}; using target pom as parent (may cause build issues)", parentPomPath);
        return pomPath;
    }

    PomInfo readPomInfo(Path pomPath) throws IOException {
        try {
            Document doc = parseXml(pomPath);
            XPath xpath = XPathFactory.newInstance().newXPath();

            String groupId = xpath.evaluate("/project/groupId", doc).strip();
            if (groupId.isBlank()) {
                groupId = xpath.evaluate("/project/parent/groupId", doc).strip();
            }
            String artifactId = xpath.evaluate("/project/artifactId", doc).strip();
            String version    = xpath.evaluate("/project/version", doc).strip();
            if (version.isBlank()) {
                version = xpath.evaluate("/project/parent/version", doc).strip();
            }
            String javaVersion = xpath.evaluate("/project/properties/maven.compiler.source", doc).strip();
            if (javaVersion.isBlank()) {
                javaVersion = "17";
            }
            String springBootVersion = readSpringBootVersion(doc, xpath);

            return new PomInfo(groupId, artifactId, version, javaVersion, springBootVersion);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to parse pom.xml: " + pomPath, e);
        }
    }

    /**
     * Reads the Spring Boot version from {@code <dependencyManagement>}, checking both
     * {@code spring-boot-starter-parent} and {@code spring-boot-dependencies} artifact ids.
     */
    private String readSpringBootVersion(Document doc, XPath xpath) throws Exception {
        for (String artifactId : List.of("spring-boot-starter-parent", "spring-boot-dependencies")) {
            String v = xpath.evaluate(
                    "/project/dependencyManagement/dependencies/dependency" +
                    "[artifactId='" + artifactId + "']/version", doc).strip();
            if (!v.isBlank()) return v;
        }
        return "";
    }

    private String readXpath(Path pomPath, String expression) throws IOException {
        try {
            Document doc = parseXml(pomPath);
            return XPathFactory.newInstance().newXPath().evaluate(expression, doc).strip();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("XPath error on " + pomPath, e);
        }
    }

    private Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        return builder.parse(path.toFile());
    }

    private void writeModulePom(Path moduleDir, PomInfo parent, String parentRelativePath,
                                PomInfo appUnderTest) throws IOException {
        String dependencyManagement = appUnderTest.springBootVersion().isBlank() ? "" : """
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <version>%s</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>

                """.formatted(appUnderTest.springBootVersion());

        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>

                  <parent>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <relativePath>%s</relativePath>
                  </parent>

                  <artifactId>rest-assured-tests</artifactId>
                  <name>rest-assured-tests</name>
                  <description>RestAssured integration tests generated by Mutagen</description>

                  <properties>
                    <maven.compiler.source>%s</maven.compiler.source>
                    <maven.compiler.target>%s</maven.compiler.target>
                  </properties>

                %s<dependencies>
                    <!-- Application under test -->
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-test</artifactId>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>io.rest-assured</groupId>
                      <artifactId>rest-assured</artifactId>
                      <version>5.5.0</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>

                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <executions>
                          <execution>
                            <goals>
                              <goal>integration-test</goal>
                              <goal>verify</goal>
                            </goals>
                          </execution>
                        </executions>
                      </plugin>
                      <plugin>
                        <groupId>org.pitest</groupId>
                        <artifactId>pitest-maven</artifactId>
                        <version>1.17.4</version>
                        <dependencies>
                          <dependency>
                            <groupId>org.pitest</groupId>
                            <artifactId>pitest-junit5-plugin</artifactId>
                            <version>1.2.1</version>
                          </dependency>
                        </dependencies>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(
                parent.groupId(), parent.artifactId(), parent.version(), parentRelativePath,
                appUnderTest.javaVersion(), appUnderTest.javaVersion(),
                dependencyManagement,
                appUnderTest.groupId(), appUnderTest.artifactId(), appUnderTest.version()
        );

        Path pomPath = moduleDir.resolve("pom.xml");
        Files.writeString(pomPath, pom);
        log.info("Written: {}", pomPath);
    }

    private void writeTestFiles(Path moduleDir, List<GeneratedTest> tests) throws IOException {
        for (GeneratedTest test : tests) {
            Path target = moduleDir.resolve(test.getRelativeFilePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, test.getSourceCode());
            log.info("Written: {}", target);
        }
    }

    void addModuleToParentPom(Path pomPath, String moduleRef) throws IOException {
        String content = Files.readString(pomPath);

        if (content.contains("<module>" + moduleRef + "</module>")) {
            log.debug("Module {} already present in {}", moduleRef, pomPath);
            return;
        }

        String moduleEntry = "    <module>" + moduleRef + "</module>";
        String updated;
        if (content.contains("<modules>")) {
            updated = content.replaceFirst("<modules>", "<modules>\n" + moduleEntry);
        } else {
            updated = content.replace(
                    "</project>",
                    "\n  <modules>\n" + moduleEntry + "\n  </modules>\n</project>"
            );
        }

        Files.writeString(pomPath, updated);
        log.info("Added module {} to {}", moduleRef, pomPath);
    }
}
