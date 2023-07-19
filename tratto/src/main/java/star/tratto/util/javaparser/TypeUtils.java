package star.tratto.util.javaparser;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.TypeParameter;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class provides a collection of static methods to convert between the
 * field descriptor and source code formats of all Java types. This class
 * builds upon the functionality of {@link PrimitiveTypeUtils} with methods
 * that convert between Object and array types, as well as other utilities
 * for both representations. For our purposes, we note that JDoctor uses
 * field descriptors to represent types, whereas JavaParser uses source code
 * format to represent types.
 */
public class TypeUtils {
    // private constructor to avoid creating an instance of this class.
    private TypeUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * Removes any type arguments from a parameterized type name.
     *
     * @param parameterizedType a field descriptor or source code type
     *                          representation
     * @return the same type representation without type arguments
     */
    public static String removeTypeArguments(String parameterizedType) {
        // regex to match type arguments in angle brackets.
        String regex = "<[^<>]*>";
        // repeatedly remove all type arguments.
        String previous;
        String current = parameterizedType;
        do {
            previous = current;
            current = previous.replaceAll(regex, "");
        } while (!current.equals(previous));
        return current;
    }

    /**
     * Splits an identifier, which represents a class path or a
     * fully-qualified name (of a class or method), into components.
     *
     * @param identifier a class path or fully-qualified name
     * @return identifier components. Includes: path prefix (if the
     * identifier is a path), all outer classes (separated), the class, and a
     * file extension (if the identifier is a path).
     */
    public static List<String> getIdentifierComponents(
            String identifier
    ) {
        String regex = "[.$]";
        return Arrays.asList(identifier.split(regex));
    }

    /**
     * @see TypeUtils#getIdentifierComponents(String)
     * @param identifierComponents identifier components. Should be derived
     *                             from a class path (not FQN).
     * @return identifier components, without the file extension
     */
    public static List<String> removeIdentifierComponentsExtension(
            List<String> identifierComponents
    ) {
        return identifierComponents.subList(0, identifierComponents.size() - 1);
    }

    /**
     * @see TypeUtils#getIdentifierComponents(String)
     * @param identifierComponents identifier components without file extension
     * @return identifier components joined by "."
     */
    public static String getPackageNameFromIdentifierComponents(
            List<String> identifierComponents
    ) {
        return String.join(".", identifierComponents);
    }

    /**
     * @see TypeUtils#getIdentifierComponents(String)
     * @param identifierComponents identifier components without suffix
     * @return the innermost class
     */
    public static String getClassNameFromIdentifierComponents(
            List<String> identifierComponents
    ) {
        return identifierComponents.get(identifierComponents.size() - 1);
    }

    /**
     * @param typeName a field descriptor or source code type representation
     * @return the array level of the type
     */
    private static int getArrayLevel(String typeName) {
        int arrayLevel = 0;
        for (char c : typeName.toCharArray()) {
            if (c == '[') {
                arrayLevel++;
            }
        }
        return arrayLevel;
    }

    /**
     * Adds brackets to a given (source code format) type, increasing the
     * array level by a specified amount.
     *
     * @param typeName a source code format type name
     * @param arrayLevel the number of array levels to add
     * @return the new type name with the added number of array levels
     */
    private static String addSourceCodeArrayLevel(String typeName, int arrayLevel) {
        return typeName + ("[]").repeat(arrayLevel);
    }

    /**
     * Converts a field descriptor array to a source code format array. This
     * method ONLY changes the square brackets, and will not change the
     * component type name. For example:
     *  [Object => Object[]
     *  [[D     => D[][]
     *
     * @param fieldDescriptor a field descriptor array type
     * @return the corresponding source code format array type
     */
    private static String fieldDescriptorArrayToSourceCodeArray(String fieldDescriptor) {
        int arrayLevel = getArrayLevel(fieldDescriptor);
        return addSourceCodeArrayLevel(fieldDescriptor.substring(arrayLevel), arrayLevel);
    }

