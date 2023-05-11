package star.tratto.dataset.oracles;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import star.tratto.dataset.oracles.JDoctorCondition.Operation;
import star.tratto.dataset.oracles.JDoctorCondition.PreCondition;
import star.tratto.dataset.oracles.JDoctorCondition.PostCondition;
import star.tratto.dataset.oracles.JDoctorCondition.ThrowsCondition;
import star.tratto.util.javaparser.DatasetUtils;
import star.tratto.util.javaparser.JavaParserUtils;

import java.util.*;

/**
 * ProjectOracleGenerator generates all oracle data points of a project using
 * a list of JDoctor conditions associated with its classes and methods.
 */
public class ProjectOracleGenerator {
    private int idCounter;
    private int checkpoint;
    private Project project;
    private List<JDoctorCondition> jDoctorConditions;

    /**
     * Create a new instance of ProjectOracleGenerator.
     * This constructor sets up:
     * <ul>
     *     <li>A counter ({@link #idCounter}) used to provide unique
     *     identifiers of the generated oracle data points.</li>
     *     <li>A checkpoint that memorizes the last identifier used to
     *     generate a data point within a given project. The checkpoint is
     *     used to calculate the total number of oracles generated between
     *     projects.</li>
     * </ul>
     *
     */
    public ProjectOracleGenerator() {
        this.idCounter = 0;
        this.checkpoint = 0;
    }

    /**
     * This method sets up a Java project for analysis. It creates a reference
     * to the Project and associated JDoctor conditions, and creates an
     * instance of JavaParserUtils.
     * @param project The Java project under analysis
     * @param jDocConditions A list of conditions generated by JDoctor from the project.
     */
    public void loadProject(
            Project project,
            List<JDoctorCondition> jDocConditions
    ) {
        this.project = project;
        this.jDoctorConditions = jDocConditions;
    }

    /**
     * This method generates all oracle data points for the loaded Java
     * project from a list of JDoctor conditions.
     * @return The list of oracle data points {@link OracleDatapoint} produced
     * for the loader project, from the JDoctor conditions.
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
            oracleDPs.add(getNextDatapoint(operation, postConditions));
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
        String className = DatasetUtils.getClassName(operation);
        CompilationUnit cu = DatasetUtils.getClassCompilationUnit(operation, sourcePath).get();
        TypeDeclaration<?> jpClass = DatasetUtils.getTypeDeclaration(cu, className);
        // set data point information.
        builder.setId(this.getId());
        builder.setConditionInfo(condition);
        builder.setProjectName(projectName);
        builder.setClassSourceCode(jpClass.toString());
        builder.setPackageName(DatasetUtils.getPackageName(operation));
        builder.setClassName(className);
        builder.setClassJavadoc(JavaParserUtils.getClassJavadoc(jpClass));
        builder.setTokensProjectClasses(DatasetUtils.getTokensProjectClasses(sourcePath));

        /*
            private String methodJavadoc;
            private String methodSourceCode;
            private List<Quartet<String, String, String, String>> tokensProjectClassesNonPrivateStaticNonVoidMethods; // <token, package, class, signature>
            private List<Quartet<String, String, String, String>> tokensProjectClassesNonPrivateStaticAttributes; // <token, package, class, declaration>
            private List<Pair<String, String>> tokensMethodJavadocValues; // <token, type>
            private List<Triplet<String, String, String>> tokensMethodArguments; // <token, package, class>
            private List<Quartet<String, String, String, String>> tokensMethodVariablesNonPrivateNonStaticNonVoidMethods; // <token, package, class, signature>
            private List<Quartet<String, String, String, String>> tokensMethodVariablesNonPrivateNonStaticAttributes; // <token, package, class, declaration>
            private List<Quartet<String, String, String, String>> tokensOracleVariablesNonPrivateNonStaticNonVoidMethods; // <token, package, class, signature>
            private List<Quartet<String, String, String, String>> tokensOracleVariablesNonPrivateNonStaticAttributes;
         */

        return builder.build();
    }

    /**
     * This method generates a unique identifier for an oracle data point.
     * @return the integer identifier of an oracle data point.
     */
    private int getId() {
        int id = this.idCounter;
        this.idCounter++;
        return id;
    }
}
