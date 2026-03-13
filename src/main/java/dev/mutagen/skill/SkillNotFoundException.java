package dev.mutagen.skill;

public class SkillNotFoundException extends RuntimeException {
    public SkillNotFoundException(String message) { super(message); }
    public SkillNotFoundException(String message, Throwable cause) { super(message, cause); }
}