    /**
     * Converts a field descriptor representation of a primitive type to a
     * source code format representation of a primitive type.
     *
     * @param fieldDescriptor a field descriptor primitive type name
     * @return the corresponding source code format primitive type name
     * @throws IllegalArgumentException if {@code fieldDescriptor} does not
     * match a known field descriptor primitive representation
     */
    private static String fieldDescriptorPrimitiveToSourceCodePrimitive(String fieldDescriptor) {
        // check that given field descriptor represents a primitive type.
        List<String> allPrimitiveFieldDescriptors = PrimitiveTypeUtils.getAllPrimitiveFieldDescriptors();
        if (!allPrimitiveFieldDescriptors.contains(fieldDescriptor.replaceAll("[^a-zA-Z]+", ""))) {
            throw new IllegalArgumentException(String.format(
                    "The string passed to the function (%s) does not represent a primitive type",
                    fieldDescriptor
            ));
        }
        // match `fieldDescriptor` to a known primitive field descriptor.
        String fieldDescriptorRegex = PrimitiveTypeUtils.getAllPrimitiveFieldDescriptorsRegex();
        String regex = String.format(
                "[^A-Za-z0-9_]*(%s)[^A-Za-z0-9_]*",
                fieldDescriptorRegex
        );
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(fieldDescriptor);
        if (matcher.find()) {
            String rawFieldDescriptor = matcher.group(1);
            String sourceCodePrimitiveType = PrimitiveTypeUtils.fieldDescriptorToPrimitiveType(rawFieldDescriptor);
            return fieldDescriptor.replaceAll(fieldDescriptorRegex, sourceCodePrimitiveType);
        } else {
            // `fieldDescriptor` does not match any known field descriptor.
            throw new IllegalArgumentException(String.format(
                    "The condition parameter does not match any primitive type: %s",
                    fieldDescriptor
            ));
        }
    }

    /**
     * Converts a JDoctor type name to its corresponding JavaParser type name.
     *
     * @param jDoctorTypeName a JDoctor representation of a type
     * @return the corresponding JavaParser representation of a type
     */
    private static String convertJDoctorTypeNameToJPTypeName(String jDoctorTypeName) {
        jDoctorTypeName = removeTypeArguments(jDoctorTypeName);
        // converts primitive type.
        List<String> primitiveJDoctorValues = PrimitiveTypeUtils.getAllPrimitiveFieldDescriptors();
        if (primitiveJDoctorValues.contains(jDoctorTypeName.replaceAll("[^a-zA-Z]+", ""))) {
            jDoctorTypeName = fieldDescriptorPrimitiveToSourceCodePrimitive(jDoctorTypeName);
        }
        // converts array type.
        if (jDoctorTypeName.startsWith("[")) {
            jDoctorTypeName = fieldDescriptorArrayToSourceCodeArray(jDoctorTypeName);
        }
        // gets class name.
        List<String> paramPathList = getIdentifierComponents(jDoctorTypeName);
        return getClassNameFromIdentifierComponents(paramPathList);
    }

    /**
     * The method converts a list of JDoctor type names {@code jDoctorTypeNames} into a list of JavaParser type names.
     * For example, the JDoctor type name {@code [D} represents a list of doubles, and the corresponding type name in
     * JavaParser is {@code double[]}. The method apply these conversions from JDoctor type names to JavaParser type
     * names. See PrimitiveTypeUtils {@link PrimitiveTypeUtils} for all possible conversions.
     *
     * @param jDoctorTypeNames JDoctor type names to convert
     * @return a list of the corresponding JavaParser type names
     */
    public static List<String> convertJDoctorTypeNamesToJPTypeNames(
            List<String> jDoctorTypeNames
    ) {
        return jDoctorTypeNames
                .stream()
                .map(TypeUtils::convertJDoctorTypeNameToJPTypeName)
                .collect(Collectors.toList());
    }

    /**
     * @param jpClass a JavaParser class or interface declaration
     * @return true iff the given class or interface uses generic types
     */
    public static boolean hasGenerics(ClassOrInterfaceDeclaration jpClass) {
        return jpClass.getTypeParameters().size() > 0;
    }

    /**
     * @param typeName name of a JavaParser parameter {@link Parameter}
     * @return true iff the parameter name includes "..."
     */
    public static boolean hasEllipsis(String typeName) {
        return typeName.contains("...");
    }

    /**
     * Checks if a given type extends a supertype in source code.
     * NOTE: We only check "extends" and not "implements" for parameterized
     * types.
     *
     * @param sourceCode the method or class source code in which
     * {@code typeName} is used
     * @param typeName a JavaParser type name
     * @return true iff the type name extends another type
     */
    public static boolean hasSupertype(String sourceCode, String typeName) {
        String regex = String.format("%s\\s+extends\\s+([A-Za-z0-9_]+)[<[A-Za-z0-9_,]+]*", typeName.replaceAll("\\[]",""));
        // Using the Pattern and Matcher classes
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(sourceCode);
        return matcher.find();
    }

