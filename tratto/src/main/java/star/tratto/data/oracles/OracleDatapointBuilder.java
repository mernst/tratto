package star.tratto.data.oracles;

import star.tratto.data.OracleDatapoint;
import star.tratto.data.OracleType;
import star.tratto.data.records.AttributeTokens;
import star.tratto.data.records.ClassTokens;
import star.tratto.data.records.JDoctorCondition.Guard;
import star.tratto.data.records.JDoctorCondition.PostCondition;
import star.tratto.data.records.JDoctorCondition.PreCondition;
import star.tratto.data.records.JDoctorCondition.Property;
import star.tratto.data.records.JDoctorCondition.ThrowsCondition;
import star.tratto.data.records.ValueTokens;
import star.tratto.data.records.MethodArgumentTokens;
import star.tratto.data.records.MethodTokens;
import star.tratto.data.TrattoPath;
import star.tratto.util.FileUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.plumelib.util.CollectionsPlume.mapList;

/**
 * A builder class for {@link OracleDatapoint}.
 */
public class OracleDatapointBuilder {
    /**
     * The OracleDatapoint being built. {@link OracleDatapointBuilder#build()}
     * returns a copy.
     */
    private OracleDatapoint datapoint;

    public OracleDatapointBuilder() {
        this.reset();
    }

    /**
     * Resets all fields in the current datapoint {@link OracleDatapoint}.
     * Sets default values.
     */
    public void reset() {
        this.datapoint = new OracleDatapoint();
        // set default oracle datapoint values.
        this.setDefaultGrammarTokens();
        this.setDefaultGeneralValues();
        this.setTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(new ArrayList<>());
        this.setTokensMethodVariablesNonPrivateNonStaticAttributes(new ArrayList<>());
        this.setTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(new ArrayList<>());
        this.setTokensOracleVariablesNonPrivateNonStaticAttributes(new ArrayList<>());
    }

    public void resetWithDefaults() {
        this.reset();
        this.setId(0);
        this.setOracle("");
        this.setProjectName("");
    }

    public void reset(FeatureLevel level, boolean defaults) {
        OracleDatapoint oracleDP = this.copy();
        if (defaults) {
            this.resetWithDefaults();
        } else {
            this.reset();
        }
        // copy fields based on `level`
        switch (level) {
            case ORACLE:
                this.setTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(oracleDP.getTokensOracleVariablesNonPrivateNonStaticNonVoidMethods());
                this.setTokensOracleVariablesNonPrivateNonStaticAttributes(oracleDP.getTokensOracleVariablesNonPrivateNonStaticAttributes());
            case METHOD:
                this.setMethodSourceCode(oracleDP.getMethodSourceCode());
                this.setMethodJavadoc(oracleDP.getMethodJavadoc());
                this.setTokensMethodJavadocValues(oracleDP.getTokensMethodJavadocValues());
                this.setTokensMethodArguments(oracleDP.getTokensMethodArguments());
                this.setTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(oracleDP.getTokensMethodVariablesNonPrivateNonStaticNonVoidMethods());
                this.setTokensMethodVariablesNonPrivateNonStaticAttributes(oracleDP.getTokensMethodVariablesNonPrivateNonStaticAttributes());
            case CLASS:
                this.setClassName(oracleDP.getClassName());
                this.setClassSourceCode(oracleDP.getClassSourceCode());
                this.setClassJavadoc(oracleDP.getClassJavadoc());
                this.setPackageName(oracleDP.getPackageName());
            case PROJECT:
                this.setTokensProjectClasses(oracleDP.getTokensProjectClasses());
                this.setTokensProjectClassesNonPrivateStaticNonVoidMethods(oracleDP.getTokensProjectClassesNonPrivateStaticNonVoidMethods());
                this.setTokensProjectClassesNonPrivateStaticAttributes(oracleDP.getTokensProjectClassesNonPrivateStaticAttributes());
                this.setProjectName(oracleDP.getProjectName());
            case DEFAULT:
                break;
        }
    }

    /**
     * Sets default general tokens for symbolic grammar. This represents
     * tokens that are available to every project (e.g. "+", ";", etc.). See
     * "data/repos/tokens_grammar.json" for the full list. Used for the XText
     * grammar.
     */
    private void setDefaultGrammarTokens() {
        Path tokensGrammarPath = TrattoPath.TOKENS_GRAMMAR.getPath();
        List<String> tokenGrammar = FileUtils.readJSONList(tokensGrammarPath, String.class);
        this.setTokensGeneralGrammar(tokenGrammar);
    }

    /**
     * Sets default global values for commonly observed tokens and their
     * corresponding types (e.g. [1, int], [0.0, double], etc.). Used for the
     * XText grammar.
     */
    private void setDefaultGeneralValues() {
        Path tokensGeneralValuesPath = TrattoPath.TOKENS_GENERAL_VALUES.getPath();
        List<ValueTokens> tokenGeneralValues = mapList(ValueTokens::new, FileUtils.readJSONList(tokensGeneralValuesPath, List.class));
        this.setTokensGeneralValuesGlobalDictionary(tokenGeneralValues);
    }

