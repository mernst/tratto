package star.tratto.preprocessing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import star.tratto.TokensDataset;
import star.tratto.data.TokenDPType;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This class preprocesses the tokens dataset generated by {@link TokensDataset}. It makes the following
 * assumptions:
 * <ul>
 *     <li>Each TokenDatapoint has a unique {@code id}.</li>
 *     <li>TokenDatapoints with the same {@code oracleId} belong to the same oracle OR to
 *     syntactically-different but semantically equivalent versions of the same oracle.</li>
 * </ul>
 * <br>
 * The preprocessing consists of the following steps:
 * <ol>
 *     <li>Remove duplicates. To identify duplicates, the following features are ignored: {@code id},
 *     {@code oracleId}, and {@code projectName}. We ignore these features because they are not included
 *     as input for the ML models, and they may be different for TokenDatapoints which are actually
 *     the same.</li>
 *     <li>Remove TokenDatapoints with {@code label=false} if there's some other equal TokenDatapoint
 *     with {@code label=true}. In this case, equal TokenDatapoints will have different {@code id} and
 *     may have different {@code javadocTag}, but we know they are equal because they have the same
 *     {@code oracleId} and {@code oracleSoFar}. We do this because syntactically-different but semantically
 *     equivalent versions of the same oracle may result in contradictory TokenDatapoints, i.e., after
 *     certain {@code oracleSoFar}, a token is both valid and invalid. These tokens are indeed valid,
 *     so we remove the TokenDatapoints that dictate that they are invalid.</li>
 *     <li>Remove TokenDatapoints that, sharing the same pair {@code <oracleId, oracleSoFar>}, have
 *     only one possible value for the token feature ({@code token} or {@code tokenClass}). We do this
 *     because, in these cases, there's only one possible next token, so there's no need to use the
 *     ML models to decide upon it, and training the ML models with these samples may bias them.</li>
 * </ol>
 */
public class TokensDatasetPreprocessing {

