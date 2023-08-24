package star.tratto.util.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedWildcard;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserMethodDeclaration;
import com.github.javaparser.symbolsolver.javassistmodel.JavassistMethodDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionMethodDeclaration;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tratto.data.OracleDatapoint;
import star.tratto.data.JPClassNotFoundException;
import star.tratto.data.PackageDeclarationNotFoundException;
import star.tratto.data.ResolvedTypeNotFound;
import star.tratto.oraclegrammar.custom.Parser;
import star.tratto.util.JavaTypes;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static star.tratto.util.JavaTypes.isAssignableToNumeric;
import static star.tratto.util.StringUtils.fullyQualifiedClassName;

public class JavaParserUtils {
    private static final Logger logger = LoggerFactory.getLogger(JavaParserUtils.class);
    private static JavaParser javaParser = getJavaParser();
    private static final Parser oracleParser = Parser.getInstance();
    // artificial source code used to parse arbitrary source code expressions using JavaParser
    /** Artificial class name */
    private static final String SYNTHETIC_CLASS_NAME = "Tratto__AuxiliaryClass";
    /** Artificial class source code */
    private static final String SYNTHETIC_CLASS_SOURCE = "public class " + SYNTHETIC_CLASS_NAME + " {}";
    /** Artificial method name */
    private static final String SYNTHETIC_METHOD_NAME = "__tratto__auxiliaryMethod";
    /**
     * Regex to match the signature from the "toString" of either {@link ReflectionMethodDeclaration} or
     * {@link JavassistMethodDeclaration} (both implementations of {@link ResolvedMethodDeclaration})
     */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "^ReflectionMethodDeclaration\\{method=((.*) )?\\S+ \\S+\\(.*\\}$|" +
                    "^JavassistMethodDeclaration\\{ctMethod\\=.*\\[((.*) )?\\S+ \\(.*\\).*\\]}$"
    );
    /** Regex to match the binary name of a class (e.g. "package.submodule.InnerClass$OuterClass") */
    private static final Pattern PACKAGE_CLASS = Pattern.compile("[a-zA-Z_][a-zA-Z\\d_]*(\\.[a-zA-Z_][a-zA-Z\\d_]*)*");

    /** Private constructor to avoid creating an instance of this class. */
    private JavaParserUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    public static JavaParser getJavaParser() {
        if (javaParser == null) {
            String root = "src/main/resources/projects-packaged";
            SymbolSolverCollectionStrategy strategy = new SymbolSolverCollectionStrategy();
            strategy.collect(Paths.get(root));
            javaParser = new JavaParser();
            javaParser.getParserConfiguration().setSymbolResolver(strategy.getParserConfiguration().getSymbolResolver().get());
        }
        return javaParser;
    }

    /**
     * Gets a synthetic empty JavaParser class.
     *
     * @return an empty JavaParser class
     */
    private static TypeDeclaration<?> getSyntheticClass() {
        return javaParser.parse(SYNTHETIC_CLASS_SOURCE).getResult().orElseThrow()
                .getLocalDeclarationFromClassname(SYNTHETIC_CLASS_NAME).get(0);
    }

    /**
     * Adds a synthetic empty method block to a given class.
     *
     * @param typeDeclaration the class to add a method to
     * @return an empty JavaParser method block in the given class
     */
    private static BlockStmt getSyntheticBlockStmt(TypeDeclaration<?> typeDeclaration) {
        return typeDeclaration.addMethod(SYNTHETIC_METHOD_NAME).getBody().orElseThrow();
    }

    /**
     * Creates a "java.lang.Object" type.
     *
     * @return a "java.lang.Object" type
     */
    public static ResolvedType getObjectType() {
        TypeDeclaration<?> syntheticClass = getSyntheticClass();
        BlockStmt syntheticMethod = getSyntheticBlockStmt(syntheticClass);
        return syntheticMethod
                .addStatement("java.lang.Object objectVar;")
                .getStatements().getLast().orElseThrow()
                .asExpressionStmt().getExpression()
                .asVariableDeclarationExpr().getVariables().get(0)
                .resolve().getType();
    }

    /**
     * Injects a synthetic method into a class given a method under test,
     * including:
     * <ul>
     *     <li>variable declaration for each method argument</li>
     *     <li>variable declaration for return type of analyzed method</li>
     *     <li>variable declaration (of unknown type) using given expression</li>
     * </ul>
     *
     * @param jpClass the declaring class in which to insert a synthetic method
     * @param jpCallable the method under analysis
     * @param methodArgs tokens for arguments of {@code jpCallable}
     * @param expression an expression to add to the synthetic method
     * @throws ResolvedTypeNotFound if {@code jpCallable} is a constructor
     */
    private static void addSyntheticMethodWithExpression(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable,
            List<Triplet<String, String, String>> methodArgs,
            String expression
    ) throws ResolvedTypeNotFound {
        // throw error when given a constructor due to JavaParser behavior differences
        if (jpCallable.getNameAsString().equals(jpClass.getNameAsString())) {
            throw new ResolvedTypeNotFound("Unable to generate synthetic constructor for class " + jpClass.getNameAsString());
        }
        // create synthetic method
        BlockStmt methodBody = getSyntheticBlockStmt(jpClass);
        // add method arguments as variable statements in method body (e.g. "ArgType argName;")
        for (Triplet<String, String, String> methodArg : methodArgs) {
            methodBody.addStatement(methodArg.getValue2() + " " + methodArg.getValue0() + ";");
        }
        // add return type (if non-void)
        String returnType = ((MethodDeclaration) jpCallable).getType().asString();
        if (!returnType.equals("void")) {
            methodBody.addStatement(
                    returnType + " methodResultID = " + jpCallable.getNameAsString() + "(" +
                            methodArgs
                                    .stream()
                                    .map(Triplet::getValue0)
                                    .collect(Collectors.joining(", "))
                            + ");"
            );
        }
        // add expression
        methodBody.addStatement("var returnType = " + expression + ";");
    }

    /**
     * Gets the type of the given java expression. For example, given the
     * expression, {@code Integer.MAX_VALUE - Integer.MIN_VALUE}, the method
     * returns a JavaParser representation of the type {@code int}.
     *
     * @param jpClass the declaring class
     * @param jpCallable the method under analysis
     * @param methodArgs the arguments in the method under analysis
     * @param expression a Java expression
     * @return the resolved return type of the expression
     * @throws ResolvedTypeNotFound if {@code jpClass} is not a class/interface
     * or if {@code jpCallable} is a constructor
     * @throws NoSuchElementException if an error occurs while parsing the
     * expression
     */
    public static ResolvedType getResolvedTypeOfExpression(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable,
            List<Triplet<String, String, String>> methodArgs,
            String expression
    ) throws ResolvedTypeNotFound {
        if (jpClass instanceof ClassOrInterfaceDeclaration) {
            addSyntheticMethodWithExpression(
                    jpClass,
                    jpCallable,
                    methodArgs,
                    expression
            );
            // find expression in synthetic method and resolve return type
            return jpClass.getMethodsByName(SYNTHETIC_METHOD_NAME).get(0)
                    .getBody().orElseThrow()
                    .getStatements().getLast().orElseThrow()
                    .asExpressionStmt().getExpression()
                    .asVariableDeclarationExpr().getVariables().get(0)
                    .getInitializer().orElseThrow()
                    .calculateResolvedType();
        }
        throw new ResolvedTypeNotFound(String.format(
                "ResolvedType of expression %s of class %s and method %s not found.",
                expression,
                jpClass.getNameAsString(),
                jpCallable.getNameAsString()
        ));
    }

    /**
     * Given a syntactically valid Java expression, evaluates its return type, including package and class.
     * If the expression is not syntactically valid, the method will throw an exception.
     * @param expression the expression to evaluate, e.g., "methodResultID.negate().value(null).getField()".
     *                   Must conform to TrattoGrammar.
     * @param oracleDatapoint OracleDatapoint containing additional necessary information for resolving types,
     *                        e.g., class and method names and sources. NOTE: To handle Java expressions
     *                        containing the token "jdVar", the oracleDatapoint must have its "oracle" field
     *                        populated, even if it's a partial oracle (e.g., it's the oracle being currently
     *                        generated). Then, the last occurring jdVar clause is looked for and its type is
     *                        resolved, which will be used to resolve the type of the expression.
     * @return pair of strings, where the first string is the package and the second string is the class
     */
    public static Pair<String, String> getReturnTypeOfExpression(String expression, OracleDatapoint oracleDatapoint) {
        // Handle null
        if ("null".equals(expression)) {
            return JavaTypes.NULL;
        }

        // Generate synthetic method
        CompilationUnit cu = javaParser.parse(oracleDatapoint.getClassSourceCode()).getResult().get();
        String className = oracleDatapoint.getClassName();
        MethodDeclaration syntheticMethod = getSyntheticMethod(
                getClassOrInterface(cu, className),
                getMethodOrConstructorDeclaration(oracleDatapoint.getMethodSourceCode())
        );
        BlockStmt syntheticMethodBody = syntheticMethod.getBody().get();

        // If the method is not constructor and not void, add statement to save methodResultID
        MethodDeclaration method = getMethodDeclaration(oracleDatapoint.getMethodSourceCode());
        if (method != null) {
            String methodName = method.getNameAsString();
            String methodReturnType = method.getType().asString();
            if (!"void".equals(methodReturnType)) {
                syntheticMethodBody.addStatement(methodReturnType + " methodResultID = " + methodName +
                        "(" + syntheticMethod.getParameters().stream().map(Parameter::getNameAsString).collect(Collectors.joining(", ")) + ");");
            }
        }

        // Handle jdVar if necessary
        handleJdVarIfNecessary(syntheticMethodBody, expression, oracleDatapoint);

        // Add last statement where the expression will be evaluated
        syntheticMethodBody.addStatement("var returnType = " + expression + ";");

        // Get return type of expression
        ResolvedType returnType;
        try {
            returnType = getReturnTypeOfLastStatementInSyntheticMethod(cu, className);
        } catch (UnsolvedSymbolException e) { // The resolution may fail if imports are missing, try to fix it by adding them
            addImports(cu, expression, oracleDatapoint);
            returnType = getReturnTypeOfLastStatementInSyntheticMethod(cu, className);
        }

        return getTypePairFromResolvedType(returnType);
    }

    /**
     * Given the class and method under test, creates a synthetic method
     * with the same signature (in terms of type parameters and arguments) as the method under test.
     * Inserts the synthetic method into the class and returns it.
     */
    private static MethodDeclaration getSyntheticMethod(TypeDeclaration<? extends TypeDeclaration<?>> classUnderTest, BodyDeclaration<?> methodUnderTest) {
        MethodDeclaration syntheticMethod = classUnderTest.addMethod(SYNTHETIC_METHOD_NAME);
        syntheticMethod.setTypeParameters(getTypeParameters(methodUnderTest));
        syntheticMethod.setParameters(getParameters(methodUnderTest));
        return syntheticMethod;
    }

    /**
     * Pass a null oracleDatapoint to create a synthetic method without any particularities. Otherwise,
     * if the oracleDatapoint is not null, the method will be created with
     * {@link #getSyntheticMethod(TypeDeclaration, BodyDeclaration)} (see its documentation).
     */
    private static MethodDeclaration getSyntheticMethod(CompilationUnit cu, OracleDatapoint oracleDatapoint) {
        if (oracleDatapoint != null) {
            return getSyntheticMethod(
                    getClassOrInterface(cu, oracleDatapoint.getClassName()),
                    getMethodOrConstructorDeclaration(oracleDatapoint.getMethodSourceCode())
            );
        } else {
            return getClassOrInterface(cu, SYNTHETIC_CLASS_NAME).addMethod(SYNTHETIC_METHOD_NAME);
        }
    }

    private static void handleJdVarIfNecessary(BlockStmt syntheticMethodBody, String expression, OracleDatapoint oracleDatapoint) {
        if (!expression.contains("jdVar")) {
            return;
        }
        String jdVarArrayElement = oracleParser.getLastJdVarArrayElement(oracleDatapoint.getOracle());
        if (jdVarArrayElement == null) {
            throw new IllegalStateException("Could not find a jdVar clause in the oracle, but the expression contains jdVar. " +
                    "Expression: " + expression + ". Oracle: " + oracleDatapoint.getOracle());
        }
        syntheticMethodBody.addStatement("var jdVar = " + jdVarArrayElement + ";");
    }

    private static ResolvedType getReturnTypeOfLastStatementInSyntheticMethod(CompilationUnit cu, String className) {
        return getClassOrInterface(cu, className)
                .getMethodsByName(SYNTHETIC_METHOD_NAME).get(0)
                .getBody().get()
                .getStatements().getLast().get()
                .asExpressionStmt().getExpression()
                .asVariableDeclarationExpr().getVariables().get(0)
                .getInitializer().get()
                .calculateResolvedType();
    }

    private static void addImports(CompilationUnit cu, String expression, OracleDatapoint oracleDatapoint) {
        oracleDatapoint.getTokensProjectClasses().forEach(projectClass -> {
            if (expression.contains(projectClass.getValue0())) {
                cu.addImport(fullyQualifiedClassName(projectClass.getValue1(), projectClass.getValue0()));
            }
        });
        if (expression.contains("\\bArrays\\.")) {
            cu.addImport("java.util.Arrays");
        }
    }

    /**
     * Note: if the class is something like "{@code List<String>}", this method will return "List" as the class name.
     */
    public static Pair<String, String> getTypePairFromResolvedType(ResolvedType resolvedType) {
        if (resolvedType.isReferenceType()) {
            ResolvedReferenceTypeDeclaration type = resolvedType.asReferenceType().getTypeDeclaration().get();
            return Pair.with(type.getPackageName(), type.getClassName());
        } else if (resolvedType.isArray()) {
            StringBuilder suffix = new StringBuilder("[]");
            ResolvedType arrayElement = resolvedType.asArrayType().getComponentType();
            while (arrayElement.isArray()) {
                arrayElement = arrayElement.asArrayType().getComponentType();
                suffix.append("[]");
            }
            if (arrayElement.isReferenceType()) {
                ResolvedReferenceTypeDeclaration arrayElementType = arrayElement.asReferenceType().getTypeDeclaration().get();
                return Pair.with(arrayElementType.getPackageName(), arrayElementType.getClassName() + suffix);
            } else {
                return Pair.with("", arrayElement.describe() + suffix);
            }
        } else if (resolvedType.isWildcard()) { // e.g., in Collection<? extends String>, "? extends String" is a wildcard
            ResolvedWildcard type = resolvedType.asWildcard();
            ResolvedType boundedType = type.getBoundedType();
            if (boundedType != null) {
                ResolvedReferenceTypeDeclaration resolvedBoundedType = type.getBoundedType().asReferenceType().getTypeDeclaration().get();
                return Pair.with(resolvedBoundedType.getPackageName(), resolvedBoundedType.getClassName());
            } else { // e.g., in Collection<?>, "?" can be anything, so we return Object
                return Pair.with("java.lang", "Object");
            }
        } else {
            return Pair.with("", resolvedType.describe()); // Primitive type
        }
    }

    /**
     * Removes the package name from a fully qualified name of a type. Also
     * removes package from type parameters.
     *
     * @param fullyQualifiedName a fully qualified name of a type
     * @return the type name without packages. Includes outer classes, e.g.,
     *     package.Outer.Inner    =>    Outer.Inner
     */
    private static String getTypeWithoutPackages(String fullyQualifiedName) {
        Matcher matcher = PACKAGE_CLASS.matcher(fullyQualifiedName);
        while (matcher.find()) {
            if (matcher.group().contains(".")) {
                fullyQualifiedName = fullyQualifiedName.replaceAll(
                        matcher.group(),
                        // removes packages but keeps outer classes
                        getResolvedReferenceTypeDeclaration(matcher.group()).getClassName()
                );
            }
        }
        return fullyQualifiedName;
    }

    /**
     * A resolved type may be void, primitive, an array, a reference type, etc.
     * (including arrays of reference types). If the type involves a reference
     * type, this method returns the fully qualified name without packages.
     *
     * @param resolvedType JavaParser resolved type
     * @return the fully qualified name of the type without packages. If the
     * resolved type is an array of reference types, removes the packages from
     * the fully qualified name of the component type.
     */
    public static String getTypeWithoutPackages(ResolvedType resolvedType) {
        String typeName = resolvedType.describe();
        ResolvedType componentType = removeArray(resolvedType);
        if (componentType.isReferenceType()) {
            // we use the original type name to avoid removing arrays
            return getTypeWithoutPackages(typeName);
        } else {
            return typeName;
        }
    }

    /**
     * Returns the {@link ResolvedReferenceTypeDeclaration} of a given binary
     * type name.
     *
     * @param type binary type name, e.g., {@code java.util.List}
     * @return the corresponding JavaParser ResolvedReferenceTypeDeclaration
     * @throws UnsolvedSymbolException if the type cannot be resolved
     * @throws UnsupportedOperationException if the type is an array or
     * primitive type
     */
    public static ResolvedReferenceTypeDeclaration getResolvedReferenceTypeDeclaration(String type) throws UnsolvedSymbolException, UnsupportedOperationException {
        return getResolvedType(type).asReferenceType().getTypeDeclaration().get();
    }

    private static ResolvedReferenceTypeDeclaration getResolvedReferenceTypeDeclaration(ResolvedType resolvedType) throws UnsupportedOperationException {
        return resolvedType.asReferenceType().getTypeDeclaration().get();
    }

    /**
     * @throws UnsupportedOperationException if the type is an array or a primitive type
     */
    private static ResolvedType getResolvedType(String type) throws UnsupportedOperationException {
        CompilationUnit cu = javaParser.parse(SYNTHETIC_CLASS_SOURCE).getResult().get();
        BlockStmt syntheticMethodBody = getClassOrInterface(cu, SYNTHETIC_CLASS_NAME).addMethod(SYNTHETIC_METHOD_NAME).getBody().get();
        syntheticMethodBody.addStatement(type + " type1Var;");
        return getClassOrInterface(cu, SYNTHETIC_CLASS_NAME)
                .getMethodsByName(SYNTHETIC_METHOD_NAME).get(0)
                .getBody().get()
                .getStatements().getLast().get()
                .asExpressionStmt().getExpression()
                .asVariableDeclarationExpr().getVariables().get(0)
                .resolve().getType();
    }

    /**
     * Given a fully qualified class name, returns all methods that can be called on top of that type.
     * If the type is a regular reference type that can be resolved, retrieves applicable methods. If
     * the type is a reference type but cannot be resolved (e.g., a generic type), retrieves methods
     * from java.lang.Object. If the type is an array, retrieves methods from java.lang.Object. If the
     * type is a primitive, throws an IllegalArgumentException.
     * @throws IllegalArgumentException if the type is not a reference type or an array
     */
    public static Set<MethodUsage> getMethodsOfType(String referenceType) throws IllegalArgumentException {
        ResolvedType resolvedType = null;
        ResolvedReferenceTypeDeclaration resolvedReferenceTypeDeclaration = null;
        Set<MethodUsage> methods = new HashSet<>();
        boolean useObjectMethods = true;
        try {
            resolvedType = getResolvedType(referenceType);
            resolvedReferenceTypeDeclaration = getResolvedReferenceTypeDeclaration(resolvedType);
            methods.addAll(resolvedReferenceTypeDeclaration.getAllMethods());
            // Interfaces do not always inherit from Object
            if (!resolvedReferenceTypeDeclaration.isInterface()) {
                useObjectMethods = false;
            }
        } catch (UnsupportedOperationException e) {
            if (!resolvedType.isArray()) {
                throw new IllegalArgumentException("Trying to retrieve available methods from a type that is not " +
                        "a reference type or an array: " + referenceType, e);
            }
        } catch (UnsolvedSymbolException e) {
            logger.warn("Unresolvable type: {}", referenceType);
        }
        if (useObjectMethods) {
            Set<MethodUsage> objectMethods = getResolvedReferenceTypeDeclaration("java.lang.Object").getAllMethods();
            objectMethods.forEach(om -> {
                if (methods.stream().noneMatch(m -> m.getName().equals(om.getName()) && m.getParamTypes().equals(om.getParamTypes()))) {
                    methods.add(om);
                }
            });
        }
        return methods;
    }

    /** Returns true if type1 is an instance of type2, false otherwise.
@param type1 fully qualified type, e.g., "java.util.List"
     * @param type2 fully qualified type, e.g., "java.lang.Object"
     * @param oracleDatapoint may be null. If not null, it is used to check if some type is generic.
     * @return true if type1 is an instance of type2, false otherwise
     */
    public static boolean isInstanceOf(String type1, String type2, OracleDatapoint oracleDatapoint) {
        return isInstanceOf(type1, type2, oracleDatapoint, true);
    }

    /**
     * Compared to {@link #isInstanceOf(String, String, OracleDatapoint)}, this method returns
     * true if type1 and type2 can be compared using the instanceof operator. To better understand this
     * difference, consider the following use cases:
     * <ul>
     *     <li>{@code isInstanceOf("String", "Object", null)} returns {@code true}</li>
     *     <li>{@code isInstanceOf("Object", "String", null)} returns {@code false}</li>
     *     <li>{@code doesInstanceofCompile("String", "Object", null)} returns {@code true}</li>
     *     <li>{@code doesInstanceofCompile("Object", "String", null)} returns {@code true}</li>
     * </ul>
     * In other words, this method returns true if the expression "var1 instanceof type2" would compile,
     * where var1 is a variable of type1.
     */
    public static boolean doesInstanceofCompile(String type1, String type2, OracleDatapoint oracleDatapoint) {
        ResolvedType resolvedType2 = tryToGetResolvedType(type2);
        if (resolvedType2 == null) {
            return false; // Either type2 is generic, or an unknown class. In both cases, type1 cannot be instanceof it
        }
        try {
            List<String> type2TypeParameters = resolvedType2.asReferenceType().getTypeDeclaration().get().getTypeParameters()
                    .stream().map(ResolvedTypeParameterDeclaration::getName).collect(Collectors.toList());
            if (type2TypeParameters.contains(type1)) {
                return true; // Special case: type1 is a generic and a type parameter of type2
            }
        } catch (UnsupportedOperationException|NoSuchElementException|NullPointerException ignored) {}
        return isInstanceOf(type1, type2, oracleDatapoint, false) || isInstanceOf(type2, type1, null, false);
    }

    /**
     * Auxiliary method used both by {@link #isInstanceOf(String, String, OracleDatapoint)}
     * and {@link #doesInstanceofCompile}.
     * @param checkEquality if true, returns true if type1 is equal to type2. If false, this check is
     *                      not performed at all. Must be true if checking {@code isInstanceOf}.
     *                      Must be false if checking {@code doesInstanceofCompile}. This is because
     *                      type1 and type2 may be generics or unresolvable classes, and in those cases
     *                      we cannot use the instanceof operator in a generated oracle, because it
     *                      would not compile.
     */
    private static boolean isInstanceOf(String type1, String type2, OracleDatapoint oracleDatapoint, boolean checkEquality) {
        // Preliminary checks
        if (JavaTypes.PRIMITIVE_TYPES.contains(type1) || JavaTypes.PRIMITIVE_TYPES.contains(type2)) {
            return false;
        }
        if (checkEquality && type1.equals(type2)) {
            return true;
        }
        ResolvedType resolvedType2 = tryToGetResolvedType(type2);
        if (resolvedType2 == null) {
            return false; // Type2 is generic or an unknown class. In both cases, type1 cannot be instanceof it
        }

        String classSourceCode;
        String className;
        if (oracleDatapoint != null) {
            classSourceCode = oracleDatapoint.getClassSourceCode();
            className = oracleDatapoint.getClassName();
        } else {
            classSourceCode = SYNTHETIC_CLASS_SOURCE;
            className = SYNTHETIC_CLASS_NAME;
        }

        // Parse class that contains method under test and add synthetic method to get resolved type 1 (we already have type 2)
        CompilationUnit cu = javaParser.parse(classSourceCode).getResult().get();
        BlockStmt syntheticMethodBody = getSyntheticMethod(cu, oracleDatapoint).getBody().get();
        syntheticMethodBody.addStatement(type1 + " type1Var;");

        // Get result of instanceof expression
        try {
            return resolvedType2.isAssignableBy(
                    getClassOrInterface(cu, className)
                            .getMethodsByName(SYNTHETIC_METHOD_NAME).get(0)
                            .getBody().get()
                            .getStatements().getFirst().get()
                            .asExpressionStmt().getExpression()
                            .asVariableDeclarationExpr().getVariables().get(0)
                            .resolve().getType());
        } catch (UnsolvedSymbolException e) {
            logger.warn("Failed to evaluate instanceof within method. Expression: \"type1Var instanceof {}\". Method: {}", type2, syntheticMethodBody.getParentNode().get());
            return false;
        }
    }

    private static ResolvedType tryToGetResolvedType(String type2) {
        ResolvedType resolvedType2;
        try {
            resolvedType2 = getResolvedType(type2);
        } catch (UnsolvedSymbolException e) {
            return null;
        }
        return resolvedType2;
    }

    // TODO: The "not the other way around" is true only in a certain sense.  Given variables `b`
    // and `B` of type boolean and Boolean, both "b = B" and "B = b" will compile, because Java
    // performs automatic boxing and unboxing.  Please clarify the comment.
    /**
     * This method is different from {@link #isInstanceOf} in that it can be used to compare
     * primitive types and primitive wrapper types. For instance, a boolean is assignable to a Boolean,
     * but not the other way around. Note that this method takes as input pairs of package and class
     * name, instead of fully qualified types.
     * @param type1 pair with &lt;package, class&gt;, e.g., &lt;"java.util", "List"&gt;
     * @param type2 pair with &lt;package, class&gt;, e.g., &lt;"java.lang", "Object"&gt;
     * @param oracleDatapoint may be null. If not null, it is used to check if some type is generic.
     * @return true if a variable of type1 can be assigned to a variable of type2, false otherwise
     */
    public static boolean isType1AssignableToType2(Pair<String, String> type1, Pair<String, String> type2, OracleDatapoint oracleDatapoint) {
        // Cases to consider:
        // 1) left and right types are the same
        // 2) left type is null and right type is not primitive
        // 3) left type is primitive and right type is wrapper
        // 4) both types are numeric and left type is assignable to right type
        // 5) left type is instance of right type (both types are non-primitive)
        return
                type1.equals(type2) ||
                (type1.equals(JavaTypes.NULL) && !JavaTypes.PRIMITIVES.contains(type2)) ||
                (JavaTypes.PRIMITIVES.contains(type1) && JavaTypes.PRIMITIVES_TO_WRAPPERS.get(type1).equals(type2)) ||
                (JavaTypes.NUMBERS.contains(type1) && JavaTypes.NUMBERS.contains(type2) && isAssignableToNumeric(type1, type2)) ||
                isInstanceOf(fullyQualifiedClassName(type1), fullyQualifiedClassName(type2), oracleDatapoint);
    }

    public static TypeDeclaration<?> getClassOrInterface(CompilationUnit cu, String name) {
        try {
            return cu.getLocalDeclarationFromClassname(name).get(0);
        } catch (NoSuchElementException|IndexOutOfBoundsException ignored) {}
        try {
            return cu.getClassByName(name).get();
        } catch (NoSuchElementException ignored) {}
        try {
            return cu.getInterfaceByName(name).get();
        } catch (NoSuchElementException ignored) {}
        try {
            return cu.getEnumByName(name).get();
        } catch (NoSuchElementException e) {
            throw new RuntimeException("Could not find class or interface " + name + " in compilation unit.", e);
        }
    }

    public static TypeDeclaration<?> getClassOrInterface(String classSourceCode, String name) {
        return getClassOrInterface(javaParser.parse(classSourceCode).getResult().get(), name);
    }

    /**
     * Returns the String variable declaration represented by a JavaParser
     * VariableDeclarator.
     *
     * @param field the JavaParser field declaration
     * @param variable the JavaParser variable declaration
     * @return a string representation of the declaration of {@code variable}.
     * The declaration follows the format:
     *  "(modifiers) (type) (name)( = initial value);"
     */
    public static String getVariableDeclaration(FieldDeclaration field, VariableDeclarator variable) {
        return (String.join("", field.getModifiers().stream().map(Node::toString).toList()) +
                variable.getTypeAsString() + " " +
                variable.getNameAsString() +
                (variable.getInitializer().isPresent()
                        ? " = " + variable.getInitializer().get()
                        : "") +
                ";").trim();
    }

    /**
     * Returns the String field declaration represented by a JavaParser
     * {@link ResolvedFieldDeclaration}.
     *
     * @param resolvedField a resolved field declaration
     * @return a string representation of the declaration of
     * {@code resolvedField}. The declaration follows the format:
     *  "(access-specifier) (static) (type) (name);"
     */
    public static String getFieldDeclaration(
            ResolvedFieldDeclaration resolvedField
    ) {
        boolean hasAccessSpecifier = !resolvedField.accessSpecifier().asString().equals("");
        boolean isStatic = resolvedField.isStatic();
        return ((hasAccessSpecifier ? resolvedField.accessSpecifier().asString() + " " : "") +
                (isStatic ? "static " : "") +
                getTypeWithoutPackages(resolvedField.getType().describe()) + " " +
                resolvedField.getName() +
                ";");
    }

    /**
     * Gets the String field declaration represented by a JavaParser
     * {@link ResolvedFieldDeclaration}. Uses a given modifier value to
     * determine the modifiers.
     *
     * @param resolvedField resolved field declaration
     * @param modifier an integer representing the field modifiers
     * @return a string representation of the declaration. The declaration
     * follows the format:
     *  "(modifiers) (type) (name);"
     */
    public static String getFieldDeclaration(
            ResolvedFieldDeclaration resolvedField,
            int modifier
    ) {
        return ((modifier == 0) ? "" : (java.lang.reflect.Modifier.toString(modifier) + " ") +
                getTypeWithoutPackages(resolvedField.getType()) + " " +
                resolvedField.getName() +
                ";").trim();
    }

    /**
     * Returns the signature of a JavaParser callable declaration. Uses the
     * method source code and removes method body, contained comments, the
     * Javadoc comment, and other special characters (e.g. "\n").
     *
     * @param jpCallable a JavaParser callable declaration
     * @return a string representation of the signature. A signature follows
     * the format:
     *     "[modifiers] [typeParameters] [type] [methodName]([parameters]) throws [exceptions]"
     */
    public static String getCallableSignature(
            CallableDeclaration<?> jpCallable
    ) {
        // get method source as a string
        String methodSignature = jpCallable.toString();
        Optional<BlockStmt> methodBody = jpCallable instanceof MethodDeclaration ?
                ((MethodDeclaration) jpCallable).getBody() :
                Optional.ofNullable(((ConstructorDeclaration) jpCallable).getBody());
        // remove method body
        if (methodBody.isPresent()) {
            methodSignature = methodSignature.replace(methodBody.get().toString(), "");
        }
        // remove all comments
        for (Node comment: jpCallable.getAllContainedComments()) {
            methodSignature = methodSignature.replace(comment.toString(), "");
        }
        // remove Javadoc comment
        if (jpCallable.getComment().isPresent()) {
            methodSignature = methodSignature.replaceAll("[\\s\\S]*\n", "");
        }
        // remove special characters
        methodSignature = methodSignature.replaceAll("/\\*\\*([\\s\\S]*?)\\*/(\\n|\\r|\\t)*", "");
        return methodSignature.trim().replaceAll(";$", "");
    }

    /**
     * Returns the signature of a JavaParser method declaration.
     *
     * @param methodDeclaration a JavaParser method declaration
     * @return a string representation of the signature. Signature follows the
     * format:
     *  "[modifiers] [type] [methodName]([parameters]) throws [exceptions]"
     */
    public static String getMethodSignature(MethodDeclaration methodDeclaration) {
        String method = methodDeclaration.toString();
        if (methodDeclaration.getBody().isPresent()) {
            // Remove body
            method = method.replace(methodDeclaration.getBody().get().toString(), "");
        }
        for (Node comment: methodDeclaration.getAllContainedComments()) {
            // Remove comments within method signature
            method = method.replace(comment.toString(), "");
        }
        if (methodDeclaration.getComment().isPresent()) {
            // At this point, last line is method signature. Remove everything before that
            method = method.replaceAll("[\\s\\S]*\n", "");
        }
        return method.trim().replaceAll(";$", "");
    }

    /**
     * Gets all type parameters of a given method.
     */
    private static List<String> getTypeParameters(MethodUsage methodUsage) {
        return methodUsage.getDeclaration().getTypeParameters()
                .stream()
                .map(ResolvedTypeParameterDeclaration::getName)
                .toList();
    }

    /**
     * Gets all formal parameters in the method definition.
     */
    private static List<String> getParameters(MethodUsage methodUsage) {
        ResolvedMethodDeclaration methodDeclaration = methodUsage.getDeclaration();
        // iterate through each parameter in the method declaration.
        List<String> methodParameters = new ArrayList<>();
        for (int i = 0; i < methodDeclaration.getNumberOfParams(); i++) {
            methodParameters.add(getTypeWithoutPackages(methodDeclaration.getParam(i).getType()) + " arg" + i);
        }
        return methodParameters;
    }

    /**
     * Gets all exceptions that can be thrown by a given method.
     */
    private static List<String> getExceptions(MethodUsage methodUsage) {
        return methodUsage.getDeclaration().getSpecifiedExceptions()
                .stream()
                .map(JavaParserUtils::getTypeWithoutPackages)
                .toList();
    }

    /**
     * Gets the method signature from a JavaParser MethodUsage and its
     * corresponding ResolvedMethodDeclaration. Unfortunately, depending on
     * the implementation of the ResolvedMethodDeclaration, it is not possible
     * to recover specific features, such as:
     * <ul>
     *     <li>Modifiers</li>
     *     <li>Annotations</li>
     *     <li>Parameter names</li>
     * </ul>
     * This method considers three implementations of
     * ResolvedMethodDeclaration: JavaParserMethodDeclaration,
     * ReflectionMethodDeclaration, and JavassistMethodDeclaration. A
     * signature follows the format:
     *     "[modifiers] [typeParameters] [type] [methodName]([parameters]) throws [exceptions]"
     */
    public static String getMethodSignature(
            MethodUsage methodUsage
    ) {
        ResolvedMethodDeclaration methodDeclaration = methodUsage.getDeclaration();
        // Consider JavaParserMethodDeclaration.
        if (methodDeclaration instanceof JavaParserMethodDeclaration jpMethodDeclaration) {
            MethodDeclaration jpMethod = jpMethodDeclaration.getWrappedNode();
            return getMethodSignature(jpMethod);
        }
        // Consider ReflectionMethodDeclaration or JavassistMethodDeclaration
        Matcher matcher = METHOD_SIGNATURE.matcher(methodDeclaration.toString());
        if (!matcher.find()) {
            throw new IllegalStateException("Could not parse method signature: " + methodDeclaration);
        }
        String methodModifiers = methodDeclaration instanceof ReflectionMethodDeclaration ? matcher.group(1) : matcher.group(2);
        // Take into account the case in which the method declaration refers to a method without an access specifier.
        if (methodModifiers == null) {
            methodModifiers = "";
        }
        List<String> typeParameterList = getTypeParameters(methodUsage);
        List<String> formalParameterList = getParameters(methodUsage);
        List<String> exceptionList = getExceptions(methodUsage);
        return (methodModifiers + " " + (typeParameterList.isEmpty() ? "" : "<" + String.join(", ", typeParameterList) + ">") +
                " " + getTypeWithoutPackages(methodDeclaration.getReturnType()) +
                " " + methodDeclaration.getName() +
                "(" + String.join(", ", formalParameterList) + ")" +
                (exceptionList.isEmpty() ? "" : " throws " + String.join(", ", exceptionList)))
                .replaceAll(" +", " ").trim();
    }

    /**
     * Gets the JavaParser package declaration of a given compilation unit.
     *
     * @param cu a compilation unit
     * @return the JavaParser package declaration {@link PackageDeclaration}
     * of the compilation unit
     * @throws PackageDeclarationNotFoundException if the JavaParser package
     * declaration {@link PackageDeclaration} cannot be found or is unnamed
     */
    public static PackageDeclaration getPackageDeclaration(
            CompilationUnit cu
    ) throws PackageDeclarationNotFoundException {
        Optional<PackageDeclaration> jpPackage = cu.getPackageDeclaration();
        if (jpPackage.isEmpty()) {
            throw new PackageDeclarationNotFoundException(
                    "The JavaParser package declaration of the compilation unit is empty"
            );
        }
        return jpPackage.get();
    }

    /**
     * Returns the base component type of a resolved type. Recursively strips
     * all array variables. For example:
     *     Object[][] => Object
     *
     * @param resolvedType a type
     * @return the base component type
     */
    public static ResolvedType removeArray(ResolvedType resolvedType) {
        while (resolvedType.isArray()) {
            resolvedType = resolvedType.asArrayType().getComponentType();
        }
        return resolvedType;
    }

    /**
     * Checks if a type is a type parameter.
     *
     * @param resolvedType a JavaParser resolved type
     * @return true iff a given type is a type parameter of a generic class or
     * method. If the given type is an array, then this method checks the base
     * component type.
     */
    public static boolean isTypeParameter(
            ResolvedType resolvedType
    ) {
        ResolvedType componentType = removeArray(resolvedType);
        return componentType.isTypeVariable();
    }

    /**
     * Checks if a type name represents a type parameter. A type parameter may
     * be declared in either the class or method signature. This method checks
     * both the class where the type is found and its declaring class to
     * determine if the type is a type parameter.
     *
     * @param typeName a type name
     * @param jpCallable the method using {@code typeName}
     * @param jpClass the declaring class of {@code jpCallable}
     * @return true iff a given type is a type parameter of a generic class or
     * method. If the given type is an array, then this method checks the base
     * component type.
     */
    public static boolean isTypeParameter(
            String typeName,
            CallableDeclaration<?> jpCallable,
            TypeDeclaration<?> jpClass
    ) {
        // add all type parameters of the method
        List<String> typeParameterNames = jpCallable.getTypeParameters()
                .stream()
                .map(NodeWithSimpleName::getNameAsString)
                .collect(Collectors.toList());
        // add all type parameters of the class
        if (jpClass instanceof ClassOrInterfaceDeclaration) {
            typeParameterNames.addAll(
                    jpClass.asClassOrInterfaceDeclaration().getTypeParameters()
                            .stream()
                            .map(NodeWithSimpleName::getNameAsString)
                            .toList()
            );
        }
        return typeParameterNames.contains(typeName.replaceAll("\\[]", ""));
    }

    /**
     * Get all methods available to a given class (including those defined in
     * superclasses).
     *
     * @param typeDeclaration object class
     * @return list of MethodUsage objects
     * @throws JPClassNotFoundException if {@code typeDeclaration} is not
     * resolvable
     */
    public static List<MethodUsage> getMethodsOfType(
            TypeDeclaration<?> typeDeclaration
    ) throws JPClassNotFoundException {
        try {
            return new ArrayList<>(typeDeclaration.resolve().getAllMethods());
        } catch (UnsolvedSymbolException | IllegalArgumentException e) {
            String errMsg = String.format(
                    "Impossible to get all the methods of the class %s.%n%s.",
                    typeDeclaration.getNameAsString(), e
            );
            logger.error(errMsg);
            throw new JPClassNotFoundException(errMsg);
        }
    }

    /**
     * Get all fields available to a given class (including those defined in
     * superclasses).
     *
     * @param typeDeclaration object class
     * @return list of ResolvedFieldDeclaration objects
     * @throws JPClassNotFoundException if {@code typeDeclaration} is not
     * resolvable
     */
    public static List<ResolvedFieldDeclaration> getFieldsOfType(
            TypeDeclaration<?> typeDeclaration
    ) throws JPClassNotFoundException {
        try {
            return typeDeclaration.resolve().getAllFields();
        } catch (UnsolvedSymbolException | IllegalArgumentException e) {
            String errMsg = String.format(
                    "Impossible to get all the methods of class %s.%n%s.",
                    typeDeclaration.getNameAsString(), e
            );
            logger.error(errMsg);
            throw new JPClassNotFoundException(errMsg);
        }
    }

    /**
     * Gets a compilation unit from a Java file path.
     *
     * @param path a Java file
     * @return the corresponding JavaParser compilation unit. Returns
     * {@code Optional.empty()} if an error occurs while attempting to parse
     * the file.
     */
    public static Optional<CompilationUnit> getCompilationUnit(Path path) {
        try {
            return javaParser.parse(path).getResult();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Checks if a method is non-private and non-static.
     *
     * @param methodUsage a method
     * @return true iff the method is non-private non-static.
     */
    private static boolean isNonPrivateNonVoidMethod(MethodUsage methodUsage) {
        return !methodUsage.getDeclaration().getReturnType().isVoid() && !methodUsage.getDeclaration().accessSpecifier().equals(AccessSpecifier.PRIVATE);
    }

    /**
     * Checks if an attribute is non-private and non-static. An attribute
     * is any class field.
     *
     * @param fieldDeclaration a class field
     * @return true iff the field is non-private and non-static.
     */
    public static boolean isNonPrivateNonStaticAttribute(ResolvedFieldDeclaration fieldDeclaration) {
        return !fieldDeclaration.accessSpecifier().equals(AccessSpecifier.PRIVATE) && !fieldDeclaration.isStatic();
    }

    public static boolean isNonPrivateStaticNonVoidMethod(MethodUsage methodUsage) {
        return methodUsage.getDeclaration().isStatic() && isNonPrivateNonVoidMethod(methodUsage);
    }

    public static boolean isNonPrivateNonStaticNonVoidMethod(MethodUsage methodUsage) {
        return !methodUsage.getDeclaration().isStatic() && isNonPrivateNonVoidMethod(methodUsage);
    }

    /**
     * Unfortunately, JavaParser does not allow to parse constructors as method declarations. Since we don't
     * need the constructor declaration for anything, if the passed methodSourceCode is from a constructor,
     * this method returns null. This behavior must be handled by the caller.
     * @return null if the passed method source code is actually a constructor
     * @throws IllegalArgumentException if the provided methodSourceCode cannot be parsed by JavaParser
     */
    public static MethodDeclaration getMethodDeclaration(String methodSourceCode) throws IllegalArgumentException {
        try {
            return javaParser.parseBodyDeclaration(methodSourceCode).getResult().get().asMethodDeclaration();
        } catch (NoSuchElementException e) {
            throw new IllegalArgumentException("JavaParser cannot parse:" + System.lineSeparator() + methodSourceCode, e);
        } catch (IllegalStateException e) {
            return null; // This happens when the methodSourceCode is actually a constructor
        }
    }

    private static BodyDeclaration<?> getMethodOrConstructorDeclaration(String methodSourceCode) {
        BodyDeclaration<?> bodyDeclaration = javaParser.parseBodyDeclaration(methodSourceCode).getResult().get();
        if (bodyDeclaration.isMethodDeclaration() || bodyDeclaration.isConstructorDeclaration()) {
            return bodyDeclaration;
        } else {
            throw new IllegalArgumentException("Not a constructor or method:" + System.lineSeparator() + methodSourceCode);
        }
    }

    private static NodeList<TypeParameter> getTypeParameters(BodyDeclaration<?> bodyDeclaration) {
        if (bodyDeclaration.isMethodDeclaration()) {
            return bodyDeclaration.asMethodDeclaration().getTypeParameters();
        } else if (bodyDeclaration.isConstructorDeclaration()) {
            return bodyDeclaration.asConstructorDeclaration().getTypeParameters();
        } else {
            throw new IllegalArgumentException("Not a constructor or method body:"+ System.lineSeparator() + bodyDeclaration);
        }
    }

    private static NodeList<Parameter> getParameters(BodyDeclaration<?> bodyDeclaration) {
        if (bodyDeclaration.isMethodDeclaration()) {
            return bodyDeclaration.asMethodDeclaration().getParameters();
        } else if (bodyDeclaration.isConstructorDeclaration()) {
            return bodyDeclaration.asConstructorDeclaration().getParameters();
        } else {
            throw new IllegalArgumentException("Not a constructor or method body:" + System.lineSeparator() + bodyDeclaration);
        }
    }
}