    private void setThrowsConditionInfo(ThrowsCondition condition) {
        this.setOracleType(OracleType.EXCEPT_POST);
        this.setJavadocTag(condition.description());
        this.setOracle((condition.guard().condition() + ";").replaceAll("receiverObjectID", "this"));
    }

    private void setPreConditionInfo(PreCondition condition) {
        this.setOracleType(OracleType.PRE);
        this.setJavadocTag(condition.description());
        this.setOracle((condition.guard().condition() + ";").replaceAll("receiverObjectID", "this"));
    }

    /**
     * Gets the oracle of a non-exceptional JDoctor post-condition. The oracle
     * is represented by a ternary statement. If the post-condition does not
     * have an alternative "else" final state, then the false result of the
     * ternary expression is "true".
     *
     * @param conditionList a list of JDoctor post-conditions
     * @return the oracle corresponding to the post-condition
     * @throws IllegalArgumentException if the list of post conditions has
     * less than one or more than two conditions
     */
    private String getPostConditionOracle(List<PostCondition> conditionList) {
        PostCondition mainCondition = conditionList.get(0);
        String mainTag = mainCondition.description();
        Guard mainGuard = mainCondition.guard();
        Property mainProperty = mainCondition.property();
        StringBuilder sb = new StringBuilder();
        // get true result
        sb.append(mainGuard.condition());
        sb.append(" ? ");
        sb.append(mainProperty.condition());
        sb.append(" : ");
        // get false result
        switch (conditionList.size()) {
            case 2 -> {
                PostCondition altCondition = conditionList.get(1);
                String altTag = altCondition.description();
                if (!mainTag.equals(altTag)) throw new AssertionError();
                Property altProperty = altCondition.property();
                sb.append(altProperty.condition());
            }
            case 1 -> sb.append("true");
            default -> throw new IllegalArgumentException(
                    "Expected condition list to have 1 or 2 conditions, but got " + conditionList.size()
            );
        }
        sb.append(";");
        return sb.toString().replaceAll("receiverObjectID", "this");
    }

    private void setPostConditionInfo(List<PostCondition> conditionList) {
        if (conditionList.size() > 2 || conditionList.isEmpty()) {
            throw new IllegalArgumentException("Expected condition list to have 1 or 2 conditions, but got " + conditionList.size());
        }
        this.setOracleType(OracleType.NORMAL_POST);
        this.setJavadocTag(conditionList.get(0).description());
        this.setOracle(getPostConditionOracle(conditionList));
    }

    /**
     * Sets the oracle type, Javadoc tag, and oracle from a JDoctor condition.
     *
     * @param condition a JDoctor condition (does not use a shared supertype
     *                  to avoid compatibility issues with reading the JSON)
     * @throws IllegalArgumentException if the condition is not a
     * ThrowsCondition, PreCondition, or List of PostConditions
     */
    public void setConditionInfo(Object condition) {
        if (condition instanceof ThrowsCondition) {
            this.setThrowsConditionInfo((ThrowsCondition) condition);
        } else if (condition instanceof PreCondition) {
            this.setPreConditionInfo((PreCondition) condition);
        } else if (condition instanceof List<?>) {
            // use stream to avoid compiler warnings when casting.
            List<PostCondition> conditionList = ((List<?>) condition)
                    .stream()
                    .map(e -> (PostCondition) e)
                    .collect(Collectors.toList());
            if (conditionList.size() > 0) {
                this.setPostConditionInfo(conditionList);
            }
        } else {
            throw new IllegalArgumentException(
                    "Unexpected JDoctor condition " + condition.getClass() + " " + condition
            );
        }
    }

    public void setId(Integer id) {
        this.datapoint.setId(id);
    }

    public void setOracle(String oracle) {
        this.datapoint.setOracle(oracle);
    }

    public void setOracleType(OracleType oracleType) {
        this.datapoint.setOracleType(oracleType);
    }

    public void setProjectName(String projectName) {
        this.datapoint.setProjectName(projectName);
    }

    public void setPackageName(String packageName) {
        this.datapoint.setPackageName(packageName);
    }

    public void setClassName(String className) {
        this.datapoint.setClassName(className);
    }

    public void setJavadocTag(String javadocTag) {
        this.datapoint.setJavadocTag(javadocTag);
    }

    public void setMethodJavadoc(String methodJavadoc) {
        this.datapoint.setMethodJavadoc(methodJavadoc);
    }

    public void setMethodSourceCode(String methodSourceCode) {
        this.datapoint.setMethodSourceCode(methodSourceCode);
    }

    public void setClassJavadoc(String classJavadoc) {
        this.datapoint.setClassJavadoc(classJavadoc);
    }

    public void setClassSourceCode(String classSourceCode) {
        this.datapoint.setClassSourceCode(classSourceCode);
    }

    public void setTokensGeneralGrammar(List<String> tokensGeneralGrammar) {
        this.datapoint.setTokensGeneralGrammar(tokensGeneralGrammar);
    }

