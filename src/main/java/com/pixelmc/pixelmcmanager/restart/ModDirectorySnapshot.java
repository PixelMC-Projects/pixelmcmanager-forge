package com.pixelmc.pixelmcmanager.restart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Directory metadata only: no hashing and no reads of JAR contents during inventory. */
public record ModDirectorySnapshot(Map<String, Entry> entries) {
    public record Entry(long size, long modified, String fileKey) {}
    public static boolean isJar(String name) { return name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"); }
    public static Entry stat(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
        if (!a.isRegularFile()) throw new IOException("模组JAR不是普通文件");
        return new Entry(a.size(), a.lastModifiedTime().toMillis(), String.valueOf(a.fileKey()));
    }
    public static ModDirectorySnapshot capture(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("mods目录不可用");
        Map<String, Entry> result = new TreeMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> isJar(p.getFileName().toString())).toList())
                result.put(path.getFileName().toString(), stat(path));
        }
        return new ModDirectorySnapshot(Map.copyOf(result));
    }
    public static void validateChangedJar(Path path) throws IOException {
        Entry before = stat(path);
        // Only inspect the ZIP central directory of the changed file, never all entries.
        try (ZipFile zip = new ZipFile(path.toFile())) { zip.size(); }
        if (!before.equals(stat(path))) throw new IOException("模组JAR仍在写入");
    }
}
