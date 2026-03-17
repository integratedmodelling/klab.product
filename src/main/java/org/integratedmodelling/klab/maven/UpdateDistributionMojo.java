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
import org.integratedmodelling.klab.api.engine.distribution.impl.BuildImpl;
import org.integratedmodelling.klab.api.engine.distribution.impl.LocalBuildImpl;
import org.integratedmodelling.klab.api.utils.PropertyBean;
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

  /**
   * If true, download the remote builds to create a self-consistent distribution. Only works in
   * combination with remoteDistributionUrl. May cause long downloads and large file sizes.
   */
  @Parameter(required = false, defaultValue = "false")
  private boolean synchronizeRemoteBuilds;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    Properties remoteProperties = null;
    if (distributionRootUrl != null) {
      // TODO pre-populate the property files and define the local product paths
      var properties =
          Utils.URLs.newURL(
              distributionRootUrl + "/" + distributionName + "/distribution.properties");
      var existingProperties = new Properties();
      if (Utils.Properties.load(properties, existingProperties)) {
        getLog().info("Loaded existing distribution properties from " + properties);
        remoteProperties = existingProperties;
      }
    }

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
          walker.filter(path -> path.toString().endsWith(Build.BUILD_PROPERTIES_FILE)).toList()) {
        getLog().info("Detected build file " + releaseFile);
        var build = getBuild(releaseFile);
        products.add(build.getProperty(Product.PRODUCT_NAME_PROPERTY));
        builds.add(Pair.of(releaseFile.toFile(), build));
      }

      // Filter builds to keep only the most recent build per product, in case the build wasn't
      // cleaned before.
      Map<String, Pair<File, BuildImpl>> latestBuilds = new HashMap<>();
      for (var buildPair : builds) {
        String productName = buildPair.getSecond().getProperty(Product.PRODUCT_NAME_PROPERTY);
        String buildId = buildPair.getSecond().getProperty(Build.BUILD_VERSION_PROPERTY);

        if (!latestBuilds.containsKey(productName)) {
          latestBuilds.put(productName, buildPair);
        } else {
          String existingBuildId =
              latestBuilds.get(productName).getSecond().getProperty(Build.BUILD_VERSION_PROPERTY);
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

      // build the directory structure to host the products:
      // distribution/version/release/build/product. At each
      // step, merge with any existing properties so that when the distribution is uploaded,
      // sub-directories from previous versions are preserved up to
      // the numbers configured.

      /**
       * Structure:
       *
       * <p>-distribution/ distribution.properties (name, description, copyright data, email,
       * versions) -version/ version.properties (name, releases) -release/ release.properties (name,
       * builds) -build/ build.properties (name, date, products) -product/ product.properties (name,
       * class, type, rundata, description, constraints) filelist.txt ....files
       */
      File distributionDirectory =
          new File(project.getBasedir() + File.separator + outputDirectory);
      distributionDirectory.mkdirs();
      var distributionUrl = Utils.URLs.newURL(distributionRootUrl + "/" + distributionName);

      var distributionProperties =
          Utils.Properties.create(
              DistributionGiusta.DISTRIBUTION_NAME_PROPERTY,
              distributionName,
              DistributionGiusta.DISTRIBUTION_DATE_PROPERTY,
              Instant.now().toString(),
              DistributionGiusta.DISTRIBUTION_VERSIONS_PROPERTY,
              distributionVersion);

      if (distributionRootUrl != null) {
        distributionProperties.setProperty(
            DistributionGiusta.DISTRIBUTION_VERSIONS_PROPERTY,
            synchronize(
                Utils.URLs.newURL(
                    distributionUrl + "/" + DistributionGiusta.DISTRIBUTION_PROPERTIES_FILE),
                DistributionGiusta.DISTRIBUTION_VERSIONS_PROPERTY,
                distributionVersion));
      }

      distributionProperties.save(
          new File(distributionDirectory, DistributionGiusta.DISTRIBUTION_PROPERTIES_FILE));

      var versionDirectory = new File(distributionDirectory + File.separator + distributionVersion);
      var versionUrl = new File(distributionUrl + "/" + distributionVersion);
      versionDirectory.mkdirs();
      var versionProperties =
          Utils.Properties.create(
              DistributionGiusta.VERSION_NAME_PROPERTY,
              distributionVersion,
              DistributionGiusta.VERSION_RELEASES_PROPERTY,
              releaseName);
      if (distributionRootUrl != null) {
        versionProperties.setProperty(
            DistributionGiusta.VERSION_RELEASES_PROPERTY,
            synchronize(
                Utils.URLs.newURL(versionUrl + "/" + DistributionGiusta.VERSION_PROPERTIES_FILE),
                DistributionGiusta.VERSION_RELEASES_PROPERTY,
                releaseName));
      }
      versionProperties.save(
          new File(versionDirectory, DistributionGiusta.VERSION_PROPERTIES_FILE));

      var releaseDirectory = new File(distributionDirectory + File.separator + releaseName);
      var releaseUrl = new File(versionUrl + "/" + distributionVersion);
      releaseDirectory.mkdirs();
      var releaseProperties =
          Utils.Properties.create(
              DistributionGiusta.RELEASE_NAME_PROPERTY,
              releaseName,
              DistributionGiusta.RELEASE_BUILDS_PROPERTY,
              buildTimestamp);
      if (distributionRootUrl != null) {
        releaseProperties.setProperty(
            DistributionGiusta.RELEASE_BUILDS_PROPERTY,
            synchronize(
                Utils.URLs.newURL(releaseUrl + "/" + DistributionGiusta.RELEASE_PROPERTIES_FILE),
                DistributionGiusta.RELEASE_BUILDS_PROPERTY,
                buildTimestamp));
      }
      releaseProperties.save(
          new File(versionDirectory, DistributionGiusta.VERSION_PROPERTIES_FILE));

      var buildDirectory = new File(releaseDirectory + File.separator + releaseName);
      buildDirectory.mkdirs();
      // no sync for builds as their contents are immutable once uploaded
      var buildProperties =
          Utils.Properties.create(
              DistributionGiusta.BUILD_NAME_PROPERTY,
              buildTimestamp,
              DistributionGiusta.BUILD_PRODUCTS_PROPERTY,
              String.join(",", products));
      buildProperties.save(new File(buildDirectory, DistributionGiusta.BUILD_PROPERTIES_FILE));

      StringBuilder productNames = new StringBuilder();

      for (var productName : products) {

        productNames.append((productNames.length() == 0) ? "" : ",").append(productName);

        var productDirectory = new File(distributionDirectory + File.separator + productName);
        productDirectory.mkdirs();

        var productProperties =
            new PropertyBean(
                new File(productDirectory + File.separator + Product.PRODUCT_PROPERTIES_FILE));
        // TODO merge with existing ones
        var releaseDirectory = new File(productDirectory + File.separator + releaseName);
        releaseDirectory.mkdirs();

        var releaseProperties =
            new PropertyBean(
                new File(releaseDirectory + File.separator + Release.RELEASE_PROPERTIES_FILE));
        releaseProperties.setProperty(Release.RELEASE_NAME_PROPERTY, releaseName);
        StringBuilder buildNames = new StringBuilder();

        Product.ProductType productClass = null;
        Product.Type productType = null;
        String productDescription = null;

        for (var build : builds) {

          // TODO if more than one build for a project, take the most recent only

          if (build.getSecond().getProperty(Product.PRODUCT_NAME_PROPERTY).equals(productName)) {

            var buildProperties = new PropertyBean(build.getFirst());

            productClass =
                Product.ProductType.valueOf(
                    build.getSecond().getProperty(Product.PRODUCT_CLASS_PROPERTY));
            productType =
                Product.Type.forOption(
                    build.getSecond().getProperty(Product.PRODUCT_TYPE_PROPERTY));
            productDescription =
                build.getSecond().getProperty(Product.PRODUCT_DESCRIPTION_PROPERTY);

            var buildName =
                Utils.Paths.getLast(buildProperties.getProperty(Build.BUILD_VERSION_PROPERTY), '.');
            File destination = new File(releaseDirectory + File.separator + buildTimestamp);
            destination.mkdirs();
            getLog()
                .info(
                    "Moving "
                        + releaseName
                        + " build "
                        + buildName
                        + " of product "
                        + productName
                        + " from "
                        + build.getFirst().toPath().getParent()
                        + " to "
                        + destination);
            Files.move(
                build.getFirst().toPath().getParent(), destination.toPath(), REPLACE_EXISTING);
            buildProperties.setProperty(
                Build.BUILD_WORKSPACE_PROPERTY, destination.toPath().normalize().toString());
            buildProperties.setProperty(
                Build.BUILD_WORKSPACE_PROPERTY, destination.toPath().normalize().toString());
            buildProperties.saveProperties(
                new File(destination + File.separator + Build.BUILD_PROPERTIES_FILE));
            buildNames.append((buildNames.isEmpty()) ? "" : ",").append(buildTimestamp);
          }
        }

        releaseProperties.setProperty(Release.BUILD_VERSIONS_PROPERTY, buildNames.toString());
        releaseProperties.saveProperties();

        // TODO merge with existing
        productProperties.setProperty(
            Product.PRODUCT_DESCRIPTION_PROPERTY, productDescription, "No" + " description given");
        productProperties.setProperty(Product.PRODUCT_CLASS_PROPERTY, productClass.name());
        productProperties.setProperty(Product.PRODUCT_TYPE_PROPERTY, productType.userOption);
        productProperties.setProperty(Product.RELEASE_NAMES_PROPERTY, releaseName);
        productProperties.saveProperties();
      }

      //      var distribution = new PropertyBean(distributionPropertiesFile);
      //      distribution.setProperty(Distribution.DISTRIBUTION_NAME_PROPERTY, distributionName);
      //      distribution.setProperty(Distribution.DISTRIBUTION_DATE_PROPERTY,
      // Instant.now().toString());
      //      distribution.setProperty(
      //          Distribution.DISTRIBUTION_PRODUCTS_PROPERTY, productNames.toString());
      //      distribution.setProperty(Distribution.DISTRIBUTION_VERSION_PROPERTY,
      // distributionVersion);
      //      distribution.saveProperties();

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

  // REMOVE (?)
  private void saveProperties(
      Properties propertiesAsDefined,
      URL possibleRemotePropertiesURL,
      String buildPropertyToMergeWith,
      String thisBuildId,
      File outputFile) {
    var remoteProperties = new Properties();
    if (Utils.Properties.load(possibleRemotePropertiesURL, remoteProperties)) {
      getLog().info("Loaded existing properties from " + possibleRemotePropertiesURL);
      var remoteBuildIds = remoteProperties.getProperty(buildPropertyToMergeWith);
      if (remoteBuildIds != null) {
        var remoteBuildIdsList = Arrays.asList(remoteBuildIds.split(","));
        if (!remoteBuildIdsList.contains(thisBuildId)) {
          remoteBuildIdsList.add(0, thisBuildId);
          propertiesAsDefined.setProperty(
              buildPropertyToMergeWith, String.join(",", remoteBuildIdsList));
        }
      }
    }

    Utils.Properties.save(outputFile, propertiesAsDefined);
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
    var build = new LocalBuildImpl(releaseFile.toFile(), null, null, null);
    build.setLocalWorkspace(releaseFile.getParent().toFile());
    return build;
  }
}
