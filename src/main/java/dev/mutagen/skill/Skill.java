package dev.mutagen.skill;

/** A loaded skill — a markdown file containing prompt instructions for a specific generation task. */
public class Skill {

    public enum Type {
        RESTASSURED_TEST,
        MUTATION_GAP_ANALYSIS,
        TEST_IMPROVEMENT,
        CUSTOM
    }

    private final Type type;
    private final String name;
    private final String content;
    private final String source;

    public Skill(Type type, String name, String content, String source) {
        this.type    = type;
        this.name    = name;
        this.content = content;
        this.source  = source;
    }

    public Type getType()       { return type; }
    public String getName()     { return name; }
    public String getContent()  { return content; }
    public String getSource()   { return source; }

    @Override
    public String toString() {
        return "Skill[%s from %s]".formatted(name, source);
    }
}
