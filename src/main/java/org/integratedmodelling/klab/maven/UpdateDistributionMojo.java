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
import org.integratedmodelling.klab.api.engine.distribution.*;
import org.integratedmodelling.klab.api.utils.Utils;

import java.io.File;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Create the distribution.properties and product.properties files for all products found in the
 * compiled targets. If a distributionUrl parameter is passed, use the correspondent files as
 * templates and update them, adding any new release and new product found. In that case, the
 * maxReleasesPerProduct parameter controls the number of releases kept around.
 *
 * <p>The default release structure is in the target / distribution of the main project and contains
 * the distribution.properties file with the list of products, each corresponding to a subdirectory
 * with the product's name and the same structure for all the releases. The Maven plugin builds one
 * single build in target/ and should be invoked after <code>clean</code>. If more than one build is
 * found for the same product, only the latest one is used.
 *
 * <p>Synchronization with the contents of anything already on the web at the remoteDistributionUrl
 * parameter (if passed), so that the contents can be uploaded as a unit without changes and merged
 * with any other existing remote content. Clients should be aware that some builds that are
 * referenced in the properties may have been deleted after the fact and just ignore them without
 * error.
 *
 * <p>This goal should be run in a separate module that depends on all the products that need to be
 * part of the distribution and have been built using the independent {@link ReleasePackageMojo}.
 * The build ID in those modules will be overwritten by the build ID for the distribution, so that
 * it remains consistent across a build. The pom.xml containing this goal should have the desired
 * built modules as dependencies to ensure that the distribution is created correctly.
 *
 * <p>The hierarchy in the distribution file structure is
 * distribution/version/release/build/product. Originally it was distribution/release/product/build.
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
  private URL distributionRootUrl;

  @Parameter(required = true)
  private String releaseName;

  @Parameter(required = true)
  private String distributionName;

  @Parameter(required = true)
  private String distributionVersion;

  @Parameter(required = false, defaultValue = "target/distribution")
  private String outputDirectory;

  @Parameter(required = false, defaultValue = "2")
  private int maxReleasesPerVersion;

  @Parameter(required = false, defaultValue = "3")
  private int maxBuildsPerRelease;

  @Parameter(required = false, defaultValue = "3")
  private int maxVersionsPerDistribution;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    OffsetDateTime now = OffsetDateTime.now();
    String buildTimestamp =
        now.getYear()
            + String.format("%02d", now.getMonthValue())
            + String.format("%02d", now.getDayOfMonth())
            + String.format("%02d", now.getHour())
            + String.format("%02d", now.getMinute());

    List<Pair<File, BuildImpl>> builds = new ArrayList<>();
    Set<String> products = new HashSet<>();
    var rootDirectory = Path.of(mavenSession.getExecutionRootDirectory());

    /*
    Create Build objects from each build found in the project
     */
    try (var walker = Files.walk(rootDirectory, FileVisitOption.FOLLOW_LINKS)) {

      for (var releaseFile :
          walker
              .filter(path -> path.toString().endsWith(Distribution.BUILD_PROPERTIES_FILE))
              .toList()) {
        getLog().info("Detected build file " + releaseFile);
        var build = getBuild(releaseFile);
        products.add(build.getProperty(Distribution.PRODUCT_NAME_PROPERTY));
        builds.add(Pair.of(releaseFile.toFile(), build));
      }

      // Filter builds to keep only the most recent build per product, in case the build wasn't
      // cleaned before.
      Map<String, Pair<File, BuildImpl>> latestBuilds = new HashMap<>();
      for (var buildPair : builds) {
        String productName = buildPair.getSecond().getProperty(Distribution.PRODUCT_NAME_PROPERTY);
        String buildId = buildPair.getSecond().getProperty(Distribution.BUILD_VERSION_PROPERTY);

        if (!latestBuilds.containsKey(productName)) {
          latestBuilds.put(productName, buildPair);
        } else {
          String existingBuildId =
              latestBuilds
                  .get(productName)
                  .getSecond()
                  .getProperty(Distribution.BUILD_VERSION_PROPERTY);
          if (buildId.compareTo(existingBuildId) > 0) {
            latestBuilds.put(productName, buildPair);
          }
        }
      }
      builds = new ArrayList<>(latestBuilds.values());

      /*
      Build the distribution file
      TODO handle merge with networked distro
       */
      if (products.isEmpty()) {
        getLog()
            .info(
                "Distribution generator: no products detected in configured path ("
                    + rootDirectory
                    + "). Exiting.");
        return;
      }

      /* build the directory structure to host the products:
      distribution/version/release/build/product. At each
      step, merge with any existing properties so that when the distribution is uploaded,
      sub-directories from previous versions are preserved up to
      the numbers configured. */

      File distributionDirectory =
          new File(
              project.getBasedir()
                  + File.separator
                  + outputDirectory
                  + File.separator
                  + distributionName);
      distributionDirectory.mkdirs();
      var distributionUrl = Utils.URLs.newURL(distributionRootUrl + "/" + distributionName);

      var distributionProperties =
          Utils.Properties.create(
              Distribution.DISTRIBUTION_NAME_PROPERTY,
              distributionName,
              Distribution.DISTRIBUTION_DATE_PROPERTY,
              Instant.now().toString(),
              Distribution.DISTRIBUTION_VERSIONS_PROPERTY,
              distributionVersion);

      if (distributionRootUrl != null) {
        distributionProperties.setProperty(
            Distribution.DISTRIBUTION_VERSIONS_PROPERTY,
            synchronize(
                Utils.URLs.newURL(
                    distributionUrl + "/" + Distribution.DISTRIBUTION_PROPERTIES_FILE),
                Distribution.DISTRIBUTION_VERSIONS_PROPERTY,
                distributionVersion));
      }

      distributionProperties.save(
          new File(distributionDirectory, Distribution.DISTRIBUTION_PROPERTIES_FILE));

      var versionDirectory = new File(distributionDirectory + File.separator + distributionVersion);
      var versionUrl = Utils.URLs.newURL(distributionUrl + "/" + distributionVersion);
      versionDirectory.mkdirs();
      var versionProperties =
          Utils.Properties.create(
              Distribution.VERSION_NAME_PROPERTY,
              distributionVersion,
              Distribution.VERSION_RELEASES_PROPERTY,
              releaseName);
      if (distributionRootUrl != null) {
        versionProperties.setProperty(
            Distribution.VERSION_RELEASES_PROPERTY,
            synchronize(
                Utils.URLs.newURL(versionUrl + "/" + Distribution.VERSION_PROPERTIES_FILE),
                Distribution.VERSION_RELEASES_PROPERTY,
                releaseName));
      }
      versionProperties.save(new File(versionDirectory, Distribution.VERSION_PROPERTIES_FILE));

      var releaseDirectory = new File(versionDirectory + File.separator + releaseName);
      var releaseUrl = Utils.URLs.newURL(versionUrl + "/" + distributionVersion);
      releaseDirectory.mkdirs();
      var releaseProperties =
          Utils.Properties.create(
              Distribution.RELEASE_NAME_PROPERTY,
              releaseName,
              Distribution.RELEASE_BUILDS_PROPERTY,
              buildTimestamp);
      if (distributionRootUrl != null) {
        releaseProperties.setProperty(
            Distribution.RELEASE_BUILDS_PROPERTY,
            synchronize(
                Utils.URLs.newURL(releaseUrl + "/" + Distribution.RELEASE_PROPERTIES_FILE),
                Distribution.RELEASE_BUILDS_PROPERTY,
                buildTimestamp));
      }
      releaseProperties.save(new File(releaseDirectory, Distribution.RELEASE_PROPERTIES_FILE));

      var buildDirectory = new File(releaseDirectory + File.separator + buildTimestamp);
      buildDirectory.mkdirs();
      // no sync for builds as their contents are immutable once uploaded
      var buildProperties =
          Utils.Properties.create(
              Distribution.BUILD_NAME_PROPERTY,
              buildTimestamp,
              Distribution.BUILD_PRODUCTS_PROPERTY,
              String.join(",", products));
      buildProperties.save(new File(buildDirectory, Distribution.BUILD_PROPERTIES_FILE));

      for (var built : builds) {

        var productSource = built.getFirst();
        var build = built.getSecond();

        var productType =
            Distribution.Product.Type.valueOf(
                build.getProperty(Distribution.PRODUCT_TYPE_PROPERTY).toUpperCase());
        var platform =
            Distribution.Product.Platform.valueOf(
                build.getProperty(Distribution.PRODUCT_PLATFORM_PROPERTY).toUpperCase());

        var productDirectory =
            new File(
                buildDirectory
                    + File.separator
                    + build.getProperty(Distribution.PRODUCT_NAME_PROPERTY));

        // no sync for builds as their contents are immutable once uploaded
        var productProperties =
            Utils.Properties.create(
                Distribution.PRODUCT_PLATFORM_PROPERTY,
                platform.name(),
                Distribution.PRODUCT_MAINCLASS_PROPERTY,
                build.getExecutable(),
                Distribution.PRODUCT_TYPE_PROPERTY,
                productType.name());

        getLog()
            .info(
                "Moving "
                    + productType
                    + " of build "
                    + buildTimestamp
                    + " from "
                    + productSource.toPath().getParent()
                    + " to "
                    + productDirectory);

        Files.move(productSource.toPath().getParent(), productDirectory.toPath(), REPLACE_EXISTING);
        Files.deleteIfExists(
            new File(productDirectory, Distribution.BUILD_PROPERTIES_FILE).toPath());
        productProperties.save(new File(productDirectory, Distribution.PRODUCT_PROPERTIES_FILE));
      }

      getLog()
          .info(
              "Distribution created in "
                  + distributionDirectory
                  + " with "
                  + products.size()
                  + " products");

    } catch (Throwable e) {
      e.printStackTrace();
      throw new MojoExecutionException(e);
    }
  }

  private String synchronize(
      URL possibleRemotePropertiesURL, String buildPropertyToMergeWith, String thisBuildId) {
    var remoteProperties = new Properties();
    if (Utils.Properties.load(possibleRemotePropertiesURL, remoteProperties)) {
      getLog().info("Loaded existing properties from " + possibleRemotePropertiesURL);
      var remoteBuildIds = remoteProperties.getProperty(buildPropertyToMergeWith);
      if (remoteBuildIds != null) {
        var remoteBuildIdsList = Arrays.asList(remoteBuildIds.split(","));
        if (!remoteBuildIdsList.contains(thisBuildId)) {
          remoteBuildIdsList.add(0, thisBuildId);
          return String.join(",", remoteBuildIdsList);
        }
      }
    }
    return thisBuildId;
  }

  private static BuildImpl getBuild(Path releaseFile) {
    var build = new BuildImpl(releaseFile.toFile());
    build.setLocalWorkspace(releaseFile.getParent().toFile());
    return build;
  }
}