    /**
     * Finds the supertype of a given type name in source code. We analyze
     * source code using JavaParser, such that the given type name must use
     * the JavaParser representation.
     *
     * @param sourceCode the method or class source code in which
     * {@code jpTypeName} is used
     * @param jpTypeName a JavaParser type name
     * @return the name of the supertype of {@code jpTypeName}
     * @throws IllegalArgumentException if {@code jpTypeName} does not extend
     * a type
     */
    private static String getSupertype(String sourceCode, String jpTypeName) {
        int arrayLevel = getArrayLevel(jpTypeName);
        // finds the supertype.
        String regex = String.format("%s\\s+extends\\s+([A-Za-z0-9_]+)[<[A-Za-z0-9_,]+]*", jpTypeName.replaceAll("\\[]", ""));
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(sourceCode);
        if (matcher.find()) {
            return removeTypeArguments(matcher.group(1)) + ("[]").repeat(arrayLevel);
        } else {
            throw new IllegalArgumentException(String.format(
                    "The JavaParser source code %s does not match the regex built with the JavaParser type name %s.",
                    sourceCode,
                    jpTypeName
            ));
        }
    }

    /**
     * Gets the raw name of the type in source code. Used to manage edge cases
     * with generic types in source code. Also ensures name is consistent with
     * JDoctor format for direct comparison.
     *
     * @param jpClass the declaring class
     * @param jpCallable the method using {@code jpParam}
     * @param jpParam a parameter
     * @return the raw name of the parameter in source code
     */
    public static String getRawTypeName(
            TypeDeclaration<?> jpClass,
            CallableDeclaration<?> jpCallable,
            Parameter jpParam
    ) {
        String jpTypeName = removeTypeArguments(jpParam.getType().asString());
        // get class name.
        if (jpParam.getType().isClassOrInterfaceType()) {
            jpTypeName = removeTypeArguments(jpParam.getType().asClassOrInterfaceType().getNameAsString());
        }
        // handle ellipsis.
        if (hasEllipsis(jpParam.toString())) {
            jpTypeName = addSourceCodeArrayLevel(jpTypeName, 1);
        }
        // use upper bound if possible.
        if (hasSupertype(jpCallable.getTokenRange().get().toString(), jpTypeName)) {
            jpTypeName = getSupertype(jpCallable.getTokenRange().get().toString(), jpTypeName);
        }
        // handle generic upper bound separately due to naming.
        if (jpClass.isClassOrInterfaceDeclaration()) {
            ClassOrInterfaceDeclaration jpClassDeclaration =  jpClass.asClassOrInterfaceDeclaration();
            if (hasGenerics(jpClassDeclaration)) {
                for (TypeParameter jpGeneric : jpClassDeclaration.getTypeParameters()) {
                    if (jpGeneric.getNameAsString().equals(jpTypeName.replaceAll("\\[]", ""))) {
                        if (hasSupertype(jpGeneric.toString(), jpTypeName)) {
                            jpTypeName = getSupertype(jpGeneric.toString(), jpTypeName);
                        }
                    }
                }
            }
        }
        return jpTypeName;
    }

    /**
     * We define a "standard" type as a type which implements either the
     * "Object" or "Comparable" interfaces, which require extra consideration
     * when comparing arguments to check equality.
     * See `jpParamEqualsJDoctorParam` in DatasetUtils for elaboration.
     *
     * @param conditionType name of the JDoctor or JavaParser type
     * @return true iff the given type name is "Object" or "Comparable"
     */
    public static boolean isStandardType(String conditionType) {
        return conditionType.equals("Object") || conditionType.equals("Comparable");
    }

    /**
     * Checks if a given type name is a "standard" array. By definition, this
     * includes the "Object[]" and "Comparable[]" types.
     * See above method {@code isStandardType} for further elaboration.
     *
     * @param conditionType name of the JDoctor or JavaParser type
     * @return true iff the given type name is "Object[]" or "Comparable[]"
     */
    public static boolean isStandardTypeArray(String conditionType) {
        return conditionType.equals("Object[]") || conditionType.equals("Comparable[]");
    }
}
