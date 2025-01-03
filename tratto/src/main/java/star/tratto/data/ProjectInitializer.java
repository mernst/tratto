package star.tratto.data;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import star.tratto.data.records.Project;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A helper class that provides a static method
 * {@link ProjectInitializer#getProjects(Path)} to initialize all projects to a
 * list of {@link Project} records from a JSON file.
 */
public class ProjectInitializer {
    /** Do not instantiate this class. */
    private ProjectInitializer() {
        throw new UnsupportedOperationException("This class cannot be instantiated.");
    }

    /**
     * This helper class uses the Jackson framework to deserialize a JSON
     * object into a {@link Project}.
     */
    private static class ProjectDeserializer extends JsonDeserializer<Project> {
        /**
         * Gets a Path from a JSON list of path elements.
         *
         * @param arrayNode a JSON list of path elements
         * @return the path corresponding to the joined elements of
         * {@code arrayNode}
         */
        private Path arrayNodeToPath(ArrayNode arrayNode) {
            List<String> pathElements = new ArrayList<>();
            for (JsonNode element : arrayNode) {
                pathElements.add(element.asText());
            }
            String pathString = String.join(FileSystems.getDefault().getSeparator(), pathElements);
            return FileSystems.getDefault().getPath(pathString);
        }

        /**
         * Deserializes a JSON object into a {@link Project} record.
         *
         * @param jsonParser a parser used to deserialize a JSON object
         * @param deserializationContext provides reusable temporary objects
         *                               for deserialization (not used in this
         *                               implementation)
         * @return a Project record, corresponding to the parsed JSON
         * @throws IOException if an error occurs reading the tree of the
         * json parser
         */
        @Override
        public Project deserialize(
                JsonParser jsonParser,
                DeserializationContext deserializationContext
        ) throws IOException {
            JsonNode node = jsonParser.getCodec().readTree(jsonParser);
            String projectName = node.get("projectName").asText();
            Path projectPath = TrattoPath.RESOURCES.getPath().resolve(arrayNodeToPath((ArrayNode) node.get("dirPathList")));
            Path jarPath = projectPath.resolve(arrayNodeToPath((ArrayNode) node.get("jarPathList")));
            Path conditionsPath = projectPath.resolve(arrayNodeToPath((ArrayNode) node.get("jDocConditionsPathList")));
            Path srcPath = projectPath.resolve(arrayNodeToPath((ArrayNode) node.get("srcPathList")));
            boolean generateEmptyOracles = node.get("generateEmptyOracles").asBoolean();
            return new Project(
                    projectName,
                    projectPath,
                    jarPath,
                    conditionsPath,
                    srcPath,
                    generateEmptyOracles
            );
        }
    }

    /**
     * This helper class uses the Jackson framework to set the deserializer to
     * a {@link ProjectDeserializer} when parsing a {@link Project}.
     */
    public static class ProjectDeserializerModifier extends BeanDeserializerModifier {
        @Override
        public JsonDeserializer<?> modifyDeserializer(
                DeserializationConfig config,
                BeanDescription beanDesc,
                JsonDeserializer<?> deserializer
        ) {
            if (beanDesc.getBeanClass() == Project.class) {
                return new ProjectDeserializer();
            }
            return super.modifyDeserializer(config, beanDesc, deserializer);
        }
    }

    /**
     * Gets a list of {@link Project} records from a JSON file.
     *
     * @param jsonPath path to the JSON representation of projects
     * @return the corresponding {@link Project} records. Filters any
     * inaccessible projects (i.e. the project path does not exist).
     */
    public static List<Project> getProjects(Path jsonPath) {
        // initialize ObjectMapper with custom deserializer
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.setDeserializerModifier(new ProjectDeserializerModifier());
        objectMapper.registerModule(module);
        // read JSON object to Project
        try {
            List<Project> projectList = objectMapper.readValue(
                    jsonPath.toFile(),
                    new TypeReference<>() {}
            );
            return projectList
                    .stream()
                    .filter(project -> {
                        Path rootDir = project.rootPath();
                        return Files.exists(rootDir) && Files.isDirectory(rootDir);
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new Error("Error in processing the JSON file " + jsonPath, e);
        }
    }
}
