package com.crispyland.mcpserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Day 31 — Git MCP tools for the developer assistant.
 * Provides read-only access to the local git repository so the model
 * can answer questions about the current state of the codebase.
 */
@Service
public class GitTools {

    private static final Logger log = LoggerFactory.getLogger(GitTools.class);

    private final File repoDir;

    public GitTools(@Value("${git.repo-dir:..}") String repoDir) {
        this.repoDir = new File(repoDir);
        log.info("Git tools configured: repo={}", this.repoDir.getAbsolutePath());
    }

    @McpTool(name = "getGitBranch",
            description = "Get the current git branch name and recent commit history. "
                    + "Returns the branch name and the last 5 commit messages.")
    public String getGitBranch() {
        String branch = git("rev-parse", "--abbrev-ref", "HEAD");
        if (branch.startsWith("ERROR")) return branch;

        String recentLog = git("log", "--oneline", "-5");

        return "Current branch: " + branch.strip() + "\n\n"
                + "Recent commits:\n" + recentLog.strip();
    }

    @McpTool(name = "getGitFiles",
            description = "List files in the git repository, optionally filtered to a subdirectory. "
                    + "Returns tracked file paths. Use this to understand the project structure.")
    public String getGitFiles(
            @McpToolParam(description = "Optional subdirectory path to list, e.g. 'src/main/java'. "
                    + "Leave empty to list all top-level files and directories.",
                    required = false) String path) {

        if (path != null && !path.isBlank()) {
            // List files in a specific directory
            String result = git("ls-tree", "--name-only", "HEAD", path + "/");
            if (result.startsWith("ERROR") || result.isBlank()) {
                // Try as a file pattern
                result = git("ls-files", path);
            }
            return result.isBlank() ? "No files found at: " + path : result.strip();
        }

        // Top-level: show directories and files
        String tree = git("ls-tree", "--name-only", "HEAD");
        return tree.isBlank() ? "No files found." : tree.strip();
    }

    @McpTool(name = "getGitDiff",
            description = "Show uncommitted changes (staged and unstaged) in the git repository. "
                    + "Optionally filter to a specific file. Returns a unified diff.")
    public String getGitDiff(
            @McpToolParam(description = "Optional file path to diff, e.g. 'src/main/java/Foo.java'. "
                    + "Leave empty to show all changes.",
                    required = false) String file) {

        // Combined: staged + unstaged
        String diff;
        if (file != null && !file.isBlank()) {
            diff = git("diff", "HEAD", "--", file);
        } else {
            diff = git("diff", "HEAD");
        }

        if (diff.isBlank()) return "No uncommitted changes.";
        // Truncate long diffs to avoid overwhelming the model
        if (diff.length() > 4000) {
            diff = diff.substring(0, 4000) + "\n... (truncated, " + diff.length() + " chars total)";
        }
        return diff.strip();
    }

    @McpTool(name = "getGitStatus",
            description = "Show the working tree status: modified, added, deleted and untracked files.")
    public String getGitStatus() {
        String status = git("status", "--short");
        if (status.isBlank()) return "Working tree is clean.";
        return status.strip();
    }

    private String git(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);

            Process process = new ProcessBuilder(cmd)
                    .directory(repoDir)
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            int exit = process.waitFor();
            if (exit != 0) {
                log.warn("git {} exited {}: {}", args[0], exit, output);
                return "ERROR: git " + args[0] + " failed: " + output;
            }
            return output;
        } catch (Exception e) {
            log.error("Failed to run git {}: {}", args[0], e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }
}