    private static final Logger logger = LoggerFactory.getLogger(TokensDatasetPreprocessing.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();
    static { objectMapper.configure(DeserializationFeature.USE_LONG_FOR_INTS, true); }
    private static final String TOKENS_DATASET_FOLDER = "src/main/resources/tokens-dataset/";
    private static final TokenDPType DATASET_TYPE = TokenDPType.TOKEN_CLASS;
    private static final String tokenFeature = DATASET_TYPE == TokenDPType.TOKEN_VALUE ? "token" : "tokenClass";
    private static List<Long> idsToRemove = new ArrayList<>();
    private static List<Long> idsDuplicates = new ArrayList<>();
    private static List<Long> idsContradictory = new ArrayList<>();
    private static List<Long> idsSinglePossibility = new ArrayList<>();
    private static final List<String> singlePossibilityTokenClasses = List.of(
            "Semicolon",
            "OpeningParenthesis",
            "This",
            "TRUE",
            "ArraysClass",
            "EqOperator",
            "IneqOperator",
            "Period",
            "InstanceOfOperator",
            "NULL",
            "ClosingParenthesis",
            "MethodResultID",
            "BitwiseNegateOperator",
            "FALSE",
            "Colon",
            "QuestionMark",
            "StreamMethod",
            "MatchMethodVar",
            "RightArrow",
            "Comma",
            "ClassModifier"
    );
    private static final List<String> projects = List.of(
            "plume-lib-1.1.0",
            "jgrapht-core-0.9.2",
            "gs-core-1.3",
            "guava-19.0",
            "commons-collections4-4.1",
            "commons-math3-3.6.1"
    );
    private static final String oracleIdFeature = "oracleId";
    private static final String javadocTagFeature = "javadocTag";
    private static final String methodSourceCodeFeature = "methodSourceCode";

    public static void main(String[] args) throws IOException {
        if (DATASET_TYPE != TokenDPType.TOKEN_CLASS && DATASET_TYPE != TokenDPType.TOKEN_VALUE) {
            throw new IllegalArgumentException("Preprocessing not implemented for dataset type: " + DATASET_TYPE);
        }

        logger.info("-----------------------------------------------------------------------------");
        logger.info("Starting tokens dataset preprocessing...");
        logger.info("-----------------------------------------------------------------------------");

        // FIRST PHASE: Collecting TokenDatapoint IDs that need to be removed

        // Save minified version of ALL TokenDatapoints
        Path tokensDatasetFolder = Paths.get(TOKENS_DATASET_FOLDER);
        int totalFiles = 0;
        DirectoryStream<Path> tokensDatasetStream;
        int index;
        for (String project : projects) {
            List<Map> projectTokenDatapoints = new ArrayList<>();
            logger.info("Loading TokenDatapoints for project: {}", project);
            tokensDatasetStream = Files.newDirectoryStream(tokensDatasetFolder);
            for (Path tokensDatasetFile : tokensDatasetStream) { // Assume that only dataset files are in the folder
                if (!tokensDatasetFile.getFileName().toString().contains(project)) {
                    continue;
                }
                totalFiles++;
                logger.info("Loading TokenDatapoints from file: {}", tokensDatasetFile.getFileName());

                // Save TokenDatapoints as Maps. To save memory, discard some features
                List<Map> tokenDatapoints = objectMapper.readValue(tokensDatasetFile.toFile(), List.class);
                tokenDatapoints.forEach(tdp -> {
                    tdp.remove("projectName");
                    tdp.remove("classJavadoc");
                    tdp.remove("classSourceCode");
//                    tdp.put("methodSourceCode", ((String) tdp.get("methodSourceCode")).split("\\{")[0]);
                });
                projectTokenDatapoints.addAll(tokenDatapoints);
            }
            long projectTDPsSize = projectTokenDatapoints.size();
            logger.info("Total number of TokenDatapoints: {}", projectTDPsSize);


            // To speed up processing for token-values, we remove all data points whose token class is a single-possible-value one.
            if (DATASET_TYPE == TokenDPType.TOKEN_VALUE) {
                List<Long> singleValueIds = new ArrayList<>();
                projectTokenDatapoints.removeIf(tdp -> {
                    if (singlePossibilityTokenClasses.contains((String)tdp.get("tokenClass"))) {
                        idsSinglePossibility.add((Long)tdp.get("id"));
                        idsToRemove.add((Long)tdp.get("id"));
                        singleValueIds.add((Long)tdp.get("id"));
                        return true;
                    }
                    return false;
                });
                logger.info("Removed {} TokenDatapoints related to single-possible-values", projectTDPsSize - projectTokenDatapoints.size());
                logger.info("TokenDatapoints removed: {}", singleValueIds);
                projectTDPsSize = projectTokenDatapoints.size();
                logger.info("Total number of TokenDatapoints after removing single-possible-values: {}", projectTDPsSize);
            }

            // Need to remove class- or value- related features from data points for subsequent checks (e.g., .equals())
            projectTokenDatapoints.forEach(tdp -> {
                if (DATASET_TYPE == TokenDPType.TOKEN_CLASS) {
                    tdp.remove("token");
                    tdp.remove("tokenInfo");
                } else {
                    tdp.remove("tokenClass");
                    tdp.remove("tokenClassesSoFar");
                }
            });

            // Also to speed up process, split into chunks which all have the same oracleType, packageName and className.
            Collection<List<Map>> datapointsChunks = projectTokenDatapoints
                    .stream()
                    .collect(Collectors.groupingBy(tdp -> ((String)tdp.get("oracleType") + tdp.get("packageName") + tdp.get("className"))))
                    .values();

            logger.info("Collecting IDs of TokenDatapoints that need to be removed...");
            // Apply filters to detect which TokenDatapoints need to be removed
            index = 0;
            int indexChunk = 0;
            for (List<Map> datapointsChunk : datapointsChunks) {
                logger.info("Processing chunk {}/{} with {} TokenDatapoints", ++indexChunk, datapointsChunks.size(), datapointsChunk.size());
                for (Map tokenDatapoint : datapointsChunk) {
                    logger.info("[{} / {}] Processing TokenDatapoint with ID {}", ++index, projectTDPsSize, tokenDatapoint.get("id"));

                    if (idsToRemove.contains((Long) tokenDatapoint.get("id"))) {
                        continue; // Skip TokenDatapoints that have already been marked for removal
                    }

                    List<Long> currentIdsDuplicates = new ArrayList<>();
                    List<Long> currentIdsContradictory = new ArrayList<>();
                    List<Long> currentIdsSinglePossibility = new ArrayList<>();

                    // 1. Remove duplicates
                    currentIdsDuplicates.addAll(getDuplicateIds(datapointsChunk, tokenDatapoint)); // The current datapoint is in this list, so don't add it

                    // 2. Remove contradictory TokenDatapoints
                    currentIdsContradictory.addAll(getContradictoryIds(datapointsChunk, tokenDatapoint, true));
                    currentIdsContradictory.addAll(getContradictoryIds(datapointsChunk, tokenDatapoint, false));

                    // 3. Remove TokenDatapoints with single value for token feature
                    List<Map> singlePossibilityTokenDatapoints = datapointsChunk
                            .stream()
                            .filter(tdp -> tdp.get("oracleId").equals(tokenDatapoint.get("oracleId")) && tdp.get("oracleSoFar").equals(tokenDatapoint.get("oracleSoFar")))
                            .toList();
                    if (singlePossibilityTokenDatapoints.stream().map(tdp -> tdp.get(tokenFeature)).distinct().count() == 1) {
                        currentIdsSinglePossibility.addAll(singlePossibilityTokenDatapoints.stream().map(tdp -> (Long) tdp.get("id")).toList());
                    }

                    // Add IDs to the global lists
                    if (currentIdsDuplicates.size() > 0) {
                        logger.info("IDs marked for removal due to duplicates based on current TokenDatapoint: {}", currentIdsDuplicates);
                        idsDuplicates.addAll(currentIdsDuplicates);
                        idsToRemove.addAll(currentIdsDuplicates);
                    }
                    if (currentIdsContradictory.size() > 0) {
                        logger.info("IDs marked for removal due to contradictions based on current TokenDatapoint: {}", currentIdsContradictory);
                        idsContradictory.addAll(currentIdsContradictory);
                        idsToRemove.addAll(currentIdsContradictory);
                    }
                    if (currentIdsSinglePossibility.size() > 0) {
                        logger.info("IDs marked for removal due to single possibility based on current TokenDatapoint: {}", currentIdsSinglePossibility);
                        idsSinglePossibility.addAll(currentIdsSinglePossibility);
                        idsToRemove.addAll(currentIdsSinglePossibility);
                    }
                }
            }
        }

        idsToRemove = idsToRemove.stream().distinct().toList();
        idsDuplicates = idsDuplicates.stream().distinct().toList();
        idsContradictory = idsContradictory.stream().distinct().toList();
        idsSinglePossibility = idsSinglePossibility.stream().distinct().toList();

        logger.info("Finished collecting IDs of TokenDatapoints that need to be removed.");

        logger.info("-----------------------------------------------------------------------------");
        logger.info("-----------------------------------------------------------------------------");
        logger.info("-----------------------------------------------------------------------------");

        logger.info("Updating TokenDatapoints files...");

        // SECOND PHASE: Removing TokenDatapoints with the collected IDs
        List<Long> finalIdsToRemove = idsToRemove;
        long removedIds = 0;
        tokensDatasetStream = Files.newDirectoryStream(tokensDatasetFolder);
        index = 0;
        for (Path tokensDatasetFile : tokensDatasetStream) { // Assume that only dataset files are in the folder
            logger.info("[{} / {}] Checking file: {}", ++index, totalFiles, tokensDatasetFile.getFileName());

            List<Map> tokenDatapoints = objectMapper.readValue(tokensDatasetFile.toFile(), List.class);
            int oldSize = tokenDatapoints.size();
            tokenDatapoints.removeIf(tdp -> finalIdsToRemove.contains((Long)tdp.get("id")));
            int newSize = tokenDatapoints.size();
            if (oldSize != newSize) {
                logger.info("Removed {} TokenDatapoints from file {}", oldSize - newSize, tokensDatasetFile.getFileName());
                objectMapper.writeValue(tokensDatasetFile.toFile(), tokenDatapoints);
                removedIds += oldSize - newSize;
            }
        }

        assert removedIds == idsToRemove.size();

        logger.info("Finished updating TokenDatapoints files. Final stats:");
        logger.info("Total number of TokenDatapoints removed: {}", idsToRemove.size());
        logger.info("Number of TokenDatapoints removed due to duplicates: {}", idsDuplicates.size());
        logger.info("Number of TokenDatapoints removed due to contradictions: {}", idsContradictory.size());
        logger.info("Number of TokenDatapoints removed due to single possibility: {}", idsSinglePossibility.size());
    }

    private static List<Long> getDuplicateIds(List<Map> datapoints, Map datapoint) {
        Map datapointsNoIds = new HashMap(datapoint);
        datapointsNoIds.remove("id");
        datapointsNoIds.remove("oracleId");
        List<Long> duplicateIds = datapoints
                .stream()
                .filter(tdp -> {
                    Map tdpWithoutFeatures = new HashMap(tdp);
                    tdpWithoutFeatures.remove("id");
                    tdpWithoutFeatures.remove("oracleId");
                    return tdpWithoutFeatures.equals(datapointsNoIds);
                })
                .map(tdp -> (Long) tdp.get("id"))
                .toList();
        return duplicateIds.subList(1, duplicateIds.size()); // The current datapoint is in this list, so don't add it
    }

    private static List<Long> getContradictoryIds(List<Map> datapoints, Map datapoint, boolean compareOracleId) {
        Map tokenDatapointNoIdLabelFeature = new HashMap(datapoint);
        tokenDatapointNoIdLabelFeature.remove("id");
        tokenDatapointNoIdLabelFeature.remove("label");
        if (compareOracleId) {
            tokenDatapointNoIdLabelFeature.remove(oracleIdFeature);
        } else {
            tokenDatapointNoIdLabelFeature.remove(javadocTagFeature);
            tokenDatapointNoIdLabelFeature.remove(methodSourceCodeFeature);
        }
        List<Long> contradictoryIds = new ArrayList<>();
        if ((Boolean) datapoint.get("label")) {
            contradictoryIds = datapoints
                    .stream()
                    .filter(tdp -> {
                        if ((Boolean) tdp.get("label") || tdp.get("id") == datapoint.get("id")) {
                            return false;
                        }
                        Map tdpWithoutFeatures = new HashMap(tdp);
                        tdpWithoutFeatures.remove("id");
                        tdpWithoutFeatures.remove("label");
                        if (compareOracleId) {
                            tdpWithoutFeatures.remove(oracleIdFeature);
                        } else {
                            tdpWithoutFeatures.remove(javadocTagFeature);
                            tdpWithoutFeatures.remove(methodSourceCodeFeature);
                        }
                        return tdpWithoutFeatures.equals(tokenDatapointNoIdLabelFeature);
                    })
                    .map(tdp -> (Long) tdp.get("id"))
                    .toList();
        }
        return contradictoryIds; // The current datapoint is NOT in this list, so add all
    }
}
