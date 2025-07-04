/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.java.impl.refactoring.migration;

import consulo.application.Application;
import consulo.component.util.text.UniqueNameGenerator;
import consulo.container.boot.ContainerPathManager;
import consulo.language.codeStyle.CodeStyleSettingsManager;
import consulo.logging.Logger;
import consulo.util.io.FileUtil;
import consulo.util.jdom.JDOMUtil;
import consulo.util.lang.StringUtil;
import consulo.util.xml.serializer.InvalidDataException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public class MigrationMapSet {
    private static final Logger LOG = Logger.getInstance(MigrationMapSet.class);

    private ArrayList<MigrationMap> myMaps;
    private static final String MIGRATION_MAP = "migrationMap";
    private static final String ENTRY = "entry";
    private static final String NAME = "name";
    private static final String OLD_NAME = "oldName";
    private static final String NEW_NAME = "newName";
    private static final String DESCRIPTION = "description";
    private static final String VALUE = "value";
    private static final String TYPE = "type";
    private static final String PACKAGE_TYPE = "package";
    private static final String CLASS_TYPE = "class";
    private static final String RECURSIVE = "recursive";

    private static final String[] DEFAULT_MAPS = new String[]{
        "/com/intellij/refactoring/migration/res/Swing__1_0_3____1_1_.xml",
    };
    private Set<String> myDeletedMaps = new TreeSet<>();

    public MigrationMapSet() {
    }

    public void addMap(MigrationMap map) {
        if (myMaps == null) {
            loadMaps();
        }
        myMaps.add(map);
        //    saveMaps();
    }

    @Nullable
    public MigrationMap findMigrationMap(@Nonnull String name) {
        if (myMaps == null) {
            loadMaps();
        }
        for (MigrationMap map : myMaps) {
            if (name.equals(map.getName())) {
                return map;
            }
        }
        return null;
    }

    public void replaceMap(MigrationMap oldMap, MigrationMap newMap) {
        for (int i = 0; i < myMaps.size(); i++) {
            if (myMaps.get(i) == oldMap) {
                myMaps.set(i, newMap);
            }
        }
    }

    public void removeMap(MigrationMap map) {
        if (myMaps == null) {
            loadMaps();
        }
        myMaps.remove(map);
        String name = map.getFileName();
        if (isPredefined(name)) {
            myDeletedMaps.add(name);
        }
    }

    private static boolean isPredefined(String name) {
        boolean fileNameMatches = Application.get().getExtensionPoint(PredefinedMigrationProvider.class).anyMatchSafe(provider -> {
            File file = new File(provider.getMigrationMap().getFile());
            return FileUtil.getNameWithoutExtension(file).equals(name);
        });
        if (fileNameMatches) {
            return true;
        }

        for (String defaultTemplate : DEFAULT_MAPS) {
            String fileName = FileUtil.getNameWithoutExtension(StringUtil.getShortName(defaultTemplate, '/'));

            if (fileName.equals(name)) {
                return true;
            }
        }
        return false;
    }

    public MigrationMap[] getMaps() {
        if (myMaps == null) {
            loadMaps();
        }
        MigrationMap[] ret = new MigrationMap[myMaps.size()];
        for (int i = 0; i < myMaps.size(); i++) {
            ret[i] = myMaps.get(i);
        }
        return ret;
    }

    private static File getMapDirectory() {
        File dir = new File(ContainerPathManager.get().getConfigPath() + File.separator + "migration");

        if (!dir.exists() && !dir.mkdir()) {
            LOG.error("cannot create directory: " + dir.getAbsolutePath());
            return null;
        }

        return dir;
    }

    private void copyPredefinedMaps(File dir) {
        File deletedFiles = new File(dir, "deleted.txt");
        if (deletedFiles.isFile()) {
            try {
                myDeletedMaps.addAll(Arrays.asList(
                    consulo.ide.impl.idea.openapi.util.io.FileUtil.loadFile(deletedFiles, true).split("\n")
                ));
            }
            catch (IOException e) {
                LOG.error(e);
            }
        }

        Application.get().getExtensionPoint(PredefinedMigrationProvider.class).forEach(provider -> {
            URL migrationMap = provider.getMigrationMap();
            String fileName = new File(migrationMap.getFile()).getName();
            if (myDeletedMaps.contains(FileUtil.getNameWithoutExtension(fileName))) {
                return;
            }
            copyMap(dir, migrationMap, fileName);
        });

        for (String defaultTemplate : DEFAULT_MAPS) {
            URL url = MigrationMapSet.class.getResource(defaultTemplate);
            LOG.assertTrue(url != null);
            String fileName = defaultTemplate.substring(defaultTemplate.lastIndexOf("/") + 1);
            if (myDeletedMaps.contains(FileUtil.getNameWithoutExtension(fileName))) {
                continue;
            }
            copyMap(dir, url, fileName);
        }
    }

    private static void copyMap(File dir, URL url, String fileName) {
        File targetFile = new File(dir, fileName);
        if (targetFile.isFile()) {
            return;
        }

        try (FileOutputStream outputStream = new FileOutputStream(targetFile); InputStream inputStream = url.openStream()) {
            FileUtil.copy(inputStream, outputStream);
        }
        catch (Exception e) {
            LOG.error(e);
        }
    }

    private static File[] getMapFiles(File dir) {
        if (dir == null) {
            return new File[0];
        }
        File[] ret = dir.listFiles((f) -> FileUtil.extensionEquals(f.getPath(), "xml"));
        if (ret == null) {
            LOG.error("cannot read directory: " + dir.getAbsolutePath());
            return new File[0];
        }
        return ret;
    }

    private void loadMaps() {
        myMaps = new ArrayList<>();


        File dir = getMapDirectory();
        copyPredefinedMaps(dir);

        File[] files = getMapFiles(dir);
        for (File file : files) {
            try {
                MigrationMap map = readMap(file);
                if (map != null) {
                    map.setFileName(FileUtil.getNameWithoutExtension(file));
                    myMaps.add(map);
                }
            }
            catch (InvalidDataException | JDOMException e) {
                LOG.error("Invalid data in file: " + file.getAbsolutePath());
            }
            catch (IOException e) {
                LOG.error(e);
            }
        }
    }

    private static MigrationMap readMap(File file) throws JDOMException, InvalidDataException, IOException {
        if (!file.exists()) {
            return null;
        }

        Element root = JDOMUtil.load(file);
        if (!MIGRATION_MAP.equals(root.getName())) {
            throw new InvalidDataException();
        }

        MigrationMap map = new MigrationMap();

        for (Element node : root.getChildren()) {
            if (NAME.equals(node.getName())) {
                String name = node.getAttributeValue(VALUE);
                map.setName(name);
            }
            if (DESCRIPTION.equals(node.getName())) {
                String description = node.getAttributeValue(VALUE);
                map.setDescription(description);
            }

            if (ENTRY.equals(node.getName())) {
                MigrationMapEntry entry = new MigrationMapEntry();
                String oldName = node.getAttributeValue(OLD_NAME);
                if (oldName == null) {
                    throw new InvalidDataException();
                }
                entry.setOldName(oldName);
                String newName = node.getAttributeValue(NEW_NAME);
                if (newName == null) {
                    throw new InvalidDataException();
                }
                entry.setNewName(newName);
                String typeStr = node.getAttributeValue(TYPE);
                if (typeStr == null) {
                    throw new InvalidDataException();
                }
                entry.setType(MigrationMapEntry.CLASS);
                if (typeStr.equals(PACKAGE_TYPE)) {
                    entry.setType(MigrationMapEntry.PACKAGE);
                    String isRecursiveStr = node.getAttributeValue(RECURSIVE);
                    if (isRecursiveStr != null && isRecursiveStr.equals("true")) {
                        entry.setRecursive(true);
                    }
                    else {
                        entry.setRecursive(false);
                    }
                }
                map.addEntry(entry);
            }
        }

        return map;
    }

    public void saveMaps() throws IOException {
        File dir = getMapDirectory();
        if (dir == null) {
            return;
        }

        File[] files = getMapFiles(dir);

        String[] filePaths = new String[myMaps.size()];
        Document[] documents = new Document[myMaps.size()];

        UniqueNameGenerator namesProvider = new UniqueNameGenerator();
        for (int i = 0; i < myMaps.size(); i++) {
            MigrationMap map = myMaps.get(i);

            filePaths[i] = dir + File.separator + namesProvider.generateUniqueName(map.getFileName()) + ".xml";
            documents[i] = saveMap(map);
        }

        JDOMUtil.updateFileSet(files, filePaths, documents, CodeStyleSettingsManager.getSettings(null).getLineSeparator());

        if (!myDeletedMaps.isEmpty()) {
            FileUtil.writeToFile(new File(dir, "deleted.txt"), StringUtil.join(myDeletedMaps, "\n"));
        }
    }

    private static Document saveMap(MigrationMap map) {
        Element root = new Element(MIGRATION_MAP);

        Element nameElement = new Element(NAME);
        nameElement.setAttribute(VALUE, map.getName());
        root.addContent(nameElement);

        Element descriptionElement = new Element(DESCRIPTION);
        descriptionElement.setAttribute(VALUE, StringUtil.notNullize(map.getDescription()));
        root.addContent(descriptionElement);

        for (int i = 0; i < map.getEntryCount(); i++) {
            MigrationMapEntry entry = map.getEntryAt(i);
            Element element = new Element(ENTRY);
            element.setAttribute(OLD_NAME, entry.getOldName());
            element.setAttribute(NEW_NAME, entry.getNewName());
            if (entry.getType() == MigrationMapEntry.PACKAGE) {
                element.setAttribute(TYPE, PACKAGE_TYPE);
                element.setAttribute(RECURSIVE, Boolean.valueOf(entry.isRecursive()).toString());
            }
            else {
                element.setAttribute(TYPE, CLASS_TYPE);
            }
            root.addContent(element);
        }

        return new Document(root);
    }
}
