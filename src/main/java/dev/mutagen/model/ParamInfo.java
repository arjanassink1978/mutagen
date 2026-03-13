package dev.mutagen.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/** A single parameter of an endpoint (path, query, or header). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ParamInfo {

    private String name;
    private String javaType;
    private boolean required = true;
    private String defaultValue;
    private List<String> constraints = new ArrayList<>();

    public ParamInfo() {}

    public ParamInfo(String name, String javaType) {
        this.name = name;
        this.javaType = javaType;
    }

    public String getName()                              { return name; }
    public void setName(String name)                     { this.name = name; }
    public String getJavaType()                          { return javaType; }
    public void setJavaType(String javaType)             { this.javaType = javaType; }
    public boolean isRequired()                          { return required; }
    public void setRequired(boolean required)            { this.required = required; }
    public String getDefaultValue()                      { return defaultValue; }
    public void setDefaultValue(String defaultValue)     { this.defaultValue = defaultValue; }
    public List<String> getConstraints()                 { return constraints; }
    public void setConstraints(List<String> constraints) { this.constraints = constraints; }
}
