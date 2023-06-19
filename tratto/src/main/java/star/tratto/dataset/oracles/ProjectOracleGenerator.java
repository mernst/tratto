package star.tratto.dataset.oracles;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import org.javatuples.Pair;
import org.javatuples.Quartet;
import star.tratto.data.OracleDatapoint;
import star.tratto.dataset.oracles.JDoctorCondition.Operation;
import star.tratto.dataset.oracles.JDoctorCondition.PreCondition;
import star.tratto.dataset.oracles.JDoctorCondition.PostCondition;
import star.tratto.dataset.oracles.JDoctorCondition.ThrowsCondition;
import star.tratto.exceptions.JPClassNotFoundException;
import star.tratto.util.javaparser.DatasetUtils;
import star.tratto.util.javaparser.JDoctorUtils;

import java.util.*;

/**
 * ProjectOracleGenerator generates all oracle data points of a project using
 * a list of JDoctor conditions associated with its classes and methods.
 */
public class ProjectOracleGenerator {
    // generator fields.
    /** Provides unique identifiers of the generated oracle data points. */
    private int idCounter;
    /**
     * The last identifier used to generate a data point. The checkpoint is
     * used to calculate the total number of oracles generated between projects.
     */
    private int checkpoint;
    // project fields.
    private Project project;
    private List<JDoctorCondition> jDoctorConditions;
    private List<Pair<String, String>> tokensProjectClasses;
    private List<Quartet<String, String, String, String>> tokensProjectClassesMethods;
    private List<Quartet<String, String, String, String>> tokensProjectClassesAttributes;

    /**
     * Creates a new instance of ProjectOracleGenerator.
     */
    public ProjectOracleGenerator() {
        this.idCounter = 0;
        this.checkpoint = 0;
    }

    /**
     * This method sets up a Java project for analysis. It creates a reference
     * to the Project and associated JDoctor conditions, and creates an
     * instance of JavaParserUtils.
     * @param project the Java project under analysis
     * @param jDocConditions a list of conditions generated by JDoctor from the project
     */
    public void loadProject(
            Project project,
            List<JDoctorCondition> jDocConditions
    ) {
        this.project = project;
        this.jDoctorConditions = jDocConditions;
        this.tokensProjectClasses = DatasetUtils.getTokensProjectClasses(this.project.getSrcPath());
        this.tokensProjectClassesMethods = DatasetUtils.getTokensProjectClassesNonPrivateStaticNonVoidMethods(this.project.getSrcPath());
        this.tokensProjectClassesAttributes = DatasetUtils.getTokensProjectClassesNonPrivateStaticAttributes(this.project.getSrcPath());
    }

    /**
     * This method generates all oracle data points for the loaded Java
     * project from a list of JDoctor conditions.
     * @return the list of oracle data points {@link OracleDatapoint} produced
     * for the loader project, from the JDoctor conditions
     */
    public List<OracleDatapoint> generate() {
        List<OracleDatapoint> oracleDPs = new ArrayList<>();
        // Generate an OracleDatapoint for each JDoctor condition.
        for (JDoctorCondition jDoctorCondition : this.jDoctorConditions) {
            Operation operation = jDoctorCondition.getOperation();
            // Add all ThrowsCondition oracles to dataset.
            List<ThrowsCondition> throwsConditions = jDoctorCondition.getThrowsConditions();
            for (ThrowsCondition condition : throwsConditions) {
                oracleDPs.add(getNextDatapoint(operation, condition));
            }
            // Add all PreCondition oracles to dataset.
            List<PreCondition> preConditions = jDoctorCondition.getPreCondition();
            for (PreCondition condition : preConditions) {
                oracleDPs.add(getNextDatapoint(operation, condition));
            }
            // Add all PostCondition oracles to dataset.
            List<PostCondition> postConditions = jDoctorCondition.getPostConditions();
            if (postConditions.size() > 0) {
                oracleDPs.add(getNextDatapoint(operation, postConditions));
            }
        }
        System.out.printf("Processed %s conditions.%n", this.idCounter - this.checkpoint);
        this.checkpoint = this.idCounter;
        return oracleDPs;
    }

