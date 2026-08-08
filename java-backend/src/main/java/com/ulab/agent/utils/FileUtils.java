package com.ulab.agent.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ulab.agent.Main.GSON;

public class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    // Creating a directory
    public static void createDirectory(Path directoryPath, boolean showInfo) {
        if (Files.exists(directoryPath)) return;

        String tag = " [" + directoryPath.getFileName() + "]";
        try {
            Files.createDirectories(directoryPath);
            if (showInfo) log.info(Lang.DIR_CREATE_SUCCESS + tag);
        } catch (IOException e) {
            log.error(Lang.DIR_CREATE_FAIL + tag);
        }
    }

    // Loading a .json file
    public static <T> T loadJsonFile(Path filePath, Type typeOfT) {
        return loadJsonFile(filePath.getFileName().toString(), filePath, typeOfT, false);
    }

    public static <T> T loadJsonFile(String fileName, Path filePath, Type typeOfT) {
        return loadJsonFile(fileName, filePath, typeOfT, false);
    }

    public static <T> T loadJsonFile(Path filePath, Type typeOfT, boolean showInfo) {
        return loadJsonFile(filePath.getFileName().toString(), filePath, typeOfT, showInfo);
    }

    public static <T> T loadJsonFile(String fileName, Path filePath, Type typeOfT, boolean showInfo) {
        if (!Files.exists(filePath)) {
            log.warn(Lang.FILE_NOT_FOUND + " [" + fileName + "] at " + filePath);
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            T myObj = GSON.fromJson(reader, typeOfT);
            if (myObj == null) {
                log.error(Lang.FILE_READ_FAIL + " [" + fileName + "] at " + filePath);
                return null;
            }
            if (showInfo) log.info(String.format(Lang.FILE_LOADED, fileName));
            return myObj;
        } catch (IOException | JsonSyntaxException e) {
            logError("loadJsonFile", e);
            log.error(Lang.FILE_READ_FAIL + " [" + fileName + "] at " + filePath);
        }
        return null;
    }

    // Getting a .json object
    public static JsonObject getJsonObject(Path filePath) {
        return getJsonObject(filePath.getFileName().toString(), filePath, false);
    }

    public static JsonObject getJsonObject(Path filePath, boolean showInfo) {
        return getJsonObject(filePath.getFileName().toString(), filePath, showInfo);
    }

    public static JsonObject getJsonObject(String fileName, Path filePath, boolean showInfo) {
        if (!Files.exists(filePath)) {
            log.warn(Lang.FILE_NOT_FOUND + " [" + fileName + "] at " + filePath);
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            if (jsonObject == null) {
                log.error(Lang.FILE_READ_FAIL + " [" + fileName + "] at " + filePath);
                return null;
            }
            if (showInfo) log.info(String.format(Lang.FILE_LOADED, fileName));
            return jsonObject;
        } catch (IOException | JsonSyntaxException e) {
            logError("getJsonObject", e);
            log.error(Lang.FILE_READ_FAIL + " [" + fileName + "] at " + filePath);
        }
        return null;
    }

    // Saving a .json file
    public static <T> void saveJsonFile(Path savePath, T dataObject) {
        saveJsonFile(savePath.getFileName().toString(), savePath, dataObject, false);
    }

    public static <T> void saveJsonFile(String fileName, Path savePath, T dataObject) {
        saveJsonFile(fileName, savePath, dataObject, false);
    }

    public static <T> void saveJsonFile(String fileName, Path savePath, T dataObject, boolean showInfo) {
        if (dataObject == null) {
            log.error(Lang.FILE_WRITE_FAIL + " [" + fileName + "] at " + savePath);
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(savePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            GSON.toJson(dataObject, writer);
            if (showInfo) log.info(Lang.FILE_WRITE_SUCCESS + " [" + fileName + "]");
        } catch (IOException e) {
            logError("saveJsonFile", e);
            log.error(Lang.FILE_WRITE_FAIL + " [" + fileName + "] at " + savePath);
        }
    }

    // Updating a .json file
    public static <T> void updateJsonFile(Path filePath, T defaultDataObject, boolean showInfo) {
        updateJsonFile(filePath.getFileName().toString(), filePath, defaultDataObject, showInfo);
    }

    public static <T> void updateJsonFile(String fileName, Path filePath, T defaultDataObject, boolean showInfo) {
        if (!Files.exists(filePath)) {
            saveJsonFile(fileName, filePath, defaultDataObject, showInfo);
            return;
        }
        try {
            JsonElement existingJsonTree = getJsonObject(fileName, filePath, false);
            JsonElement defaultJsonTree = GSON.toJsonTree(defaultDataObject);

            if (existingJsonTree == null || !existingJsonTree.isJsonObject() || !defaultJsonTree.isJsonObject()) {
                saveJsonFile(fileName, filePath, defaultDataObject, showInfo);
                return;
            }

            boolean changed = syncJsonObjects(existingJsonTree.getAsJsonObject(), defaultJsonTree.getAsJsonObject());
            if (changed) {
                saveJsonFile(fileName, filePath, existingJsonTree, false);
                if (showInfo) log.info(Lang.FILE_WRITE_SUCCESS + " [" + fileName + "]");
            }
        } catch (Exception e) {
            logError("updateJsonFile", e);
            log.error(Lang.FILE_WRITE_FAIL + " [" + fileName + "] at " + filePath);
        }
    }

    public static boolean syncJsonObjects(JsonObject existingObj, JsonObject targetObj) {
        boolean changed = false;
        var existingIterator = existingObj.entrySet().iterator();
        while (existingIterator.hasNext()) {
            var entry = existingIterator.next();
            if (!targetObj.has(entry.getKey())) {
                existingIterator.remove();
                changed = true;
            }
        }
        for (var entry : targetObj.entrySet()) {
            String key = entry.getKey();
            JsonElement targetVal = entry.getValue();
            if (!existingObj.has(key)) {
                existingObj.add(key, targetVal);
                changed = true;
                continue;
            }
            JsonElement existingVal = existingObj.get(key);
            if (existingVal.isJsonObject() && targetVal.isJsonObject()) {
                if (syncJsonObjects(existingVal.getAsJsonObject(), targetVal.getAsJsonObject())) {
                    changed = true;
                }
            } else if (!isSameJsonType(existingVal, targetVal)) {
                existingObj.add(key, targetVal);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean isSameJsonType(JsonElement elementA, JsonElement elementB) {
        if (elementA.isJsonObject() && elementB.isJsonObject()) return true;
        if (elementA.isJsonArray() && elementB.isJsonArray()) return true;
        if (elementA.isJsonPrimitive() && elementB.isJsonPrimitive()) return true;
        return elementA.isJsonNull() && elementB.isJsonNull();
    }

    /**
     * Writes a failure down, wherever this application's logs go.
     *
     * This used to open a file of its own under logs/errors/ and print the
     * stack trace into it. Three things were wrong with that. The file was
     * named from a count of the files already in the folder, so two threads
     * failing at once wrote to the same name and one lost its trace. Nothing
     * ever removed them, so the folder grew for as long as the app ran. And a
     * stack trace out of the client queries carries SQL fragments and bound
     * parameters, which is a thing to be careful where you put.
     *
     * Handing it to SLF4J solves all three at once: the rolling appender in
     * logback-spring.xml caps the size and the number of files kept, one
     * appender means no two writers race for a name, and there is a single
     * place that decides where any of it is written.
     *
     * @param at where in the code this happened, in plain words
     */
    public static void logError(Exception e) {
        logError("undefined", e);
    }

    public static void logError(String at, Exception e) {
        log.error("Failed in {}: {}", at, e.toString(), e);
    }
}
