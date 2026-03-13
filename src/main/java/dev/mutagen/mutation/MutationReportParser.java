package dev.mutagen.mutation;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import dev.mutagen.mutation.model.MutationReport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parses Pitest's {@code mutations.xml} into a {@link MutationReport}. */
public class MutationReportParser {

    private final XmlMapper xmlMapper = new XmlMapper();

    public MutationReport parse(Path xmlPath) throws IOException {
        try (InputStream is = Files.newInputStream(xmlPath)) {
            return xmlMapper.readValue(is, MutationReport.class);
        }
    }
}
