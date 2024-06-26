package star.tratto.util;

import static org.plumelib.util.CollectionsPlume.mapList;

import edu.stanford.nlp.ling.CoreAnnotations.LemmaAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.javatuples.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class provides static methods for manipulating and evaluating strings,
 * such as removing unnecessary spaces or computing the semantic similarity
 * of two inputs.
 */
public class StringUtils {
    /** A suite of NLP tools for pre-processing text inputs. */
    private static final StanfordCoreNLP stanfordCoreNLP = getStanfordCoreNLP();
    private static final Pattern instanceofPattern = Pattern.compile(" instanceof( |$)");

    /** Do not instantiate this class. */
    private StringUtils() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * @return a new StanfordCoreNLP object for
     * preprocessing two strings for semantic comparison. We use the following
     * modifiers:
     * <ul>
     *     <li>tokenize: splits input into tokens (e.g. words, punctuation)</li>
     *     <li>pos: assigns a part-of-speech to each token</li>
     *     <li>lemma: converts each word to its base root (e.g. "running" -> "run")</li>
     * </ul>
     */
    private static StanfordCoreNLP getStanfordCoreNLP() {
        Properties properties = new Properties();
        properties.setProperty("annotators", "tokenize, pos, lemma");
        return new StanfordCoreNLP(properties);
    }

    /**
     * Remove spaces, except around "instanceof".
     */
    public static String compactExpression(String expression) {
        if (expression == null) {
            return "";
        }
        if (instanceofPattern.matcher(expression).find()) {
            String[] segments = instanceofPattern.split(expression, -1);
            List<String> compactedSegments = mapList(StringUtils::removeSpaces, segments);
            return String.join(" instanceof ", compactedSegments);
        } else {
            return removeSpaces(expression);
        }
    }

    /** Returns the string, with all spaces removed. */
    private static String removeSpaces(String s) {
        return s.replace(" ", "");
    }

    /**
     * Join the given strings by spaces, then call {@link #compactExpression(String)} on the result.
     */
    public static String compactExpression(List<String> expressionTokens) {
        if (expressionTokens == null) {
            return "";
        }
        return compactExpression(String.join(" ", expressionTokens));
    }

    /**
     * @throws IllegalArgumentException if openingParenthesisIndex is greater than or equal to the size of oracleTokens,
     *                                  or if oracleTokens does not contain the "(" token at the openingParenthesisIndex
     *                                  position
     * @return null if no corresponding closing parenthesis is found, otherwise returns the index (in {@code oracleTokens}) of the corresponding closing parenthesis
     */
    public static Integer getCorrespondingClosingParenthesisIndex(List<String> oracleTokens, int openingParenthesisIndex) {
        if (openingParenthesisIndex >= oracleTokens.size()) {
            throw new IndexOutOfBoundsException("openingParenthesisIndex=" + openingParenthesisIndex + ", oracle tokens list size=" + oracleTokens.size());
        } else if (!oracleTokens.get(openingParenthesisIndex).equals("(")) {
            throw new IllegalArgumentException("The token at the openingParenthesisIndex (" + openingParenthesisIndex + ") is not an opening parenthesis. " +
                    "Token: " + oracleTokens.get(openingParenthesisIndex));
        }

        int openingParenthesisCounter = 1;
        for (int i = openingParenthesisIndex + 1; i < oracleTokens.size(); i++) {
            String token = oracleTokens.get(i);
            if (token.equals("(")) {
                openingParenthesisCounter++;
            } else if (token.equals(")")) {
                openingParenthesisCounter--;
            }
            if (openingParenthesisCounter == 0) {
                return i;
            }
        }

        return null;
    }

    /** Returns the indexes of the oracleTokens list where the tokens are found. Empty if tokens is null.
     * All indexes if tokens is empty.
     * @param oracleTokens list of tokens in the (partial) oracle
     * @param tokens list of tokens to find in the oracle
     * @return the indexes of the oracleTokens list where the tokens are found. Empty if tokens is null.
     * All indexes if tokens is empty.
     */
    public static List<Integer> getIndexesOfTokensInOracleTokens(List<String> oracleTokens, List<String> tokens) {
        if (tokens == null) {
            return Collections.emptyList();
        } else if (tokens.isEmpty()) {
            return IntStream.rangeClosed(0, oracleTokens.size() - 1).boxed().collect(Collectors.toList());
        }

        List<Integer> indexesOfTokensInOracle = new ArrayList<>();
        for (int i = 0; i < oracleTokens.size(); i++) {
            if (tokens.contains(oracleTokens.get(i))) {
                indexesOfTokensInOracle.add(i);
            }
        }
        return indexesOfTokensInOracle;
    }

    /**
     * @param packageName is never null, but may be ""
     */
    public static String fullyQualifiedClassName(String packageName, String className) {
        if (packageName.isEmpty()) {
            return className;
        }
        return packageName + "." + className;
    }

    public static String fullyQualifiedClassName(Pair<String, String> packageClassPair) {
        return fullyQualifiedClassName(packageClassPair.getValue0(), packageClassPair.getValue1());
    }

    public static String getClassNameFromPath(String path) {
        if (!path.endsWith(".java")) {
            throw new IllegalArgumentException("Path does not end with .java: " + path);
        }
        String[] pathTokens = path.split("/");
        String className = pathTokens[pathTokens.length - 1];
        return className.substring(0, className.length() - 5);
    }

    /**
     * Removes all non-alphabetic and non-space characters in a String, and
     * sets all alphabetic characters to lower case.
     *
     * @param s an input string
     * @return removes all non-alphabetic and non-space characters from the
     * input, and sets all alphabetic characters to lower case
     */
    private static String toAllLowerCaseLetters(String s) {
        return s.replaceAll("[^a-zA-Z ]", "").toLowerCase();
    }

