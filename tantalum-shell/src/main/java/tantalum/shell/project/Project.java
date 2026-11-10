package tantalum.shell.project;

import java.time.Instant;
import java.util.Objects;

/** 一个工作区：用户电脑上的一个目录加一个名字。{@code path} 是用户给的原样（去了首尾空白），比较用 {@link ProjectPaths#key}。 */
public record Project(String id, String title, String path, Instant created) {

    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(created, "created");
    }
}
