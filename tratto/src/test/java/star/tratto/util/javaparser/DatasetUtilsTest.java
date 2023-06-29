package star.tratto.util.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import org.javatuples.Pair;
import org.javatuples.Quartet;
import org.javatuples.Triplet;
import org.junit.jupiter.api.Test;
import star.tratto.data.OracleDatapoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static star.tratto.TestUtilities.readOracleDatapointsFromOraclesDataset;
import static star.tratto.util.javaparser.DatasetUtils.*;
import static star.tratto.util.javaparser.JavaParserUtils.*;

public class DatasetUtilsTest {
    private static final List<OracleDatapoint> oracleDatapoints = readOracleDatapointsFromOraclesDataset();
    private static final JavaParser javaParser = JavaParserUtils.getJavaParser();

    @Test
    public void removeDuplicatesTest() {
        List<String> original = List.of("first", "second", "first", "first", "third", "third");
        List<String> expected = List.of("first", "second", "third");
        assertEquals(expected, removeDuplicates(original));
    }

    @Test
    public void getClassJavadocTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        TypeDeclaration<?> jpClass = getClassOrInterface(oracleDatapoint.getClassSourceCode(), oracleDatapoint.getClassName());
        assertEquals(oracleDatapoint.getClassJavadoc(), getClassJavadoc(jpClass));
    }

    @Test
    public void getCallableJavadocTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        TypeDeclaration<?> jpClass = getClassOrInterface(oracleDatapoint.getClassSourceCode(), oracleDatapoint.getClassName());
        String methodName = "value";
        List<String> methodArgs = List.of("DerivativeStructure");
        CallableDeclaration<?> jpCallable = getCallableDeclaration(jpClass, methodName, methodArgs);
        assertNotNull(jpCallable);
        String actual = getCallableJavadoc(jpCallable).trim();
        String expected = oracleDatapoint.getMethodJavadoc();
        assertEquals(expected, actual);
    }

    @Test
    public void getJavadocValuesTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        TypeDeclaration<?> jpClass = getClassOrInterface(oracleDatapoint.getClassSourceCode(), oracleDatapoint.getClassName());
        String methodName = "value";
        List<String> methodArgs = List.of("DerivativeStructure");
        CallableDeclaration<?> jpCallable = getCallableDeclaration(jpClass, methodName, methodArgs);
        assertNotNull(jpCallable);
        String javadoc = getCallableJavadoc(jpCallable).trim();
        List<Pair<String, String>> expected = oracleDatapoint.getTokensMethodJavadocValues();
        List<Pair<String, String>> actual = getJavadocValues(javadoc);
        assertEquals(expected, actual);
    }

    @Test
    public void getTokensMethodArgumentsTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        TypeDeclaration<?> jpClass = getClassOrInterface(oracleDatapoint.getClassSourceCode(), oracleDatapoint.getClassName());
        String methodName = "value";
        List<String> methodArgs = List.of("DerivativeStructure");
        CallableDeclaration<?> jpCallable = getCallableDeclaration(jpClass, methodName, methodArgs);
        assertNotNull(jpCallable);
        List<Triplet<String, String, String>> expected = oracleDatapoint.getTokensMethodArguments();
        List<Triplet<String, String, String>> actual = getTokensMethodArguments(jpClass, jpCallable);
        assertEquals(expected, actual);
    }

    @Test
    public void getCallableSourceCodeTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        TypeDeclaration<?> jpClass = getClassOrInterface(oracleDatapoint.getClassSourceCode(), oracleDatapoint.getClassName());
        String methodName = "value";
        List<String> methodArgs = List.of("DerivativeStructure");
        CallableDeclaration<?> jpCallable = getCallableDeclaration(jpClass, methodName, methodArgs);
        assertNotNull(jpCallable);
        String expected = oracleDatapoint.getMethodSourceCode().replaceAll("\\s+", "");
        String actual = getCallableSourceCode(jpCallable).replaceAll("\\s+", "");
//        System.out.println("original: " + oracleDatapoint.getMethodSourceCode());
//        System.out.println("refactor: " + getCallableSourceCode(jpCallable));
        assertEquals(expected, actual);
    }

    @Test
    public void getMethodsFromTypeTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        TypeDeclaration<?> jpClass = getClassOrInterface(oracleDatapoint.getClassSourceCode(), oracleDatapoint.getClassName());
        String methodName = "value";
        List<String> methodArgs = List.of("DerivativeStructure");
        CallableDeclaration<?> jpCallable = getCallableDeclaration(jpClass, methodName, methodArgs);
        assertNotNull(jpCallable);
        Parameter jpParam = jpCallable.getParameters().get(0);
//        System.out.println(oracleDatapoint.getTokensMethodVariablesNonPrivateNonStaticNonVoidMethods());
//        System.out.println(removeDuplicates(getMethodsFromType(jpParam.getType().resolve())));
    }

    @Test
    public void getFieldsFromTypeTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        TypeDeclaration<?> jpClass = getClassOrInterface(oracleDatapoint.getClassSourceCode(), oracleDatapoint.getClassName());
        String methodName = "value";
        List<String> methodArgs = List.of("DerivativeStructure");
        CallableDeclaration<?> jpCallable = getCallableDeclaration(jpClass, methodName, methodArgs);
        assertNotNull(jpCallable);
        Parameter jpParam = jpCallable.getParameters().get(0);
        List<Quartet<String, String, String, String>> expected = new ArrayList<>();
        List<Quartet<String, String, String, String>> actual = getFieldsFromType(jpParam.getType().resolve());
        assertEquals(expected, actual);
    }

    @Test
    public void getCallableDeclarationTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        String className = oracleDatapoint.getClassName();
        String classSourceCode = oracleDatapoint.getClassSourceCode();
        String methodName = "value";
        List<String> methodArgs = List.of("DerivativeStructure");
        Optional<CompilationUnit> cuOptional = javaParser.parse(classSourceCode).getResult();
        assertTrue(cuOptional.isPresent());
        CompilationUnit cu = cuOptional.get();
        TypeDeclaration<?> jpClass = getTypeDeclaration(cu, className);
        assertNotNull(jpClass);
        CallableDeclaration<?> jpCallable = getCallableDeclaration(jpClass, methodName, methodArgs);
        assertNotNull(jpCallable);
        MethodDeclaration jpMethod = (MethodDeclaration) jpCallable;
        assertEquals("value", jpMethod.getNameAsString());
        assertEquals("DerivativeStructure", jpMethod.getType().asString());
    }

    @Test
    public void getTypeDeclarationTest() {
        OracleDatapoint oracleDatapoint = oracleDatapoints.get(1);
        String className = oracleDatapoint.getClassName();
        String classSourceCode = oracleDatapoint.getClassSourceCode();
        Optional<CompilationUnit> cuOptional = javaParser.parse(classSourceCode).getResult();
        assertTrue(cuOptional.isPresent());
        CompilationUnit cu = cuOptional.get();
        TypeDeclaration<?> jpClass = getTypeDeclaration(cu, className);
        assertNotNull(jpClass);
        assertEquals(jpClass.getNameAsString(), oracleDatapoint.getClassName());
    }
}