    private OracleDatapoint getNextDatapoint(Operation operation, Object condition) {
        OracleDatapointBuilder builder = new OracleDatapointBuilder();
        // get basic information of operation.
        String sourcePath = this.project.getSrcPath();
        String projectName = this.project.getProjectName();
        String packageName = DatasetUtils.getOperationPackageName(operation);
        String className = DatasetUtils.getOperationClassName(operation);
        String callableName = DatasetUtils.getOperationCallableName(operation);
        List<String> parameterTypes = JDoctorUtils.convertJDoctorConditionTypeNames2JavaParserTypeNames(operation.getParameterTypes());
        // get CompilationUnit of operation class.
        Optional<CompilationUnit> cuOptional = DatasetUtils.getClassCompilationUnit(operation, sourcePath);
        if (cuOptional.isEmpty()) {
          return builder.build();
        }
        CompilationUnit cu = cuOptional.get();
        // get TypeDeclaration of class in CompilationUnit.
        TypeDeclaration<?> jpClass = DatasetUtils.getTypeDeclaration(cu, className);
        assert jpClass != null;
        // get CallableDeclaration of method in TypeDeclaration.
        CallableDeclaration<?> jpCallable = DatasetUtils.getCallableDeclaration(jpClass, callableName, parameterTypes);
        assert jpCallable != null;
        // set data point information.
        builder.setConditionInfo(condition);
        System.out.println(builder.copy().getOracle());
        builder.setProjectName(projectName);
        builder.setClassSourceCode(jpClass.toString());
        builder.setPackageName(packageName);
        builder.setClassName(className);
        builder.setClassJavadoc(DatasetUtils.getClassJavadoc(jpClass));
        builder.setTokensProjectClasses(this.tokensProjectClasses);
        builder.setTokensProjectClassesNonPrivateStaticNonVoidMethods(this.tokensProjectClassesMethods);
        builder.setTokensProjectClassesNonPrivateStaticAttributes(this.tokensProjectClassesAttributes);
        builder.setMethodSourceCode(DatasetUtils.getCallableSourceCode(jpCallable));
        builder.setMethodJavadoc(DatasetUtils.getCallableJavadoc(jpCallable));
        builder.setTokensMethodJavadocValues(DatasetUtils.getValuesFromJavadoc(builder.copy().getMethodJavadoc()));
        builder.setTokensMethodArguments(DatasetUtils.getTokensMethodArguments(jpClass, jpCallable));
        // get method variable and oracle variable tokens.
        try {
            builder.setTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(
                    DatasetUtils.getTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(
                            jpClass,
                            jpCallable
                    )
            );
            builder.setTokensMethodVariablesNonPrivateNonStaticAttributes(
                    DatasetUtils.getTokensMethodVariablesNonPrivateNonStaticAttributes(
                            jpClass,
                            jpCallable
                    )
            );
            builder.setTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(
                    DatasetUtils.getTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(
                            jpClass,
                            jpCallable,
                            builder.copy().getTokensMethodArguments(),
                            builder.copy().getOracle()
                    )
            );
            builder.setTokensOracleVariablesNonPrivateNonStaticAttributes(
                    DatasetUtils.getTokensOracleVariablesNonPrivateNonStaticAttributes(
                            jpClass,
                            jpCallable,
                            builder.copy().getTokensMethodArguments(),
                            builder.copy().getOracle()
                    )
            );
        } catch (JPClassNotFoundException | UnsolvedSymbolException e) {
            e.printStackTrace();
        }
        // return new datapoint.
        builder.setId(this.idCounter);
        this.idCounter++;
        return builder.build();
    }
}