    public void setTokensGeneralValuesGlobalDictionary(List<ValueTokens> tokensGeneralValuesGlobalDictionary) {
        this.datapoint.setTokensGeneralValuesGlobalDictionary(tokensGeneralValuesGlobalDictionary);
    }

    public void setTokensProjectClasses(List<ClassTokens> tokensProjectClasses) {
        this.datapoint.setTokensProjectClasses(tokensProjectClasses);
    }

    public void setTokensProjectClassesNonPrivateStaticNonVoidMethods(List<MethodTokens> tokensProjectClassesNonPrivateStaticNonVoidMethods) {
        this.datapoint.setTokensProjectClassesNonPrivateStaticNonVoidMethods(tokensProjectClassesNonPrivateStaticNonVoidMethods);
    }

    public void setTokensProjectClassesNonPrivateStaticAttributes(List<AttributeTokens> tokensProjectClassesNonPrivateStaticAttributes) {
        this.datapoint.setTokensProjectClassesNonPrivateStaticAttributes(tokensProjectClassesNonPrivateStaticAttributes);
    }

    public void setTokensMethodJavadocValues(List<ValueTokens> tokensMethodJavadocValues) {
        this.datapoint.setTokensMethodJavadocValues(tokensMethodJavadocValues);
    }

    public void setTokensMethodArguments(List<MethodArgumentTokens> tokensMethodArguments) {
        this.datapoint.setTokensMethodArguments(tokensMethodArguments);
    }

    public void setTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(List<MethodTokens> tokensMethodVariablesNonPrivateNonStaticNonVoidMethods) {
        this.datapoint.setTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(tokensMethodVariablesNonPrivateNonStaticNonVoidMethods);
    }

    public void setTokensMethodVariablesNonPrivateNonStaticAttributes(List<AttributeTokens> tokensMethodVariablesNonPrivateNonStaticAttributes) {
        this.datapoint.setTokensMethodVariablesNonPrivateNonStaticAttributes(tokensMethodVariablesNonPrivateNonStaticAttributes);
    }

    public void setTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(List<MethodTokens> tokensOracleVariablesNonPrivateNonStaticNonVoidMethods) {
        this.datapoint.setTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(tokensOracleVariablesNonPrivateNonStaticNonVoidMethods);
    }

    public void setTokensOracleVariablesNonPrivateNonStaticAttributes(List<AttributeTokens> tokensOracleVariablesNonPrivateNonStaticAttributes) {
        this.datapoint.setTokensOracleVariablesNonPrivateNonStaticAttributes(tokensOracleVariablesNonPrivateNonStaticAttributes);
    }

    /**
     * Creates a new copy of the current OracleDatapoint being built by this
     * OracleDatapointBuilder.
     *
     * @return a new OracleDatapoint with all fields copied from this
     */
    public OracleDatapoint copy() {
        return new OracleDatapoint(
                this.datapoint.getId(),
                this.datapoint.getOracle(),
                this.datapoint.getOracleType(),
                this.datapoint.getProjectName(),
                this.datapoint.getPackageName(),
                this.datapoint.getClassName(),
                this.datapoint.getJavadocTag(),
                this.datapoint.getMethodJavadoc(),
                this.datapoint.getMethodSourceCode(),
                this.datapoint.getClassJavadoc(),
                this.datapoint.getClassSourceCode(),
                this.datapoint.getTokensGeneralGrammar(),
                this.datapoint.getTokensGeneralValuesGlobalDictionary(),
                this.datapoint.getTokensProjectClasses(),
                this.datapoint.getTokensProjectClassesNonPrivateStaticNonVoidMethods(),
                this.datapoint.getTokensProjectClassesNonPrivateStaticAttributes(),
                this.datapoint.getTokensMethodJavadocValues(),
                this.datapoint.getTokensMethodArguments(),
                this.datapoint.getTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(),
                this.datapoint.getTokensMethodVariablesNonPrivateNonStaticAttributes(),
                this.datapoint.getTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(),
                this.datapoint.getTokensOracleVariablesNonPrivateNonStaticAttributes()
        );
    }

    /**
     * Returns a new datapoint, but carries over specific columns from the
     * current datapoint based on the given feature level. The ordering is
     * hierarchical, such that each level keeps columns from the previous
     * level (e.g. "project" also keeps "default" features).
     *
     * @param level the depth of the reset
     * @param defaults set to false to reset the oracle normally ({@link #reset} method).
     *                 Set to true to keep default values for some properties
     *                 ({@link #resetWithDefaults} method).
     * @return a new datapoint {@link OracleDatapoint}
     */
    public OracleDatapoint build(FeatureLevel level, boolean defaults) {
        OracleDatapoint oracleDP = this.copy();
        reset(level, defaults);
        return oracleDP;
    }

    /**
     * Returns a new datapoint {@link OracleDatapoint}. Resets builder.
     * @return a new datapoint {@link OracleDatapoint}. Resets builder.
     */
    public OracleDatapoint build() {
        OracleDatapoint oracleDP = this.copy();
        this.reset();
        return oracleDP;
    }
}
