package com.automationstudio.api.source.materialization.git;

import java.nio.file.Path;
import java.util.List;

interface GitCommandRunner {

    GitCommandResult execute(
            List<String> arguments,
            Path workingDirectory,
            Path isolatedHome);
}
