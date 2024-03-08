package org.integratedmodelling.klab;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.dependency.fromDependencies.CopyDependenciesMojo;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@Mojo(
        name = "package-product",
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        defaultPhase = LifecyclePhase.PACKAGE,
        threadSafe = true)
public class ProductPackageMojo extends CopyDependenciesMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void doExecute() throws MojoExecutionException {

        Artifact mainArtifact = project.getArtifact();

        File destDir = DependencyUtil.getFormattedOutputDirectory(
                useSubDirectoryPerScope,
                useSubDirectoryPerType,
                useSubDirectoryPerArtifact,
                useRepositoryLayout,
                stripVersion,
                stripType,
                outputDirectory,
                mainArtifact);

        System.out.println("THE FUCKA IS " + destDir);

        /**
         * Create all deps in configured directory
         */
        super.doExecute();

        /**
         * Add the main JAR from the target directory
         */

        /**
         * Create the MD5 hash file
         */

        /**
         * Create the product definition file
         */

        //
        //        File f = outputDirectory;
        //
        //        if (!f.exists()) {
        //            f.mkdirs();
        //        }
        //
        //        File touch = new File(f, "touch.txt");
        //
        //        FileWriter w = null;
        //        try {
        //            w = new FileWriter(touch);
        //            w.write("touch.txt");
        //        } catch (IOException e) {
        //            throw new MojoExecutionException("Error creating file " + touch, e);
        //        } finally {
        //            if (w != null) {
        //                try {
        //                    w.close();
        //                } catch (IOException e) {
        //                    // ignore
        //                }
        //            }
        //        }
    }
}
