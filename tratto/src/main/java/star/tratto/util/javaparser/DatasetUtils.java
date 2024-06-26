package star.tratto.util.javaparser;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserFieldDeclaration;
import com.github.javaparser.symbolsolver.reflectionmodel.ReflectionFieldDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tratto.data.OracleDatapoint;
import star.tratto.data.OracleType;
import star.tratto.data.JPClassNotFoundException;
import star.tratto.data.PackageDeclarationNotFoundException;
import star.tratto.data.TrattoPath;
import star.tratto.data.records.AttributeTokens;
import star.tratto.data.records.ClassTokens;
import star.tratto.data.records.JDoctorCondition.Operation;
import star.tratto.data.records.TagAndText;
import star.tratto.data.records.ValueTokens;
import star.tratto.data.records.MethodArgumentTokens;
import star.tratto.data.records.MethodTokens;
import star.tratto.oraclegrammar.custom.Parser;
import star.tratto.oraclegrammar.custom.Splitter;
import star.tratto.util.FileUtils;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.plumelib.util.CollectionsPlume.mapList;

/**
 * This class provides static methods for generating features in the oracles
 * dataset, which is then used to create the tokens dataset.
 */
public class DatasetUtils {
    private static final Logger logger = LoggerFactory.getLogger(DatasetUtils.class);

    /** Do not instantiate this class. */
    private DatasetUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Removes all duplicate elements in a list, according to equals.
     *
     * @param list a list of elements
     * @return a new list with unique elements appearing in the same order as
     * the original list
     * @param <T> the type of object in the list
     */
    public static <T> List<T> withoutDuplicates(List<T> list) {
        Set<T> set = new LinkedHashSet<>(list);
        return new ArrayList<>(set);
    }

    /**
     * Gets a list of class tokens corresponding to each class defined in a
     * Java file.
     *
     * @param cu a Java file
     * @return a list of class tokens
     * @throws PackageDeclarationNotFoundException if the package cannot be
     * retrieved
     */
    private static List<ClassTokens> getClassTokens(
            CompilationUnit cu
    ) throws PackageDeclarationNotFoundException {
        String packageName = JavaParserUtils.getPackageDeclaration(cu).getNameAsString();
        return mapList(jpClass -> new ClassTokens(jpClass.getNameAsString(), packageName), cu.getTypes());
    }

