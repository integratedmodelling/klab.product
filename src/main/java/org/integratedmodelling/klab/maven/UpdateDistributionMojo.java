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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.integratedmodelling.klab.api.collections.Pair;
import org.integratedmodelling.klab.api.engine.distribution.Product;
import org.integratedmodelling.klab.api.engine.distribution.Release;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Create the distribution.properties and product.properties files for all products found in the compiled
 * targets. If a distributionUrl parameter is passed, use the correspondent files as templates and update
 * them, adding any new release and new product found. In that case, the maxReleasesPerProduct parameter
 * controls the number of releases kept around.
 *
 * Default release structure is in target/distribution of the main project and contains the distribution.properties
 * file with the list of products, each corresponding to a subdirectory with the product's name and the same structure
 * for all the releases. The Maven plugin builds one release only, but synchronizes with the contents of anything
 * already on the web at the remoteDistributionUrl parameter (if passed), so that the contents can be uploaded
 * as a unit without changes. The local path to the release files is also filled in to allow local use in case the
 * production release files aren't moved to the product directory (moveReleaseFiles parameter).
 */
@Mojo(
        name = "update-distribution",
        requiresDependencyResolution = ResolutionScope.COMPILE,
        defaultPhase = LifecyclePhase.DEPLOY,
        threadSafe = true)
public class UpdateDistributionMojo extends AbstractMojo {

    @Parameter(readonly = true, defaultValue = "${project}")
    private MavenProject project;

    @Parameter(readonly = true, required = false)
    private URL remoteDistributionUrl;

    @Parameter(readonly = true, required = false)
    private int maxReleasesPerProduct;

    @Parameter(readonly = true, required = false, defaultValue = "true")
    private boolean moveReleaseFiles;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (remoteDistributionUrl != null) {
            // TODO pre-populate the property files and define the local product paths
        }

        List<Pair<Product.ProductType, File>> releaseFiles = new ArrayList<>();

        try (var walker = Files.walk(project.getFile().toPath(), FileVisitOption.FOLLOW_LINKS)) {
            for (var releaseFile :
                    walker.filter(path -> path.toString().endsWith(Release.RELEASE_PROPERTIES_FILE)).toList()) {

                    var release = Release.create(releaseFile.toFile());
                    releaseFiles.add(Pair.of(release.getProduct().getProductType(), releaseFile.toFile()));
                    /*
                    build release, find or create the correspondent product
                     */

                //                    products.add(new ProductImpl(productFile.toFile()));
            }
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }
}
