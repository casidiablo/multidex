/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.multidex;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Exposes application secondary dex files as files in the application data
 * directory.
 */
final class MultiDexExtractor {

    private static final String TAG = MultiDex.TAG;

    /**
     * We look for additional dex files named {@code classes2.dex},
     * {@code classes3.dex}, etc.
     */
    private static final String DEX_PREFIX = "classes";
    private static final String DEX_SUFFIX = ".dex";

    private static final String EXTRACTED_NAME_EXT = ".classes";
    private static final String EXTRACTED_SUFFIX = ".zip";
    private static final int MAX_EXTRACT_ATTEMPTS = 3;
    private static final String LOCK_FILENAME = "renamelock";

    private static final int BUFFER_SIZE = 0x4000;

    /**
     * Extracts application secondary dexes into files in the application data
     * directory.
     *
     * @return a list of files that were created. The list may be empty if there
     *         are no secondary dex files.
     * @throws IOException if encounters a problem while reading or writing
     *         secondary dex files
     */
    static List<File> load(ApplicationInfo applicationInfo, File dexDir)
            throws IOException {

        File sourceApk = new File(applicationInfo.sourceDir);
        long lastModified = sourceApk.lastModified();
        String extractedFilePrefix = sourceApk.getName()
                + EXTRACTED_NAME_EXT;

        prepareDexDir(dexDir, extractedFilePrefix, lastModified);

        final List<File> files = new ArrayList<File>();
        ZipFile apk = new ZipFile(applicationInfo.sourceDir);
        try {

            int secondaryNumber = 2;

            ZipEntry dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            while (dexFile != null) {
                String fileName = extractedFilePrefix + secondaryNumber + EXTRACTED_SUFFIX;
                File extractedFile = new File(dexDir, fileName);
                files.add(extractedFile);

                if (!extractedFile.isFile()) {
                    int numAttempts = 0;
                    boolean isExtractionSuccessful = false;
                    while (numAttempts < MAX_EXTRACT_ATTEMPTS && !isExtractionSuccessful) {
                        numAttempts++;

                        // Create a zip file (extractedFile) containing only the secondary dex file
                        // (dexFile) from the apk.
                        extract(apk, dexFile, extractedFile, extractedFilePrefix,
                                lastModified);

                        // Verify that the extracted file is indeed a zip file.
                        isExtractionSuccessful = verifyZipFile(extractedFile);

                        // Log the sha1 of the extracted zip file
                        Log.i(TAG, "Extraction " + (isExtractionSuccessful ? "success" : "failed") +
                                " - length " + extractedFile.getAbsolutePath() + ": " +
                                extractedFile.length());
                        if (!isExtractionSuccessful) {
                            // Delete the extracted file
                            extractedFile.delete();
                        }
                    }
                    if (!isExtractionSuccessful) {
                        throw new IOException("Could not create zip file " +
                                extractedFile.getAbsolutePath() + " for secondary dex (" +
                                secondaryNumber + ")");
                    }
                }
                secondaryNumber++;
                dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            }
        } finally {
            try {
                apk.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close resource", e);
            }
        }

        return files;
    }

    private static void prepareDexDir(File dexDir, final String extractedFilePrefix,
            final long sourceLastModified) throws IOException {
        dexDir.mkdir();
        if (!dexDir.isDirectory()) {
            throw new IOException("Failed to create dex directory " + dexDir.getPath());
        }

        // Clean possible old files
        FileFilter filter = new FileFilter() {

            @Override
            public boolean accept(File pathname) {
                return (!pathname.getName().startsWith(extractedFilePrefix))
                    || (pathname.lastModified() < sourceLastModified);
            }
        };
        File[] files = dexDir.listFiles(filter);
        if (files == null) {
            Log.w(TAG, "Failed to list secondary dex dir content (" + dexDir.getPath() + ").");
            return;
        }
        for (File oldFile : files) {
            if (!oldFile.delete()) {
                Log.w(TAG, "Failed to delete old file " + oldFile.getPath());
            }
        }
    }

    private static void extract(ZipFile apk, ZipEntry dexFile, File extractTo,
            String extractedFilePrefix, long sourceLastModified)
                    throws IOException, FileNotFoundException {

        InputStream in = apk.getInputStream(dexFile);
        ZipOutputStream out = null;
        File tmp = File.createTempFile(extractedFilePrefix, EXTRACTED_SUFFIX,
                extractTo.getParentFile());
        Log.i(TAG, "Extracting " + tmp.getPath());
        try {
            out = new ZipOutputStream(new FileOutputStream(tmp));
            try {
                ZipEntry classesDex = new ZipEntry("classes.dex");
                // keep zip entry time since it is the criteria used by Dalvik
                classesDex.setTime(dexFile.getTime());
                out.putNextEntry(classesDex);

                byte[] buffer = new byte[BUFFER_SIZE];
                int length = in.read(buffer);
                while (length != -1) {
                    out.write(buffer, 0, length);
                    length = in.read(buffer);
                }
            } finally {
                out.close();
            }
            if (!tmp.setLastModified(sourceLastModified)) {
                Log.e(TAG, "Failed to set time of \"" + tmp.getAbsolutePath() + "\"." +
                        " This may cause problems with later updates of the apk.");
            }
            Log.i(TAG, "Renaming to " + extractTo.getPath());
            File lockFile = new File(extractTo.getParentFile(), LOCK_FILENAME);
            // Grab the file lock.
            FileOutputStream lockFileOutputStream = new FileOutputStream(lockFile);
            FileLock lockFileLock = lockFileOutputStream.getChannel().lock();
            try {
                if (!extractTo.exists()) {
                    if (!tmp.renameTo(extractTo)) {
                        throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() +
                                "\" to \"" + extractTo.getAbsolutePath() + "\"");
                    }
                }
            } finally {
                // Release the lock file.
                lockFileLock.release();
                lockFileOutputStream.close();
            }
        } finally {
            closeQuietly(in);
            tmp.delete(); // return status ignored
        }
    }

    /**
     * Returns whether the file is a valid zip file.
     */
    private static boolean verifyZipFile(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            try {
                zipFile.close();
                return true;
            } catch (IOException e) {
                Log.w(TAG, "Failed to close zip file: " + file.getAbsolutePath());
            }
        } catch (ZipException ex) {
            Log.w(TAG, "File " + file.getAbsolutePath() + " is not a valid zip file.", ex);
        } catch (IOException ex) {
            Log.w(TAG, "Got an IOException trying to open zip file: " + file.getAbsolutePath(), ex);
        }
        return false;
    }

    /**
     * Closes the given {@code Closeable}. Suppresses any IO exceptions.
     */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close resource", e);
        }
    }
}