    /** Regex to match the Javadoc of a class or method. */
    private static final Pattern javadocPattern = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);

    /**
     * Gets the Javadoc comment of a class or method using a regex. This
     * method should ONLY be called by
     * {@link DatasetUtils#getCallableJavadoc(CallableDeclaration)} or
     * {@link DatasetUtils#getClassJavadoc(TypeDeclaration)} (e.g. after
     * attempting to recover the Javadoc using the JavaParser API).
     *
     * @param jpBody a Java class or method
     * @return the matched Javadoc comment surrounded by
     * "&#47;&#42;&#42; ... &#42;&#42;&#47;" for compatibility with the XText
     * grammar (empty string if not found)
     */
    private static String getJavadocByPattern(BodyDeclaration<?> jpBody) {
        String input = jpBody.toString();
        Matcher matcher = javadocPattern.matcher(input);
        if (matcher.find()) {
            String content = matcher.group(1);
            if (jpBody instanceof TypeDeclaration<?>) {
                // class javadoc format
                return "/**" + content + "*/";
            } else {
                // method javadoc format
                return "    /**" + content + "*/";
            }
        }
        return "";
    }

    /**
     * Gets the Javadoc comment of a given class.
     *
     * @param jpClass a JavaParser class
     * @return the class Javadoc comment, in Javadoc format, surrounded by
     * "&#47;&#42;&#42; ... &#42;&#42;&#47;" for compatibility with the XText
     * grammar (empty string if not found)
     */
    public static String getClassJavadoc(
            TypeDeclaration<?> jpClass
    ) {
        Optional<JavadocComment> optionalJavadocComment = jpClass.getJavadocComment();
        return optionalJavadocComment
                .map(javadocComment -> "/**" + javadocComment.getContent() + "*/")
                .orElseGet(() -> getJavadocByPattern(jpClass));
    }

    /**
     * Gets the package name of a given compilation unit.
     *
     * @param cu a compilation unit of a Java file
     * @return the name of the package obtained from the compilation unit
     */
    public static String getClassPackage(
            CompilationUnit cu
    ) {
        try {
            return JavaParserUtils.getPackageDeclaration(cu).getNameAsString();
        } catch (PackageDeclarationNotFoundException e) {
            return "";
        }
    }

    /**
     * Gets the Javadoc comment of a given method.
     *
     * @param jpCallable a JavaParser method
     * @return the method/constructor Javadoc comment, in Javadoc format,
     * surrounded by "&#47;&#42; ... &#42;&#47;" (empty string if not found)
     */
    public static String getCallableJavadoc(
            CallableDeclaration<?> jpCallable
    ) {
        Optional<JavadocComment> optionalJavadocComment = jpCallable.getJavadocComment();
        return optionalJavadocComment
                .map(javadocComment -> "    /**" + javadocComment.getContent().replaceAll("@exception ", "@throws ") + "*/")
                .orElseGet(() -> getJavadocByPattern(jpCallable));
    }

    /** Regex to match the numeric values in a Javadoc comment. */
    private static final Pattern numericValuePattern = Pattern.compile("-?\\d+(\\.\\d+)?|\\|\\.\\d+");

    /**
     * Gets all numeric value tokens in a Javadoc comment.
     *
     * @param javadocComment a raw Javadoc comment (i.e. including surrounding
     *                       asterisks)
     * @return a list of numeric value tokens
     */
    private static List<ValueTokens> getAllNumericLiteralsInJavadoc(
            String javadocComment
    ) {
        Matcher matcher = numericValuePattern.matcher(javadocComment);
        // Iterate through all occurrences.
        List<ValueTokens> numericValues = new ArrayList<>();
        while (matcher.find()) {
            String match = matcher.group();
            if (match.contains(".")) {
                // double (decimal).
                try {
                    double realValue = Double.parseDouble(match);
                    numericValues.add(new ValueTokens(Double.toString(realValue), "double"));
                } catch (Exception e) {
                    logger.error(String.format("Number exceeds maximum double value: %s%n", match));
                }
            } else {
                // integer (no decimal).
                try {
                    // "long" is used to parse larger integers for the XText grammar.
                    long longIntValue = Long.parseLong(match);
                    numericValues.add(new ValueTokens(Long.toString(longIntValue), "int"));
                } catch (NumberFormatException e) {
                    logger.error(String.format("Number exceeds maximum long value: %s", match));
                }
            }
        }
        return numericValues;
    }

    /** Regex to match the string and character values in a Javadoc comment. */
    private static final Pattern stringValuePattern = Pattern.compile("\"(.*?)\"|'(.*?)'");

    /**
     * Gets all string value tokens in a Javadoc comment.
     *
     * @param javadocComment a raw Javadoc comment (i.e. including surrounding
     *                       asterisks)
     * @return a list of string value tokens
     */
    private static List<ValueTokens> getAllStringLiteralsInJavadoc(
            String javadocComment
    ) {
        Matcher matcher = stringValuePattern.matcher(javadocComment);
        // Iterate through all occurrences.
        List<ValueTokens> stringValues = new ArrayList<>();
        while (matcher.find()) {
            // matches strings but not chars
            String quotedValue = String.format("\"%s\"", !(matcher.group(1) == null) ? matcher.group(1) : matcher.group(2));
            // value is recorded without surrounding quotations for grammar compatibility
            stringValues.add(new ValueTokens(quotedValue, "String"));
        }
        return stringValues;
    }

    /**
     * Gets all value tokens in a Javadoc comment via pattern matching.
     *
     * @param javadocComment a raw Javadoc comment (i.e. including surrounding
     *                       asterisks)
     * @return a list of value tokens
     */
    public static List<ValueTokens> getJavadocLiterals(
            String javadocComment
    ) {
        List<ValueTokens> valueList = new ArrayList<>();
        valueList.addAll(getAllNumericLiteralsInJavadoc(javadocComment));
        valueList.addAll(getAllStringLiteralsInJavadoc(javadocComment));
        return valueList;
    }

    /**
     * Gets the type name of a given parameter. Handles special cases with
     * generic types, primitives, arrays, and reference types. Uses an upper
     * bound for generic types if possible. Includes enclosing classes for
     * inner classes (separated by "."), but removes package names. These
     * modifications are made for compatibility with the XText grammar.
     *
     * @param jpClass the declaring class of {@code jpCallable}
     * @param jpCallable the method with a parameter {@code jpParameter}
     * @param jpParameter a parameter of {@code jpCallable}
     * @return the type name of the given parameter
     */
    private static String getParameterTypeName(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable,
            Parameter jpParameter
    ) {
        Type parameterType = jpParameter.getType();
        try {
            StringBuilder className = new StringBuilder();
            ResolvedType resolvedType = parameterType.resolve();
            if (parameterType.isPrimitiveType()) {
                className.append(parameterType.asPrimitiveType().asString());
            } else if (parameterType.isReferenceType()) {
                if (JavaParserUtils.isTypeVariable(resolvedType)) {
                    // get type bound for type parameters
                    className.append(TypeUtils.getJDoctorSimpleNameFromSourceCode(jpClass, jpCallable, jpParameter));
                } else {
                    className.append(JavaParserUtils.getTypeWithoutPackages(resolvedType));
                }
            } else {
                throw new IllegalArgumentException("Unable to parse parameter type of " + parameterType);
            }
            if (jpParameter.isVarArgs()) {
                className.append("[]");
            }
            return className.toString();
        } catch (UnsolvedSymbolException e) {
            logger.error(String.format("UnsolvedSymbolException when evaluating %s parameter type.", parameterType));
            StringBuilder className = new StringBuilder();
            className.append(parameterType.asClassOrInterfaceType().getNameAsString());
            if (jpParameter.isVarArgs()) {
                className.append("[]");
            }
            return className.toString();
        }
    }

    /**
     * Collects information about an individual parameter of a given method.
     *
     * @param jpClass the declaring class
     * @param jpCallable a method
     * @param parameter a parameter of {@code jpCallable}
     * @return a representation of the given parameter for use by XText.
     * Returns null if unable to resolve the parameter or unable to recognize
     * the given type.
     */
    public static MethodArgumentTokens getMethodArgumentTokens(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable,
            Parameter parameter
    ) {
        Type parameterType = parameter.getType();
        String parameterTypeName = getParameterTypeName(jpClass, jpCallable, parameter);
        try {
            ResolvedType resolvedParameterType = parameterType.resolve();
            if (
                    JavaParserUtils.isTypeVariable(resolvedParameterType) ||
                    resolvedParameterType.isArray() ||
                    resolvedParameterType.isPrimitive()
            ) {
                // ignore package for arrays, type parameters, and primitives.
                return new MethodArgumentTokens(parameter.getNameAsString(), "", parameterTypeName);
            } else if (parameterType.resolve().isReferenceType()) {
                String className = JavaParserUtils.getTypeWithoutPackages(parameterType.resolve().asReferenceType());
                String parameterPackageName = parameterType.resolve().asReferenceType().getQualifiedName()
                        .replace(String.format(".%s", className), "");
                return new MethodArgumentTokens(
                        parameter.getNameAsString(),
                        parameterPackageName,
                        parameterTypeName
                );
            }
        } catch (UnsolvedSymbolException e) {
            logger.error("Unable to generate MethodArgumentTokens for argument " + parameter);
        }
        return null;
    }

    /**
     * Collects information about each parameter of a given method.
     *
     * @param jpClass the declaring class
     * @param jpCallable a method
     * @return a list of method argument tokens
     */
    public static List<MethodArgumentTokens> getMethodArgumentTokens(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable
    ) {
        return jpCallable.getParameters()
                .stream()
                .map(p -> getMethodArgumentTokens(jpClass, jpCallable, p))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Gets the source code of a given method or constructor.
     *
     * @param jpCallable a method or constructor
     * @return a string representation of the source code
     */
    public static String getCallableSourceCode(
            CallableDeclaration<?> jpCallable
    ) {
        String jpSignature = JavaParserUtils.getCallableSignature(jpCallable);
        Optional<BlockStmt> jpBody = jpCallable instanceof MethodDeclaration
                ? ((MethodDeclaration) jpCallable).getBody()
                : Optional.ofNullable(((ConstructorDeclaration) jpCallable).getBody());
        return jpSignature + (jpBody.isEmpty() ? ";" : jpBody.get().toString());
    }

    /**
     * Collects information about all non-private, static, non-void methods
     * of a given compilation unit. Does not include constructors as they are
     * not static.
     *
     * @param cu a compilation unit of a Java file
     * @return a list of method tokens
     * @throws PackageDeclarationNotFoundException if the package of the
     * compilation unit is not found
     */
    private static List<MethodTokens> getNonPrivateStaticNonVoidMethods(
            CompilationUnit cu
    ) throws PackageDeclarationNotFoundException {
        List<MethodTokens> methodList = new ArrayList<>();
        // get package name.
        String packageName = JavaParserUtils.getPackageDeclaration(cu).getNameAsString();
        // iterate over each class in the compilation unit.
        for (TypeDeclaration<?> jpClass : cu.findAll(TypeDeclaration.class)) {
            String className = jpClass.getNameAsString();
            for (MethodDeclaration jpMethod : jpClass.findAll(MethodDeclaration.class)) {
                if (!jpMethod.isPrivate() && jpMethod.isStatic() && !jpMethod.getType().isVoidType()) {
                    methodList.add(new MethodTokens(
                            jpMethod.getNameAsString(),
                            packageName,
                            className,
                            JavaParserUtils.getMethodSignature(jpMethod)
                    ));
                }
            }
        }
        return methodList;
    }

    /**
     * Collects information about all non-private, static attributes (fields)
     * in a given compilation unit.
     *
     * @param cu a compilation unit of a Java file
     * @return a list of attribute tokens
     * @throws PackageDeclarationNotFoundException if the package
     * {@link PackageDeclaration} of the compilation unit is not found
     */
    private static List<AttributeTokens> getNonPrivateStaticAttributes(
            CompilationUnit cu
    ) throws PackageDeclarationNotFoundException {
        List<AttributeTokens> attributeList = new ArrayList<>();
        // get package name.
        String packageName = JavaParserUtils.getPackageDeclaration(cu).getNameAsString();
        // iterate over each class in the compilation unit.
        for (TypeDeclaration<?> jpClass : cu.findAll(TypeDeclaration.class)) {
            String className = jpClass.getNameAsString();
            for (FieldDeclaration jpField : jpClass.findAll(FieldDeclaration.class)) {
                if (!jpField.isPrivate() && jpField.isStatic()) {
                    // add each variable in declaration.
                    for (VariableDeclarator jpVariable : jpField.getVariables()) {
                        attributeList.add(new AttributeTokens(
                                jpVariable.getNameAsString(),
                                packageName,
                                className,
                                JavaParserUtils.getVariableDeclaration(jpField, jpVariable)
                        ));
                    }
                }
            }
        }
        return attributeList;
    }

    /**
     * Collects information about all Javadoc tags in a given compilation
     * unit. Does not traverse inner classes.
     *
     * @param cu a compilation unit of a Java file
     * @param fileContent the content of the Java file
     * @return a list of JavadocTags
     * @throws PackageDeclarationNotFoundException if the package
     * {@link PackageDeclaration} of the compilation unit is not found
     */
    private static List<TagAndText> getCuTags(
            CompilationUnit cu,
            String fileContent
    ) throws PackageDeclarationNotFoundException {
        List<TagAndText> tagList = new ArrayList<>();
        // iterate through each class.
        List<TypeDeclaration<?>> jpClasses = cu.getTypes();
        for (TypeDeclaration<?> jpClass : jpClasses) {
            // iterate through each method.
            List<CallableDeclaration<?>> jpCallables = new ArrayList<>();
            jpCallables.addAll(jpClass.getMethods());
            jpCallables.addAll(jpClass.getConstructors());
            for (CallableDeclaration<?> jpCallable : jpCallables) {
                // iterate through each Javadoc tag.
                Optional<Javadoc> optionalJavadoc = jpCallable.getJavadoc();
                if (optionalJavadoc.isEmpty()) {
                    continue;
                }
                List<JavadocBlockTag> blockTags = optionalJavadoc.get().getBlockTags();
                for (JavadocBlockTag blockTag : blockTags) {
                    // get info for each Javadoc tag.
                    String name = blockTag.getName().orElse("");
                    String content = blockTag.getContent().toText();
                    OracleType oracleType = switch (blockTag.getTagName()) {
                        case "param" -> OracleType.PRE;
                        case "return" -> OracleType.NORMAL_POST;
                        case "throws", "exception" -> OracleType.EXCEPT_POST;
                        default -> null;
                    };
                    if (oracleType == null) {
                        continue;
                    }
                    // add new tag.
                    tagList.add(new TagAndText(
                            fileContent,
                            jpClass,
                            jpCallable,
                            oracleType,
                            name,
                            content
                    ));
                }
            }
        }
        return tagList;
    }

    /** Files that should be ignored. */
    private static final Set<String> ignoreFiles = Set.of(".DS_Store", "package-info.java");

    /**
     * Finds all ".java" files in a given directory. Files are filtered by an
     * ad-hoc list of files to ignore (see "data/repos/ignore_file.json").
     *
     * @param dir the root directory
     * @return a list of all java files as Paths with parent directory names
     * @see DatasetUtils#ignoreFiles
     */
    public static List<Path> getJavaFiles(Path dir) {
        List<Path> javaFiles = FileUtils.getAllJavaFilesUnderDirectory(dir);
        javaFiles.removeIf(f -> ignoreFiles.contains(f.getFileName().toString()));
        return javaFiles;
    }

    /**
     * Collects information about all classes in a project from a given
     * source path.
     *
     * @param sourceDir the project root directory
     * @return a list of class tokens
     */
    public static List<ClassTokens> getProjectClassTokens(
            Path sourceDir
    ) {
        List<ClassTokens> projectClasses = new ArrayList<>();
        List<Path> javaFiles = getJavaFiles(sourceDir);
        // iterate through each file and add class tokens.
        for (Path javaFile : javaFiles) {
            Optional<CompilationUnit> cu = JavaParserUtils.getCompilationUnit(javaFile.toAbsolutePath());
            if (cu.isPresent()) {
                try {
                    projectClasses.addAll(getClassTokens(cu.get()));
                } catch (PackageDeclarationNotFoundException e) {
                    throw new Error("Unable to resolve package for class " + cu);
                }
            }
        }
        return projectClasses;
    }

    /**
     * Collects information about all non-private, static, non-void methods
     * in a project from a given source path.
     *
     * @param sourceDir the project root directory
     * @return a list of method tokens
     */
    public static List<MethodTokens> getProjectNonPrivateStaticNonVoidMethodsTokens(
            Path sourceDir
    ) {
        List<MethodTokens> projectMethods = new ArrayList<>();
        List<Path> javaFiles = getJavaFiles(sourceDir);
        // iterate through each file and add method tokens.
        for (Path javaFile : javaFiles) {
            Optional<CompilationUnit> cu = JavaParserUtils.getCompilationUnit(javaFile.toAbsolutePath());
            if (cu.isPresent()) {
                try {
                    projectMethods.addAll(getNonPrivateStaticNonVoidMethods(cu.get()));
                } catch (PackageDeclarationNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        return projectMethods;
    }

    /**
     * Collects information about all non-private, static attributes
     * in a project from a given source path.
     *
     * @param sourceDir the project root directory
     * @return a list of attribute tokens
     */
    public static List<AttributeTokens> getProjectNonPrivateStaticAttributesTokens(
            Path sourceDir
    ) {
        List<AttributeTokens> attributeList = new ArrayList<>();
        List<Path> javaFiles = getJavaFiles(sourceDir);
        // iterate through each file and add attribute tokens.
        for (Path javaFile : javaFiles) {
            Optional<CompilationUnit> cu = JavaParserUtils.getCompilationUnit(javaFile.toAbsolutePath());
            if (cu.isPresent()) {
                try {
                    attributeList.addAll(getNonPrivateStaticAttributes(cu.get()));
                } catch (PackageDeclarationNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        return attributeList;
    }

    /**
     * Collects information about all Javadoc tags in the project at a given
     * source path.
     *
     * @param sourceDir the project root directory
     * @return a list of JavadocTags
     */
    public static List<TagAndText> getProjectTagsTokens(
            Path sourceDir
    ) {
        List<TagAndText> tagList = new ArrayList<>();
        List<Path> javaFiles = getJavaFiles(sourceDir);
        // iterate through each file and add Javadoc tags.
        for (Path javaFile : javaFiles) {
            Path absoluteJavaFile = javaFile.toAbsolutePath();
            String fileContent = FileUtils.readString(absoluteJavaFile);
            Optional<CompilationUnit> cu = JavaParserUtils.getCompilationUnit(absoluteJavaFile);
            if (cu.isPresent()) {
                try {
                    tagList.addAll(getCuTags(cu.get(), fileContent));
                } catch (PackageDeclarationNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        return tagList;
    }

    /**
     * Collects information for all non-private, non-static, non-void methods
     * available (i.e., defined in the class or supertype) to a given type.
     *
     * @param jpResolvedType a type
     * @return a list of method tokens for all available methods. Returns an
     * empty list if the given type is primitive.
     */
    public static List<MethodTokens> getMethodsFromType(
            ResolvedType jpResolvedType
    ) {
        List<MethodTokens> methodList = new ArrayList<>();
        if (jpResolvedType.isArray()) {
            // array type (see dataset/repose/array_methods.json).
            List<List<String>> arrayMethods;
            arrayMethods = FileUtils.readJSONList(TrattoPath.ARRAY_METHODS.getPath())
                    .stream()
                    .map(e -> ((List<?>) e)
                            .stream()
                            .map(o -> (String) o)
                            .toList())
                    .toList();
            methodList.addAll(arrayMethods
                    .stream()
                    .map(m -> new MethodTokens(m.get(0), "", jpResolvedType.describe(), m.get(1)))
                    .toList());
        } else if (jpResolvedType.isReferenceType() || jpResolvedType.isTypeVariable()) {
            ResolvedType type = jpResolvedType;
            if (JavaParserUtils.isTypeVariable(type)) {
                type = JavaParserUtils.getObjectType();
            }
            methodList.addAll(getMethodsFromResolvedReferenceType(type.asReferenceType()));
        } else if (jpResolvedType.isWildcard()) {
            ResolvedType type = jpResolvedType.asWildcard().getBoundedType();
            if (type.isReferenceType()) {
                methodList.addAll(getMethodsFromResolvedReferenceType(type.asReferenceType()));
            }
        } else if (!(jpResolvedType.isPrimitive() || jpResolvedType.isVoid())) {
            // unknown type.
            logger.error(String.format(
                    "Return type %s different from ReferenceType, PrimitiveType, " +
                            "ArrayType, TypeVariable, VoidType and WildcardType not yet supported%n", jpResolvedType
            ));
        }
        return methodList;
    }

    /**
     * Collects information for all non-private, non-static, non-void methods
     * available to a given type, which must be a reference type.
     * @param type a resolved reference type
     * @throws IllegalArgumentException if the given type is primitive, or array,
     * or void, or a type variable, or a wildcard type
     * @return a list of method tokens for all available methods. Returns an
     * empty list if it was impossible to get the methods of the given type. This
     * happens, for example, for types that are instance of {@link
     * com.github.javaparser.symbolsolver.javassistmodel.JavassistAnnotationDeclaration
     * JavassistAnnotationDeclaration}.
     */
    private static List<MethodTokens> getMethodsFromResolvedReferenceType(
            ResolvedReferenceType type
    ) throws IllegalArgumentException {
        if (type.isPrimitive() || type.isArray() || type.isVoid() || type.isTypeVariable() || type.isWildcard()) {
            throw new IllegalArgumentException("Type must be a reference type.");
        }
        try {
            return type.getAllMethods()
                    .stream()
                    .map(MethodUsage::new)
                    .filter(JavaParserUtils::isNonPrivateNonStaticNonVoidMethod)
                    .map(MethodTokens::new)
                    .toList();
        } catch (UnsupportedOperationException e) {
            logger.warn("Unable to get methods from type {}", type);
            return Collections.emptyList();
        }
    }

    /**
     * Collects information for all non-private, non-static, non-void methods
     * available to a given type. This method is a wrapper method for
     * {@link DatasetUtils#getMethodsFromType(ResolvedType)} which first
     * attempts to resolve the given type. Returns an empty list if unable
     * to resolve the type, for example, if the given type is primitive.
     *
     * @param jpType a type
     * @return a list of method tokens for all available methods. Returns an
     * empty list if the given type is primitive.
     */
    private static List<MethodTokens> getMethodsFromType(
            Type jpType
    ) {
        ResolvedType jpResolvedType;
        try {
            jpResolvedType = jpType.resolve();
        } catch (UnsolvedSymbolException e) {
            return Collections.emptyList();
        }
        return getMethodsFromType(jpResolvedType);
    }

    /**
     * Converts a field declaration (which may declare multiple variables) to
     * a list of records of attribute tokens. his method is a special case of
     * {@link DatasetUtils#convertFieldDeclarationToAttributeTokens(List)}
     * using available information from the implementation of
     * {@link JavaParserFieldDeclaration}. If possible, declarations with
     * multiple fields are split into individual records.
     *
     * @param resolvedField a JavaParser resolved field declaration
     * @return the corresponding list of attribute tokens
     */
    private static List<AttributeTokens> convertJavaParserFieldDeclarationToAttributeTokens(
            JavaParserFieldDeclaration resolvedField
    ) {
        List<AttributeTokens> attributeList = new ArrayList<>();
        FieldDeclaration jpField = resolvedField.getWrappedNode();
        for (VariableDeclarator jpVariable : jpField.getVariables()) {
            attributeList.add(new AttributeTokens(
                    jpVariable.getNameAsString(),
                    resolvedField.declaringType().getPackageName(),
                    resolvedField.declaringType().getClassName(),
                    JavaParserUtils.getVariableDeclaration(jpField, jpVariable)
            ));
        }
        return attributeList;
    }

    /**
     * Converts a field declaration to a record. This method is a special case
     * of {@link DatasetUtils#convertFieldDeclarationToAttributeTokens(List)}
     * using available information from the implementation of
     * {@link ReflectionFieldDeclaration}.
     *
     * @param resolvedField a reflection resolved field declaration
     * @return the corresponding attribute token record
     */
    private static AttributeTokens convertReflectionFieldDeclarationToAttributeTokens(
            ReflectionFieldDeclaration resolvedField
    ) {
        String signature;
        try {
            Field f = resolvedField.getClass().getDeclaredField("field");
            f.setAccessible(true);
            Field field = (Field) f.get(resolvedField);
            signature = JavaParserUtils.getFieldDeclaration(resolvedField, field.getModifiers());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            signature = JavaParserUtils.getFieldDeclaration(resolvedField);
        }
        return new AttributeTokens(
                resolvedField.getName(),
                resolvedField.declaringType().getPackageName(),
                resolvedField.declaringType().getClassName(),
                signature
        );
    }

    /**
     * Converts a list of field declarations to a list of records of attribute
     * tokens. If possible, declarations with multiple fields are split into
     * individual records. The "fieldDeclaration" includes modifiers, type,
     * name, and initial value (if applicable).
     *
     * @param resolvedFields a list of field declarations
     * @return the corresponding list of attribute token records
     */
    private static List<AttributeTokens> convertFieldDeclarationToAttributeTokens(
            List<ResolvedFieldDeclaration> resolvedFields
    ) {
        List<AttributeTokens> fieldList = new ArrayList<>();
        for (ResolvedFieldDeclaration resolvedField : resolvedFields) {
            if (resolvedField instanceof JavaParserFieldDeclaration) {
                // use JavaParserFieldDeclaration to get a more detailed signature.
                fieldList.addAll(convertJavaParserFieldDeclarationToAttributeTokens((JavaParserFieldDeclaration) resolvedField));
            } else if (resolvedField instanceof ReflectionFieldDeclaration) {
                // use ReflectionFieldDeclaration to get a more detailed signature.
                fieldList.add(convertReflectionFieldDeclarationToAttributeTokens((ReflectionFieldDeclaration) resolvedField));
            } else {
                // use default ResolvedFieldDeclaration.
                fieldList.add(new AttributeTokens(
                        resolvedField.getName(),
                        resolvedField.declaringType().getPackageName(),
                        resolvedField.declaringType().getClassName(),
                        JavaParserUtils.getFieldDeclaration(resolvedField)
                ));
            }
        }
        return fieldList;
    }

    /**
     * Creates a ClassTokens record corresponding to a resolved type.
     *
     * @param resolvedType a type
     * @return the corresponding class tokens
     */
    private static ClassTokens getResolvedTypeClassTokens(ResolvedType resolvedType) {
        // get type pair from resolved type returns (packageName, className)
        List<String> reversedTokens = JavaParserUtils.getTypePairFromResolvedType(resolvedType).toList()
                .stream()
                .map(t -> (String) t)
                .toList();
        return new ClassTokens(reversedTokens.get(1), reversedTokens.get(0));
    }

    /**
     * Gets information for all non-private, non-static fields (attributes)
     * available to a given type.
     *
     * @param jpResolvedType the given type
     * @return a list of attribute tokens
     */
    public static List<AttributeTokens> getFieldsFromType(
            ResolvedType jpResolvedType
    ) {
        List<AttributeTokens> fieldList = new ArrayList<>();
        if (jpResolvedType.isArray()) {
            // add array field (length).
            ClassTokens typeTokens = getResolvedTypeClassTokens(jpResolvedType);
            fieldList.add(new AttributeTokens(
                    "length",
                    typeTokens.packageName(),
                    typeTokens.className(),
                    "public final int length;"
            ));
        } else if (jpResolvedType.isReferenceType()) {
            // add all fields accessible to the class.
            Optional<ResolvedReferenceTypeDeclaration> jpResolvedDeclaration = jpResolvedType.asReferenceType().getTypeDeclaration();
            if (jpResolvedDeclaration.isPresent()) {
                fieldList.addAll(getFieldsFromResolvedReferenceTypeDeclaration(jpResolvedDeclaration.get()));
            } else {
                // unable to recover type declaration.
                logger.error(String.format(
                        "Unable to analyze the resolved type %s: " +
                                "resolved type declaration not found.", jpResolvedType
                ));
            }
        } else if (jpResolvedType.isWildcard()) {
            ResolvedType type = jpResolvedType.asWildcard().getBoundedType();
            if (type.isReferenceType()) {
                Optional<ResolvedReferenceTypeDeclaration> jpResolvedDeclaration = type.asReferenceType().getTypeDeclaration();
                if (jpResolvedDeclaration.isPresent()) {
                    fieldList.addAll(getFieldsFromResolvedReferenceTypeDeclaration(jpResolvedDeclaration.get()));
                }
            }
        } else if (!(jpResolvedType.isPrimitive() || jpResolvedType.isVoid() || jpResolvedType.isTypeVariable())) {
            // unknown type.
            logger.error(String.format(
                    "Return type %s different from ReferenceType, PrimitiveType, " +
                            "ArrayType, TypeVariable, VoidType and WildcardType not yet supported%n", jpResolvedType
            ));
        }
        return fieldList;
    }

    /**
     * Collects information for all non-private, non-static fields (attributes)
     * available to a given resolved reference type declaration
     * @param type a resolved reference type declaration
     * @return a list of attribute tokens for all available attributes.
     */
    private static List<AttributeTokens> getFieldsFromResolvedReferenceTypeDeclaration(
            ResolvedReferenceTypeDeclaration type
    ) {
        return convertFieldDeclarationToAttributeTokens(type.getAllFields()
                .stream()
                .filter(JavaParserUtils::isNonPrivateNonStaticAttribute)
                .toList());
    }

    /**
     * Gets information for all non-private, non-static attributes available
     * to a given type. This method is a wrapper of
     * {@link DatasetUtils#getFieldsFromType(ResolvedType)} which attempts to
     * resolve the given type. Returns an empty list if unable to resolve the
     * given type (i.e. the class path does not include the given type).
     *
     * @param jpType the given type
     * @return a list of attribute tokens
     */
    private static List<AttributeTokens> getFieldsFromType(
            Type jpType
    ) {
        ResolvedType jpResolvedType;
        try {
            jpResolvedType = jpType.resolve();
        } catch (UnsolvedSymbolException e) {
            logger.error(String.format("Unable to generate attribute tokens from type %s", jpType));
            return Collections.emptyList();
        }
        return getFieldsFromType(jpResolvedType);
    }

    /**
     * Gets information for all non-private, non-static attributes available
     * to a given parameter.
     *
     * @param jpParameter the given parameter
     * @return a list of attribute tokens
     */
    public static List<AttributeTokens> getFieldsFromParameter(
            Parameter jpParameter
    ) {
        if (jpParameter.isVarArgs()) {
            ClassTokens typeTokens = getResolvedTypeClassTokens(jpParameter.getType().resolve());
            return List.of(new AttributeTokens(
                    "length",
                    typeTokens.packageName(),
                    typeTokens.className() + "[]",
                    "public final int length;"
            ));
        }
        // wrapper of previous getFieldsFromType method to consider varargs as arrays.
        return getFieldsFromType(jpParameter.getType());
    }

    /**
     * Collects information for all methods available to any type in a
     * given method's signature. Includes methods visible to:
     *  (1) the base class (this).
     *  (2) the arguments of the method.
     *  (3) the class of the method return type.
     *
     * @param jpClass the declaring class
     * @param jpCallable a method
     * @return a list of method tokens
     */
    public static List<MethodTokens> getTokensMethodVariablesNonPrivateNonStaticNonVoidMethods(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable
    ) throws JPClassNotFoundException {
        // add all methods of the base class (receiverObjectID -> this).
        List<MethodUsage> allReceiverMethods = JavaParserUtils.getAllMethods(jpClass)
                .stream()
                .filter(JavaParserUtils::isNonPrivateNonStaticNonVoidMethod)
                .toList();
        List<MethodTokens> methodList = new ArrayList<>(allReceiverMethods.stream().map(MethodTokens::new).toList());
        // add all methods of parameters.
        for (Parameter jpParam : jpCallable.getParameters()) {
            methodList.addAll(getMethodsFromType(jpParam.getType()));
        }
        // add all methods of return type.
        if (jpCallable instanceof MethodDeclaration) {
            methodList.addAll(getMethodsFromType(((MethodDeclaration) jpCallable).getType()));
        }
        // add Object methods.
        methodList.addAll(JavaParserUtils.getObjectType().asReferenceType().getAllMethods()
                .stream()
                .map(MethodUsage::new)
                .filter(JavaParserUtils::isNonPrivateNonStaticNonVoidMethod)
                .map(MethodTokens::new)
                .toList()
        );
        return withoutDuplicates(methodList);
    }

    /**
     * Collects information for all non-private, non-static attributes
     * for a given method variable. Includes attributes visible to:
     *  (1) the class that declares the method.
     *  (2) the arguments of the method.
     *  (3) the class of the method return type.
     *
     * @param jpClass the declaring class
     * @param jpCallable a method
     * @return a list of attribute tokens
     * @throws JPClassNotFoundException if the declaring class is not
     * resolvable
     */
    public static List<AttributeTokens> getTokensMethodVariablesNonPrivateNonStaticAttributes(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable
    ) throws JPClassNotFoundException {
        // add all fields of the base class (receiverObjectID -> this).
        List<ResolvedFieldDeclaration> allReceiverFields = JavaParserUtils.getAllFields(jpClass)
                .stream()
                .filter(JavaParserUtils::isNonPrivateNonStaticAttribute)
                .toList();
        List<AttributeTokens> attributeList = new ArrayList<>(convertFieldDeclarationToAttributeTokens(allReceiverFields));
        // add all fields of parameters.
        for (Parameter jpParam : jpCallable.getParameters()) {
            attributeList.addAll(getFieldsFromParameter(jpParam));
        }
        // add all fields of return type.
        if (jpCallable instanceof MethodDeclaration) {
            attributeList.addAll(getFieldsFromType(((MethodDeclaration) jpCallable).getType()));
        }
        return withoutDuplicates(attributeList);
    }

    /**
     * Gets all subexpressions of a given expression. A subexpression is defined
     * as either a field access or method call. For example,
     * {@code this.getStudent().id} has subexpressions: {@code this},
     * {@code this.getStudent()}, and {@code this.getStudent().id}.
     *
     * @param expression a Java expression
     * @return all subexpressions in the given expression
     */
    private static List<String> getSubexpressions(String expression) {
        return Parser.getInstance().getAllMethodsAndAttributes(expression)
                .stream()
                .map(e -> {
                    List<String> tokens = Splitter.split(e);
                    return String.join("", tokens).replaceAll("receiverObjectID", "this");
                })
                .collect(Collectors.toList());
    }

    /**
     * Collects information for all non-private, non-static, non-void methods
     * for a given oracle. Includes accessible methods for each type of each
     * sub-expression within an oracle. For example, the oracle:
     *     "this.getName().length() == 0"
     * has methods available to (1) the declaring class (i.e., the type of
     * this), (2) the return type of getName(), and (3) the return type of
     * length().
     *
     * @param oracleDatapoint an oracle data point which must have the following
     *                        attributes populated:
     *                        <ul>
     *                        <li>classSourceCode</li>
     *                        <li>className</li>
     *                        <li>methodSourceCode</li>
     *                        <li>tokensProjectClasses</li>
     *                        <li>oracle</li>
     *                        </ul>
     * @return a list of method tokens
     */
    public static List<MethodTokens> getTokensOracleVariablesNonPrivateNonStaticNonVoidMethods(OracleDatapoint oracleDatapoint) {
        List<MethodTokens> methodList = new ArrayList<>();
        List<String> subexpressions = getSubexpressions(oracleDatapoint.getOracle());
        for (String subexpression : subexpressions) {
            ResolvedType resolvedType;
            try {
                resolvedType = JavaParserUtils.getResolvedTypeOfExpression(subexpression, oracleDatapoint);
            } catch (UnsolvedSymbolException e) {
                resolvedType = JavaParserUtils.getObjectType();
            }
            if (!resolvedType.isPrimitive()) {
                methodList.addAll(getMethodsFromType(JavaParserUtils.getObjectType()));
            }
            methodList.addAll(getMethodsFromType(resolvedType));
        }
        return withoutDuplicates(methodList);
    }

    /**
     * Collects information for all non-private, non-static attributes for a
     * given oracle. Includes attributes visible to each sub-expression within
     * an oracle.
     *
     * @param oracleDatapoint an oracle data point which must have the following
     *                        attributes populated:
     *                        <ul>
     *                        <li>classSourceCode</li>
     *                        <li>className</li>
     *                        <li>methodSourceCode</li>
     *                        <li>tokensProjectClasses</li>
     *                        <li>oracle</li>
     *                        </ul>
     * @return a list of attribute tokens
     */
    public static List<AttributeTokens> getTokensOracleVariablesNonPrivateNonStaticAttributes(OracleDatapoint oracleDatapoint) {
        List<AttributeTokens> attributeList = new ArrayList<>();
        List<String> subexpressions = getSubexpressions(oracleDatapoint.getOracle());
        for (String subexpression : subexpressions) {
            ResolvedType resolvedType;
            try {
                resolvedType = JavaParserUtils.getResolvedTypeOfExpression(subexpression, oracleDatapoint);
            } catch (UnsolvedSymbolException e) {
                resolvedType = JavaParserUtils.getObjectType();
            }
            attributeList.addAll(getFieldsFromType(resolvedType));
        }
        return withoutDuplicates(attributeList);
    }

    /**
     * Checks if a JDoctor parameter and JavaParser parameter are equivalent.
     * Primarily handles issues regarding different representations of generic
     * types between JDoctor and JavaParser. Handles four cases:
     * <ul>
     *     <li>{@code jDoctorParam} and {@code jpParam} are equal</li>
     *     <li>Both {@code jDoctorParam} and {@code jpParam} represent
     *     "standard" objects, defined as "Object" and "Comparable".</li>
     *     <li>{@code jpParam} is a type parameter (not an array) and
     *     {@code jDoctorParam} is a standard object.</li>
     *     <li>{@code jpParam} is a type parameter (array) and
     *     {@code jDoctorParam} is an array of standard objects.</li>
     * </ul>
     * This method only works for unbounded generics.
     *
     * @param jDoctorParam the JDoctor parameter name
     * @param jpParam a JavaParser parameter name
     * @param jpCallable the declaring method with parameter {@code jpParam}
     * @param jpClass the declaring class of {@code jpCallable}
     * @return returns true if any of the aforementioned cases are true
     */
    private static boolean jpParamEqualsJDoctorParam(
            String jDoctorParam,
            String jpParam,
            CallableDeclaration<?> jpCallable,
            TypeDeclaration<?> jpClass
    ) {
        if (jDoctorParam.equals(jpParam)) {
            return true;
        }
        boolean jDoctorParamIsObjectOrComparable = TypeUtils.isObjectOrComparable(jDoctorParam);
        boolean jDoctorParamIsObjectOrComparableArray = TypeUtils.isObjectOrComparableArray(jDoctorParam);
        boolean jpParamIsObjectOrComparable = TypeUtils.isObjectOrComparable(jpParam);
        boolean jpParamIsArray = jpParam.endsWith("[]");
        boolean jpParamIsGeneric = JavaParserUtils.isTypeVariable(jpParam, jpCallable, jpClass);
        // the cases mentioned in the javadoc have been factored for brevity.
        return (jDoctorParamIsObjectOrComparable && jpParamIsObjectOrComparable) ||
                ((jpParamIsGeneric && !jpParamIsArray) && jDoctorParamIsObjectOrComparable) ||
                ((jpParamIsGeneric && jpParamIsArray) && jDoctorParamIsObjectOrComparableArray);
    }

    /**
     * Evaluates whether a list of JDoctor parameters and a list of JavaParser
     * parameters are equal. Primarily handles issues regarding different
     * representations of generic types between JDoctor and JavaParser.
     *
     * @param jDoctorParamList list of JDoctor parameters
     * @param jpParamList list of JavaParser parameters
     * @param jpCallable method corresponding to jpParamList
     * @param jpClass the declaring class of jpCallable
     * @return true iff the two lists represent the same parameters
     */
    private static boolean jpParamListEqualsJDoctorParamList(
            List<String> jDoctorParamList,
            List<String> jpParamList,
            CallableDeclaration<?> jpCallable,
            TypeDeclaration<?> jpClass
    ) {
        if (jDoctorParamList.size() != jpParamList.size()) {
            return false;
        }
        for (int i = 0; i < jDoctorParamList.size(); i++) {
            String jDoctorParam = jDoctorParamList.get(i);
            String jpParam = jpParamList.get(i);
            if (!jpParamEqualsJDoctorParam(jDoctorParam, jpParam, jpCallable, jpClass)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets the method/constructor from a given class given a specific name
     * and a list of parameters.
     *
     * @param jpClass the declaring class
     * @param methodName the name of the method
     * @param params the parameters of the desired method.
     *                        Parameter type names follow JDoctor format.
     * @return the corresponding method (if it exists). Returns null if no
     * such method exists.
     */
    public static CallableDeclaration<?> getCallableDeclaration(
            TypeDeclaration<?> jpClass,
            String methodName,
            List<String> params
    ) {
        List<CallableDeclaration<?>> jpCallables = new ArrayList<>();
        jpCallables.addAll(jpClass.getMethods());
        jpCallables.addAll(jpClass.getConstructors());
        // iterate through each member in the class.
        for (CallableDeclaration<?> jpCandidate : jpCallables) {
            // check if the method names are equal.
            if (jpCandidate.getNameAsString().equals(methodName)) {
                // check if parameters are equal.
                List<String> candidateParamList = jpCandidate.getParameters()
                        .stream()
                        .map(p -> TypeUtils.getJDoctorSimpleNameFromSourceCode(jpClass, jpCandidate, p))
                        .toList();
                if (jpParamListEqualsJDoctorParamList(
                        params,
                        candidateParamList,
                        jpCandidate,
                        jpClass
                )) {
                    return jpCandidate;
                }
            }
        }
        // return null if no such method or constructor is found.
        return null;
    }

    /**
     * Gets the type declaration of a class from a given compilation unit.
     *
     * @param cu the compilation unit of a file
     * @param className the name of the desired class
     * @return returns the type declaration corresponding to the given class
     * name in a compilation unit (if it exists). Returns null if no such
     * class exists.
     */
    public static TypeDeclaration<?> getTypeDeclaration(
            CompilationUnit cu,
            String className
    ) {
        // get all classes in the compilation unit.
        List<TypeDeclaration<?>> typeList = cu.getTypes();
        // return null if no classes are found.
        if (typeList == null) {
            return null;
        }
        // iterate through classes to find the corresponding class.
        for (TypeDeclaration<?> jpClass : typeList) {
            if (jpClass.getNameAsString().equals(className)) {
                return jpClass;
            }
        }
        return null;
    }

    /**
     * Gets the path to the class that declares the method of the given
     * JDoctor condition relative to a source path. This method is not
     * accurate for inner classes, which are excluded in the Oracles Dataset.
     *
     * @param operation an operation of a JDoctor condition
     * @param sourceDir the source path of the relevant project
     * @return the path of the class in the JDoctor condition
     */
    private static Path getClassPath(
            Operation operation,
            Path sourceDir
    ) {
        return sourceDir.resolve(operation.className().replace(".", "/") + ".java");
    }

    /**
     * Gets the compilation unit of the class that declares the method of the
     * given JDoctor condition.
     *
     * @param operation an operation representation of a JDoctor condition
     * @param sourceDir the source path of the relevant project
     * @return an optional JavaParser compilation unit {@link CompilationUnit}
     * corresponding to the class of the JDoctor condition, if it is found.
     * Otherwise, the method returns an empty optional.
     */
    public static Optional<CompilationUnit> getOperationCompilationUnit(
            Operation operation,
            Path sourceDir
    ) {
        Path classPath = getClassPath(operation, sourceDir);
        return JavaParserUtils.getCompilationUnit(classPath);
    }

    /**
     * Gets the source code of the Java file, corresponding to the class of a
     * JDoctor condition. Returns an empty optional type if unable to
     *
     * @param operation a JDoctor condition operation
     * @param sourcePath the path to the corresponding source code of the Java
     *                   project
     * @return the source code of the class in the JDoctor condition
     */
    public static Optional<String> getOperationClassSource(
            Operation operation,
            Path sourcePath
    ) {
        try {
            Path classPath = getClassPath(operation, sourcePath);
            return Optional.of(FileUtils.readString(classPath));
        } catch (Error e) {
            return Optional.empty();
        }
    }

    /**
     * Gets the method/constructor name of an operation.
     *
     * @param operation a JDoctor condition operation
     * @return the method/constructor name of the operation
     */
    public static String getOperationMethodName(
            Operation operation
    ) {
        if (operation.methodName().equals(operation.className())) {
            return TypeUtils.getInnermostClassNameFromClassGetName(operation.methodName());
        }
        return operation.methodName();
    }

    /**
     * Chunks a list of objects into multiple lists.
     *
     * @param list the flattened list of objects
     * @param chunkSize the number of objects per chunk
     * @return a list of lists of the type of list elements
     * @param <T> the type of list elements
     */
    public static <T> List<List<T>> splitListIntoSubLists(List<T> list, int chunkSize) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < list.size(); i += chunkSize) {
            int endIndex = Math.min(i + chunkSize, list.size());
            chunks.add(list.subList(i, endIndex));
        }
        return chunks;
    }
}
