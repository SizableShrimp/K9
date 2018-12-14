package com.tterrag.k9.mappings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tterrag.k9.util.NullHelper;
import com.tterrag.k9.util.Nullable;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@Slf4j
public abstract class MappingDownloader<M extends Mapping, T extends MappingDatabase<M>> {
    
    @FunctionalInterface
    public interface DatabaseFactory<T> {

        T create(String version) throws NoSuchVersionException;

    }
    
    private static final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    
    private static final String VERSION_FILE = ".dataversion";
    
    private final Path dataFolder = Paths.get(".", "data");
    
    private final String folder;
    private final DatabaseFactory<T> dbFactory;
    private final int version;
    
    private final Map<String, T> mappingTable = new HashMap<>();
    
    protected abstract void checkUpdates();
    
    public Path getDataFolder() {
        return dataFolder.resolve(folder);
    }
    
    protected void collectParsers(GsonBuilder builder) {}
    
    @Nullable
    private Gson gson; // lazy load
    protected Gson getGson() {
        Gson ret = this.gson;
        if (ret == null) {
            GsonBuilder builder = new GsonBuilder();
            collectParsers(builder);
            ret = this.gson = NullHelper.notnullL(builder.create(), "GsonBuilder#create");
        }
        return ret;
    }
    
    private static boolean hasCleanedUp = false;

    @SneakyThrows
    public void start() {
        // Nuke all non-directories, and directories without a version file. Do this only once globally.
        if (!hasCleanedUp) {
            synchronized (MappingDownloader.class) {
                File[] folders = dataFolder.toFile().listFiles();
                for (File folder : folders) {
                    if (folder.isDirectory()) {
                        File versionfile = new File(folder, VERSION_FILE);
                        if (!versionfile.exists()) {
                            log.info("Deleting outdated data found in " + folder);
                            FileUtils.deleteDirectory(folder);
                        }
                    } else {
                        log.warn("Found unknown file " + folder + " in data folder. Deleting!");
                        folder.delete();
                    }
                }
                hasCleanedUp = true;
            }
        }
        
        // Check this download folder's version file, if it is nonexistent or out of date, delete it and start fresh
        File versionfile = getDataFolder().resolve(VERSION_FILE).toFile();
        int currentVersion = -1;
        if (versionfile.exists()) {
            try {
                currentVersion = Integer.parseInt(Files.readFirstLine(versionfile, StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.error("Invalid " + VERSION_FILE + ": " + versionfile, e);
            }
        }
        if (currentVersion < version) {
            File dataDir = getDataFolder().toFile();
            if (dataDir.exists()) {
                log.info("Found outdated data in folder " + dataDir + " (" + currentVersion + " < " + version + "). Deleting!");
                FileUtils.deleteDirectory(dataDir);
            } else {
                log.info("Creating new data folder " + dataDir);
            }
            dataDir.mkdir();
            FileUtils.write(new File(dataDir, VERSION_FILE), Integer.toString(version), Charsets.UTF_8);
        }
        
        executor.scheduleAtFixedRate(this::checkUpdates, 0, 1, TimeUnit.HOURS);
    }
    
    protected void remove(String mcver) {
        mappingTable.remove(mcver);
    }

    public T getDatabase(String mcver) throws NoSuchVersionException {
        T db = mappingTable.get(mcver);
        if (db == null) {
            db = dbFactory.create(mcver);
            try {
                db.reload();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            mappingTable.put(mcver, db);
        }
        return db;
    }
    
    public Collection<M> lookup(MappingType type, String name, String mcver) throws NoSuchVersionException {
        return getDatabase(mcver).lookup(type, name);
    }
}