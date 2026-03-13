package dev.mutagen.auth;

/**
 * Verified auth setup discovered by {@link AuthProber} against the running backend.
 *
 * <p>Body templates use {@code UNIQUE_USER} and {@code UNIQUE_EMAIL} as placeholders for
 * the per-run unique values that the LLM must replace with its {@code uniqueUser} /
 * {@code uniqueEmail} variables.
 *
 * @param signupPath          path used for registration (null if no signup found / needed)
 * @param signupBodyTemplate  JSON template for signup body (null if no signup)
 * @param signinPath          path used for sign-in
 * @param signinBodyTemplate  JSON template for sign-in body
 * @param tokenField          response field that holds the JWT (e.g. {@code token})
 */
public record AuthSetupInfo(
        String signupPath,
        String signupBodyTemplate,
        String signinPath,
        String signinBodyTemplate,
        String tokenField
) {
    /** Returns true when a working auth flow was discovered. */
    public boolean isAvailable() {
        return signinPath != null && tokenField != null;
    }

    /**
     * Returns a ready-to-use Java code block for a {@code @BeforeAll setUpAuth()} method body.
     * The caller is responsible for wrapping it in the method declaration.
     */
    public String toJavaBeforeAllBody() {
        boolean needsUnique = (signupBodyTemplate != null && signupBodyTemplate.contains("UNIQUE"))
                || (signinBodyTemplate != null && signinBodyTemplate.contains("UNIQUE"));

        StringBuilder sb = new StringBuilder();
        if (needsUnique) {
            sb.append("        String uniqueUser = \"testuser_\" + java.util.UUID.randomUUID().toString().substring(0, 8);\n");
            sb.append("        String uniqueEmail = uniqueUser + \"@example.com\";\n");
        }

        if (signupPath != null && signupBodyTemplate != null) {
            sb.append("\n");
            sb.append("        given().contentType(ContentType.JSON)\n");
            sb.append("               .body(").append(templateToJavaString(signupBodyTemplate)).append(")\n");
            sb.append("               .post(\"").append(signupPath).append("\");\n");
        }

        sb.append("\n");
        sb.append("        token = given().contentType(ContentType.JSON)\n");
        sb.append("                       .body(").append(templateToJavaString(signinBodyTemplate)).append(")\n");
        sb.append("                       .post(\"").append(signinPath).append("\")\n");
        sb.append("                       .then().statusCode(200)\n");
        sb.append("                       .extract().path(\"").append(tokenField).append("\");\n");

        return sb.toString();
    }

    /**
     * Converts a JSON template string (with UNIQUE_USER / UNIQUE_EMAIL placeholders) to a
     * Java string concatenation expression suitable for use in generated source code.
     *
     * <p>Example input:  {@code {"username":"UNIQUE_USER","email":"UNIQUE_EMAIL","password":"Test1234!"}}
     * <p>Example output: {@code "{\"username\":\"" + uniqueUser + "\",\"email\":\"" + uniqueEmail + "\",\"password\":\"Test1234!\"}"}
     */
    static String templateToJavaString(String template) {
        // Walk through the template, splitting on UNIQUE_USER / UNIQUE_EMAIL placeholders,
        // and build a proper Java string-concatenation expression.
        StringBuilder sb = new StringBuilder();
        String remaining = template;
        boolean firstSegment = true;

        while (!remaining.isEmpty()) {
            int userIdx  = remaining.indexOf("UNIQUE_USER");
            int emailIdx = remaining.indexOf("UNIQUE_EMAIL");

            if (userIdx == -1 && emailIdx == -1) {
                // No more placeholders — append remaining literal
                appendLiteral(sb, remaining, firstSegment);
                break;
            }

            String varName;
            int nextIdx;
            int placeholderLen;
            if (emailIdx == -1 || (userIdx != -1 && userIdx < emailIdx)) {
                nextIdx        = userIdx;
                varName        = "uniqueUser";
                placeholderLen = "UNIQUE_USER".length();
            } else {
                nextIdx        = emailIdx;
                varName        = "uniqueEmail";
                placeholderLen = "UNIQUE_EMAIL".length();
            }

            String before = remaining.substring(0, nextIdx);
            appendLiteral(sb, before, firstSegment);
            firstSegment = false;
            sb.append(" + ").append(varName);
            remaining = remaining.substring(nextIdx + placeholderLen);
        }

        if (firstSegment) {
            // Template had no placeholders at all — just wrap in quotes
            sb.append("\"").append(template.replace("\"", "\\\"")).append("\"");
        }
        return sb.toString();
    }

    /** Appends {@code + "<escaped literal>"} (or just {@code "<escaped>"} when first). */
    private static void appendLiteral(StringBuilder sb, String literal, boolean first) {
        String escaped = literal.replace("\\", "\\\\").replace("\"", "\\\"");
        if (first) {
            sb.append("\"").append(escaped).append("\"");
        } else {
            sb.append(" + \"").append(escaped).append("\"");
        }
    }
}
