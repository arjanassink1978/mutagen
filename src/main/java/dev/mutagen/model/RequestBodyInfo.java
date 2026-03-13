package dev.mutagen.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/** Describes the request body of an endpoint, including resolved DTO fields. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class RequestBodyInfo {

    private String javaType;
    private String qualifiedJavaType;
    private boolean required = true;
    private boolean validated;
    private Map<String, String> fields = new LinkedHashMap<>();

    public String getJavaType()                                  { return javaType; }
    public void setJavaType(String javaType)                     { this.javaType = javaType; }
    public String getQualifiedJavaType()                         { return qualifiedJavaType; }
    public void setQualifiedJavaType(String qualifiedJavaType)   { this.qualifiedJavaType = qualifiedJavaType; }
    public boolean isRequired()                                  { return required; }
    public void setRequired(boolean required)                    { this.required = required; }
    public boolean isValidated()                                 { return validated; }
    public void setValidated(boolean validated)                  { this.validated = validated; }
    public Map<String, String> getFields()                       { return fields; }
    public void setFields(Map<String, String> fields)            { this.fields = fields; }
}
