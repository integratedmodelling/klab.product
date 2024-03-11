package org.integratedmodelling.klab.maven;

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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.integratedmodelling.klab.api.collections.Pair;
import org.integratedmodelling.klab.api.engine.distribution.Build;
import org.integratedmodelling.klab.api.engine.distribution.Distribution;
import org.integratedmodelling.klab.api.engine.distribution.Product;
import org.integratedmodelling.klab.api.engine.distribution.RunningInstance;
import org.integratedmodelling.klab.api.engine.distribution.impl.BuildImpl;
import org.integratedmodelling.klab.api.engine.distribution.impl.ProductImpl;
import org.integratedmodelling.klab.api.scope.Scope;
import org.integratedmodelling.klab.api.utils.PropertiesBasedObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Create the distribution.properties and product.properties files for all products found in the compiled
 * targets. If a distributionUrl parameter is passed, use the correspondent files as templates and update
 * them, adding any new release and new product found. In that case, the maxReleasesPerProduct parameter
 * controls the number of releases kept around.
 * <p>
 * Default release structure is in target/distribution of the main project and contains the
 * distribution.properties file with the list of products, each corresponding to a subdirectory with the
 * product's name and the same structure for all the releases. The Maven plugin builds one release only, but
 * synchronizes with the contents of anything already on the web at the remoteDistributionUrl parameter (if
 * passed), so that the contents can be uploaded as a unit without changes. The local path to the release
 * files is also filled in to allow local use in case the production release files aren't moved to the product
 * directory (moveReleaseFiles parameter).
 * <p>
 * This goal should be run in a separate module that depends on all the products that need to be part of the
 * distribution.
 */
@Mojo(
        name = "update-distribution",
        requiresDependencyResolution = ResolutionScope.COMPILE,
        defaultPhase = LifecyclePhase.INSTALL,
        threadSafe = true)
public class UpdateDistributionMojo extends AbstractMojo {

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Parameter(required = false)
    private URL remoteDistributionUrl;

    @Parameter(required = true)
    private String releaseName;

    @Parameter(required = true)
    private String distributionName;

    @Parameter(required = true)
    private String distributionVersion;

    @Parameter(required = false, defaultValue = "distribution")
    private String outputDirectory;

    /**
     * Number of builds to keep in the generated releases. Works in combination with remoteDistributionUrl (if
     * not connected to an online distribution, this will only produce one build per release and one release
     * per product).
     */
    @Parameter(required = false)
    private int maxBuildsPerRelease;

    /**
     * If true, download the remote builds to create a self-consistent distribution. Only works in combination
     * with remoteDistributionUrl. May cause long downloads and large file sized.
     */
    @Parameter(required = false, defaultValue = "false")
    private boolean synchronizeRemoteBuilds;

    /**
     * Move the build files from the original directory to the distribution. If false, only the
     * build.properties files are generated.
     */
    @Parameter(required = false, defaultValue = "true")
    private boolean moveReleaseFiles;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (remoteDistributionUrl != null) {
            // TODO pre-populate the property files and define the local product paths
        }

        List<Pair<File, BuildImpl>> builds = new ArrayList<>();
        Map<String, ProductImpl> products = new HashMap<>();
        var rootDirectory = Path.of(mavenSession.getExecutionRootDirectory());

        /*
        Create Build objects from each build found in the project
         */
        try (var walker = Files.walk(rootDirectory, FileVisitOption.FOLLOW_LINKS)) {
            for (var releaseFile :
                    walker.filter(path -> path.toString().endsWith(Build.BUILD_PROPERTIES_FILE)).toList()) {

                var build = getBuild(releaseFile);

                if (!products.containsKey(build.getProduct().getName())) {
                    products.put(build.getProduct().getName(), build.getProduct());
                }

                getLog().info("Detected build file " + releaseFile);

                builds.add(Pair.of(releaseFile.toFile(), build));
            }

            /*
            Build the distribution file
            TODO handle merge with networked distro
             */
            if (products.size() == 0) {
                getLog().info("Distribution generator: no products detected in configured path (" + rootDirectory + "). Exiting.");
                return;
            }

            File distributionDirectory = new File(project.getFile() + File.separator + outputDirectory);
            distributionDirectory.mkdirs();
            var distributionPropertiesFile =
                    new File(distributionDirectory + File.separator + Distribution.DISTRIBUTION_PROPERTIES_FILE);



            for (var productName : products.keySet()) {

                var productDirectory = new File(distributionDirectory + File.separator + productName);
                productDirectory.mkdirs();

                var releaseDirectory = new File(productDirectory + File.separator + releaseName);
                releaseDirectory.mkdirs();

                for (var build : builds) {
                    if (build.getSecond().getProduct().getName().equals(productName)) {
                        if (moveReleaseFiles) {

                        }
                    }
                }
            }

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

    private static BuildImpl getBuild(Path releaseFile) {
        var build = new BuildImpl(releaseFile.toFile()) {
            @Override
            public Product.Status getStatus() {
                return null;
            }

            @Override
            public boolean synchronize(Scope scope) {
                return false;
            }

            @Override
            public RunningInstance launch(Scope scope) {
                return null;
            }

            @Override
            public String getRemotePath() {
                return null;
            }
        };

        build.setLocalWorkspace(releaseFile.getParent().toFile());
        return build;
    }
}
