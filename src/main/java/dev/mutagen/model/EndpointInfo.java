package dev.mutagen.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Represents a single API endpoint extracted from a Spring Boot controller. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class EndpointInfo {

    private String controllerClass;
    private String controllerFile;
    private String methodName;
    private HttpMethod httpMethod;
    private String path;
    private String fullPath;
    private String responseType;
    private Map<String, String> responseFields = new LinkedHashMap<>();
    private List<ParamInfo> pathParams   = new ArrayList<>();
    private List<ParamInfo> queryParams  = new ArrayList<>();
    private List<ParamInfo> headerParams = new ArrayList<>();
    private RequestBodyInfo requestBody;
    private boolean requiresAuth;
    private String requiredRole; // e.g. "ADMIN", "USER" — parsed from @PreAuthorize
    private List<String> validationAnnotations = new ArrayList<>();
    private List<String> produces = new ArrayList<>();
    private List<String> consumes = new ArrayList<>();
    /** First ~25 lines of the controller method source code (declaration + body). */
    private String methodSource;

    public String getControllerClass()                        { return controllerClass; }
    public void setControllerClass(String controllerClass)    { this.controllerClass = controllerClass; }
    public String getControllerFile()                         { return controllerFile; }
    public void setControllerFile(String controllerFile)      { this.controllerFile = controllerFile; }
    public String getMethodName()                             { return methodName; }
    public void setMethodName(String methodName)              { this.methodName = methodName; }
    public HttpMethod getHttpMethod()                         { return httpMethod; }
    public void setHttpMethod(HttpMethod httpMethod)          { this.httpMethod = httpMethod; }
    public String getPath()                                   { return path; }
    public void setPath(String path)                          { this.path = path; }
    public String getFullPath()                               { return fullPath; }
    public void setFullPath(String fullPath)                  { this.fullPath = fullPath; }
    public String getResponseType()                           { return responseType; }
    public void setResponseType(String responseType)          { this.responseType = responseType; }
    public Map<String, String> getResponseFields()            { return responseFields; }
    public void setResponseFields(Map<String, String> f)      { this.responseFields = f; }
    public List<ParamInfo> getPathParams()                    { return pathParams; }
    public void setPathParams(List<ParamInfo> pathParams)     { this.pathParams = pathParams; }
    public List<ParamInfo> getQueryParams()                   { return queryParams; }
    public void setQueryParams(List<ParamInfo> queryParams)   { this.queryParams = queryParams; }
    public List<ParamInfo> getHeaderParams()                  { return headerParams; }
    public void setHeaderParams(List<ParamInfo> headerParams) { this.headerParams = headerParams; }
    public RequestBodyInfo getRequestBody()                   { return requestBody; }
    public void setRequestBody(RequestBodyInfo requestBody)   { this.requestBody = requestBody; }
    public boolean isRequiresAuth()                           { return requiresAuth; }
    public void setRequiresAuth(boolean requiresAuth)         { this.requiresAuth = requiresAuth; }
    public String getRequiredRole()                           { return requiredRole; }
    public void setRequiredRole(String requiredRole)          { this.requiredRole = requiredRole; }
    public List<String> getValidationAnnotations()            { return validationAnnotations; }
    public void setValidationAnnotations(List<String> v)      { this.validationAnnotations = v; }
    public List<String> getProduces()                         { return produces; }
    public void setProduces(List<String> produces)            { this.produces = produces; }
    public List<String> getConsumes()                         { return consumes; }
    public void setConsumes(List<String> consumes)            { this.consumes = consumes; }
    public String getMethodSource()                           { return methodSource; }
    public void setMethodSource(String methodSource)          { this.methodSource = methodSource; }

    @Override
    public String toString() {
        return httpMethod + " " + fullPath + " (" + controllerClass + "#" + methodName + ")";
    }
}
