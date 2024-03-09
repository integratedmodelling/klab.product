package org.integratedmodelling.klab.maven;

import org.integratedmodelling.klab.api.exceptions.KlabException;
import org.integratedmodelling.klab.api.exceptions.KlabIOException;
import org.integratedmodelling.klab.api.utils.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.io.FileUtils.writeLines;

public class Digest {
    /**
     * Create a md5sum file digest for a directory in a directory.
     *
     * @param directory the directory
     * @param fileName  the file name
     * @throws org.integratedmodelling.klab.api.exceptions.KlabException the klab exception
     */
    public static void createMD5Digest(File directory, String fileName) {

        if (!directory.exists() || !directory.canWrite() || !directory.isDirectory()) {
            return;
        }

        List<String> digest = new ArrayList<>();
        addFilesToMD5Digest(directory, digest, ".");

        try {
            writeLines(new File(directory + File.separator + fileName), digest);
        } catch (IOException e) {
            throw new KlabIOException(e);
        }
    }

    private static void addFilesToMD5Digest(File directory, List<String> digest, String pathPrefix)
            throws KlabException {

        for (File f : directory.listFiles()) {

            if (f.isDirectory()) {
                addFilesToMD5Digest(f, digest, pathPrefix + "/" + Utils.Files.getFileName(f.toString()));
            } else {
                try (FileInputStream fis = new FileInputStream(f)) {
                    String md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(fis);
                    digest.add(md5 + "  " + pathPrefix + "/" + Utils.Files.getFileName(f.toString()));
                } catch (IOException e) {
                    throw new KlabIOException(e);
                }
            }
        }
    }

    public static String getFileHash(File file) {
        String ret = "";
        try (FileInputStream fis = new FileInputStream(file)) {
            ret = org.apache.commons.codec.digest.DigestUtils.md5Hex(fis);
        } catch (IOException e) {
            throw new KlabIOException(e);
        }
        return ret;
    }

}
