package star.tratto.identifiers;

import java.nio.file.Paths;

public enum IOPath {
    OUTPUT(Paths.get("target", "output").toString()),
    RESOURCES(Paths.get("src", "main", "resources").toString()),
    REPOS(Paths.get("src", "main", "java", "star", "tratto", "data", "repos").toString());

    private final String path;

    IOPath(String path) {
        this.path = Paths.get(
                System.getProperty("user.dir"),
                path
        ).toString();
    }

    public String getValue() {
        return this.path;
    }
}