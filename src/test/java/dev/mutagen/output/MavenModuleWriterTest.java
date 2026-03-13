package dev.mutagen.output;

import dev.mutagen.generator.GeneratedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenModuleWriterTest {

    @TempDir
    Path tmp;

    private final MavenModuleWriter writer = new MavenModuleWriter();

    // ------------------------------------------------------------------
    // readPomInfo
    // ------------------------------------------------------------------

    @Test
    void readPomInfo_explicitGroupId() throws Exception {
        writePomAt(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>myapp</artifactId>
                  <version>1.2.3</version>
                  <properties>
                    <maven.compiler.source>21</maven.compiler.source>
                  </properties>
                </project>
                """);

        PomInfo info = writer.readPomInfo(tmp.resolve("pom.xml"));

        assertThat(info.groupId()).isEqualTo("com.example");
        assertThat(info.artifactId()).isEqualTo("myapp");
        assertThat(info.version()).isEqualTo("1.2.3");
        assertThat(info.javaVersion()).isEqualTo("21");
        assertThat(info.springBootVersion()).isBlank();
    }

    @Test
    void readPomInfo_groupIdInheritedFromParent() throws Exception {
        writePomAt(tmp, """
                <project>
                  <parent>
                    <groupId>io.techchamps</groupId>
                    <artifactId>casus</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                  </parent>
                  <artifactId>restbackend</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                </project>
                """);

        PomInfo info = writer.readPomInfo(tmp.resolve("pom.xml"));

        assertThat(info.groupId()).isEqualTo("io.techchamps");
        assertThat(info.artifactId()).isEqualTo("restbackend");
        assertThat(info.javaVersion()).isEqualTo("17"); // default
    }

    @Test
    void readPomInfo_readsSpringBootVersion() throws Exception {
        writePomAt(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>myapp</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.4.0</version>
                        <scope>import</scope>
                        <type>pom</type>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        PomInfo info = writer.readPomInfo(tmp.resolve("pom.xml"));

        assertThat(info.springBootVersion()).isEqualTo("3.4.0");
    }

    @Test
    void readPomInfo_readsSpringBootDependenciesVersion() throws Exception {
        writePomAt(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>myapp</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <version>3.3.1</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        assertThat(writer.readPomInfo(tmp.resolve("pom.xml")).springBootVersion()).isEqualTo("3.3.1");
    }

    // ------------------------------------------------------------------
    // findPomPackagingAncestor
    // ------------------------------------------------------------------

    @Test
    void findPomPackagingAncestor_returnsItself_whenPackagingIsPom() throws Exception {
        writePomAt(tmp, pomPackagingPom("com.example", "parent", "1.0"));

        assertThat(writer.findPomPackagingAncestor(tmp.resolve("pom.xml")))
                .isEqualTo(tmp.resolve("pom.xml"));
    }

    @Test
    void findPomPackagingAncestor_walksUpToParent_whenTargetIsJar() throws Exception {
        writePomAt(tmp, pomPackagingPom("com.example", "root", "1.0"));

        Path appDir = tmp.resolve("app");
        Files.createDirectories(appDir);
        writePomAt(appDir, """
                <project>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>app</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertThat(writer.findPomPackagingAncestor(appDir.resolve("pom.xml")))
                .isEqualTo(tmp.resolve("pom.xml"));
    }

    @Test
    void findPomPackagingAncestor_fallsBackToTarget_whenNoParentExists() throws Exception {
        writePomAt(tmp, """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>standalone</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertThat(writer.findPomPackagingAncestor(tmp.resolve("pom.xml")))
                .isEqualTo(tmp.resolve("pom.xml"));
    }

    // ------------------------------------------------------------------
    // write — target already has pom packaging (simple / standalone case)
    // ------------------------------------------------------------------

    @Test
    void write_createsModuleDirectory() throws Exception {
        prepareStandaloneProject(tmp);
        writer.write(tmp, singleTest());

        assertThat(tmp.resolve("rest-assured-tests/src/test/java")).isDirectory();
    }

    @Test
    void write_createsModulePom() throws Exception {
        prepareStandaloneProject(tmp);
        writer.write(tmp, singleTest());

        String content = Files.readString(tmp.resolve("rest-assured-tests/pom.xml"));
        assertThat(content).contains("<artifactId>rest-assured-tests</artifactId>");
        assertThat(content).contains("io.rest-assured");
    }

    @Test
    void write_modulePomContainsDependencyManagement_whenSpringBootVersionKnown() throws Exception {
        writePomAt(tmp, """
                <project>
                  <packaging>pom</packaging>
                  <groupId>com.example</groupId>
                  <artifactId>myapp</artifactId>
                  <version>1.0.0</version>
                  <properties><maven.compiler.source>17</maven.compiler.source></properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.4.0</version>
                        <scope>import</scope><type>pom</type>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        writer.write(tmp, singleTest());

        String modulePom = Files.readString(tmp.resolve("rest-assured-tests/pom.xml"));
        assertThat(modulePom).contains("<artifactId>spring-boot-dependencies</artifactId>");
        assertThat(modulePom).contains("<version>3.4.0</version>");
    }

    @Test
    void write_writesTestFile() throws Exception {
        prepareStandaloneProject(tmp);
        writer.write(tmp, singleTest());

        Path testFile = tmp.resolve(
                "rest-assured-tests/src/test/java/com/example/controller/UserControllerIT.java");
        assertThat(testFile).exists();
        assertThat(Files.readString(testFile)).contains("class UserControllerIT");
    }

    @Test
    void write_addsModuleToParentPom() throws Exception {
        prepareStandaloneProject(tmp);
        writer.write(tmp, singleTest());

        assertThat(Files.readString(tmp.resolve("pom.xml")))
                .contains("<module>rest-assured-tests</module>");
    }

    // ------------------------------------------------------------------
    // write — Spring Boot leaf module (jar packaging) → module becomes sibling
    // ------------------------------------------------------------------

    @Test
    void write_placesModuleAsSiblingOfTarget_whenTargetIsJar() throws Exception {
        // grandparent at tmp/pom.xml
        writePomAt(tmp, pomPackagingPom("io.techchamps", "casus", "0.0.1-SNAPSHOT"));

        // Spring Boot app at tmp/restbackend/
        Path appDir = tmp.resolve("restbackend");
        Files.createDirectories(appDir);
        writePomAt(appDir, """
                <project>
                  <parent>
                    <groupId>io.techchamps</groupId>
                    <artifactId>casus</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>restbackend</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <properties><maven.compiler.source>17</maven.compiler.source></properties>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.4.0</version>
                        <scope>import</scope><type>pom</type>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        writer.write(appDir, singleTest());

        // Module is a sibling: tmp/rest-assured-tests/ (NOT tmp/restbackend/rest-assured-tests/)
        assertThat(tmp.resolve("rest-assured-tests/pom.xml")).exists();
        assertThat(appDir.resolve("rest-assured-tests")).doesNotExist();
    }

    @Test
    void write_moduleParentIsGrandparent_whenTargetIsJar() throws Exception {
        writePomAt(tmp, pomPackagingPom("io.techchamps", "casus", "0.0.1-SNAPSHOT"));

        Path appDir = tmp.resolve("restbackend");
        Files.createDirectories(appDir);
        writePomAt(appDir, """
                <project>
                  <parent>
                    <groupId>io.techchamps</groupId>
                    <artifactId>casus</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>restbackend</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                </project>
                """);

        writer.write(appDir, singleTest());

        String modulePom = Files.readString(tmp.resolve("rest-assured-tests/pom.xml"));
        assertThat(modulePom).contains("<artifactId>casus</artifactId>");
        assertThat(modulePom).contains("<relativePath>../pom.xml</relativePath>");
    }

    @Test
    void write_addsModuleToGrandparentPom_whenTargetIsJar() throws Exception {
        writePomAt(tmp, pomPackagingPom("io.techchamps", "casus", "0.0.1-SNAPSHOT"));

        Path appDir = tmp.resolve("restbackend");
        Files.createDirectories(appDir);
        writePomAt(appDir, """
                <project>
                  <parent>
                    <groupId>io.techchamps</groupId>
                    <artifactId>casus</artifactId>
                    <version>0.0.1-SNAPSHOT</version>
                    <relativePath>../pom.xml</relativePath>
                  </parent>
                  <artifactId>restbackend</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                </project>
                """);

        writer.write(appDir, singleTest());

        // "rest-assured-tests" (no subdirectory prefix, it's a direct child)
        assertThat(Files.readString(tmp.resolve("pom.xml")))
                .contains("<module>rest-assured-tests</module>");
    }

    // ------------------------------------------------------------------
    // addModuleToParentPom — idempotent
    // ------------------------------------------------------------------

    @Test
    void addModuleToParentPom_idempotent() throws Exception {
        writePomAt(tmp, """
                <project>
                  <packaging>pom</packaging>
                  <modules>
                    <module>rest-assured-tests</module>
                  </modules>
                </project>
                """);

        writer.addModuleToParentPom(tmp.resolve("pom.xml"), "rest-assured-tests");

        long count = Files.readString(tmp.resolve("pom.xml"))
                .lines().filter(l -> l.contains("<module>rest-assured-tests</module>")).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void addModuleToParentPom_insertsIntoExistingModulesSection() throws Exception {
        writePomAt(tmp, """
                <project>
                  <packaging>pom</packaging>
                  <modules>
                    <module>other-module</module>
                  </modules>
                </project>
                """);

        writer.addModuleToParentPom(tmp.resolve("pom.xml"), "rest-assured-tests");

        String content = Files.readString(tmp.resolve("pom.xml"));
        assertThat(content).contains("<module>rest-assured-tests</module>");
        assertThat(content).contains("<module>other-module</module>");
    }

    @Test
    void addModuleToParentPom_createsModulesSectionIfAbsent() throws Exception {
        writePomAt(tmp, """
                <project>
                  <packaging>pom</packaging>
                  <groupId>com.example</groupId>
                  <artifactId>myapp</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        writer.addModuleToParentPom(tmp.resolve("pom.xml"), "rest-assured-tests");

        String content = Files.readString(tmp.resolve("pom.xml"));
        assertThat(content).contains("<modules>");
        assertThat(content).contains("<module>rest-assured-tests</module>");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void writePomAt(Path dir, String content) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), content);
    }

    private void prepareStandaloneProject(Path dir) throws Exception {
        writePomAt(dir, """
                <project>
                  <packaging>pom</packaging>
                  <groupId>com.example</groupId>
                  <artifactId>myapp</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.source>17</maven.compiler.source>
                  </properties>
                </project>
                """);
    }

    private String pomPackagingPom(String groupId, String artifactId, String version) {
        return """
                <project>
                  <packaging>pom</packaging>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(groupId, artifactId, version);
    }

    private List<GeneratedTest> singleTest() {
        return List.of(new GeneratedTest(
                "UserController", "UserControllerIT", "com.example.controller",
                "package com.example.controller;\nclass UserControllerIT {}",
                List.of(), 100, 200, "mock"));
    }
}