    /**
     * Transforms a String of words to a list of the corresponding lemmas. A
     * lemma is a dictionary-defined canonical form of a word. StanfordCoreNLP
     * uses WordNet to determine the canonical forms. For example:
     * <pre>
     *  "running"   ->  "run"
     *  "better"    ->  "good"
     * </pre>
     * When calculating semantic similarity, we use lemmas (rather than words)
     * to avoid treating similar words as entirely separate.
     *
     * @param words lowercase alphabetic characters separated by spaces
     * @return lemmas corresponding to words in the original String
     */
    private static List<String> toLemmas(String words) {
        Annotation processedText = new Annotation(words);
        stanfordCoreNLP.annotate(processedText);
        List<CoreLabel> tokens = processedText.get(TokensAnnotation.class);
        return mapList(t -> t.get(LemmaAnnotation.class),
                       tokens);
    }

    /**
     * Creates a map from each String to its corresponding frequency in the
     * given list of Strings.
     *
     * @param strings a list of Strings
     * @return string frequencies, where the keys are unique strings and
     * the values are the number of occurrences in {@code strings}
     */
    private static Map<String, Integer> getHistogram(List<String> strings) {
        Map<String, Integer> histogram = new HashMap<>();
        for (String string : strings) {
            int currentCount = histogram.getOrDefault(string, 0);
            histogram.put(string, currentCount + 1);
        }
        return histogram;
    }

    /**
     * Converts a histogram of word frequencies to a vector.
     *
     * @param frequencies a map of word frequencies
     * @param words the ordered set of all words to be considered in the vector
     * @return a vector representation of the word frequencies. Each entry
     * corresponds to a different word, where the value of the entry
     * corresponds to the word frequency. If a word does not appear in the
     * histogram, then it is assigned a value of 0. The length and order of the vector
     * corresponds to {@code words}.
     */
    private static RealVector wordFrequencyToVector(Map<String, Integer> frequencies, SortedSet<String> words) {
        double[] vector = new double[words.size()];
        int i = 0;
        for (String word : words) {
            vector[i] = frequencies.getOrDefault(word, 0);
            i++;
        }
        return new ArrayRealVector(vector);
    }

    /**
     * Returns the words in both sets.
     *
     * @param set1 a set of words
     * @param set2 a set of words
     * @return the words in both sets
     */
    private static SortedSet<String> setIntersection(Set<String> set1, Set<String> set2) {
        TreeSet<String> intersectionKeys = new TreeSet<>(set1);
        intersectionKeys.retainAll(set2);
        return intersectionKeys;
    }

    /**
     * Computes the cosine similarity of two vectors. Imitates behavior of
     * {@link RealVector#cosine} without throwing exceptions. If the norm of
     * either vector is 0.0, then the method returns 0.0.
     *
     * @param vector1 a vector
     * @param vector2 a vector
     * @return the cosine similarity of the two vectors
     */
    private static double cosineSimilarity(RealVector vector1, RealVector vector2) {
        double denominator = vector1.getNorm() * vector2.getNorm();
        if (denominator == 0.0) {
            return 0.0;
        }
        return vector1.dotProduct(vector2) / denominator;
    }

    /**
     * Computes the cosine similarity of two lists of Strings. <br>
     * Note: This  implementation uses the set intersection, rather than the
     * set union, due to the nature of the use case: comparing pre-processed
     * JDoctor tags and tags from source code. JDoctor removes several words
     * to simplify a tag. Notably, no NEW words are added (although some may
     * be repeated). Therefore, the set of JDoctor tag words is a strict
     * subset of the set of source code tag words. If we use the set union, it
     * does not identify tags as accurately. Attempts were made using both the
     * set union and intersection. When using the set intersection, all
     * correct tags were found. However, when using the set union, some
     * JDoctor tags were matched to incorrect source code tags. This behavior
     * was tested via randomly sampling 50 tags.
     *
     * @param strings1 list of strings
     * @param strings2 list of strings
     * @return the cosine similarity (double between 0.0 and 1.0)
     */
    private static double cosineSimilarity(List<String> strings1, List<String> strings2) {
        Map<String, Integer> wordsFreq1 = getHistogram(strings1);
        Map<String, Integer> wordsFreq2 = getHistogram(strings2);
        SortedSet<String> intersectionKeys = setIntersection(wordsFreq1.keySet(), wordsFreq2.keySet());
        RealVector wordVector1 = wordFrequencyToVector(wordsFreq1, intersectionKeys);
        RealVector wordVector2 = wordFrequencyToVector(wordsFreq2, intersectionKeys);
        return cosineSimilarity(wordVector1, wordVector2);
    }

    /**
     * Computes the semantic similarity of two strings by the cosine
     * similarity of their word frequency vectors. We define a word as a
     * sequence of alphabetic characters separated by a space. We convert
     * words into their canonical forms using the StanfordCoreNLP toolkit.
     *
     * @param s1 a string of words
     * @param s2 a string of words
     * @return the cosine similarity of the two strings represented as a
     * double between 0.0 and 1.0
     */
    public static double semanticSimilarity(String s1, String s2) {
        s1 = toAllLowerCaseLetters(s1);
        s2 = toAllLowerCaseLetters(s2);
        List<String> lemmas1 = toLemmas(s1);
        List<String> lemmas2 = toLemmas(s2);
        return cosineSimilarity(lemmas1, lemmas2);
    }

    /**
     * Checks if expression contains word as a whole (i.e., not as a substring).
     * @param expression expression to check
     * @param word word potentially contained in expression
     * @return true if expression contains word as a whole, false otherwise
     */
    public static boolean containsWord(String expression, String word) {
        return expression.matches(".*\\b" + word + "\\b.*");
    }
}
