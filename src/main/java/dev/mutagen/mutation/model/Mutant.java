package dev.mutagen.mutation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** A single mutant entry from Pitest's {@code mutations.xml}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Mutant {

    public enum Status {
        SURVIVED, KILLED, NO_COVERAGE, TIMED_OUT;

        public static Status fromString(String s) {
            if (s == null) return KILLED;
            return switch (s.toUpperCase()) {
                case "SURVIVED"    -> SURVIVED;
                case "NO_COVERAGE" -> NO_COVERAGE;
                case "TIMED_OUT"   -> TIMED_OUT;
                default            -> KILLED;
            };
        }
    }

    @JacksonXmlProperty(isAttribute = true)
    private String status;

    private String sourceFile;
    private String mutatedClass;
    private String mutatedMethod;
    private int    lineNumber;
    private String mutator;
    private String description;

    // ---------------------------------------------------------------

    public boolean isSurvived() {
        return Status.SURVIVED == getStatusEnum();
    }

    public Status getStatusEnum() {
        return Status.fromString(status);
    }

    /** Short mutator name, e.g. {@code ConditionalsBoundaryMutator} from the full FQN. */
    public String getMutatorShortName() {
        if (mutator == null) return "";
        int dot = mutator.lastIndexOf('.');
        return dot >= 0 ? mutator.substring(dot + 1) : mutator;
    }

    // ---------------------------------------------------------------

    public String getStatus()             { return status; }
    public void   setStatus(String v)     { this.status = v; }
    public String getSourceFile()         { return sourceFile; }
    public void   setSourceFile(String v) { this.sourceFile = v; }
    public String getMutatedClass()       { return mutatedClass; }
    public void   setMutatedClass(String v){ this.mutatedClass = v; }
    public String getMutatedMethod()      { return mutatedMethod; }
    public void   setMutatedMethod(String v){ this.mutatedMethod = v; }
    public int    getLineNumber()         { return lineNumber; }
    public void   setLineNumber(int v)    { this.lineNumber = v; }
    public String getMutator()            { return mutator; }
    public void   setMutator(String v)    { this.mutator = v; }
    public String getDescription()        { return description; }
    public void   setDescription(String v){ this.description = v; }

    @Override
    public String toString() {
        return "[%s] %s#%s:%d — %s".formatted(
                status, mutatedClass, mutatedMethod, lineNumber, description);
    }
}
