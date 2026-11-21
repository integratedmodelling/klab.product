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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.dependency.fromDependencies.CopyDependenciesMojo;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.integratedmodelling.klab.api.data.Version;
import org.integratedmodelling.klab.api.engine.distribution.Distribution;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Properties;

@Mojo(
    name = "package-release",
    requiresDependencyResolution = ResolutionScope.COMPILE,
    defaultPhase = LifecyclePhase.INSTALL,
    threadSafe = true)
public class ReleasePackageMojo extends CopyDependenciesMojo {

  @Parameter(required = true)
  private String productName;

  /** A build number created from the date of build will be added to the version. */
  @Parameter(defaultValue = Version.CURRENT, required = false)
  private String productVersion;

  @Parameter(required = false)
  private String productDescription;

  @Parameter(required = false)
  private String javaOptions;

  @Parameter(required = true)
  private Distribution.Product.Type productClass;

  /** Comma-separated project-relative directories shipped as product files outside its classpath. */
  @Parameter(required = false)
  private String additionalDirectories;

  /**
   * If this is present in a Java JAR-based product, it must be the main class. Otherwise a script
   * or executable is identified by its relative path in the distribution. Only
   */
  @Parameter(required = true)
  private String productMain;

  /** One of "jar", "exe", "script", "installer" */
  @Parameter(defaultValue = "jar", required = false)
  private String productType;

  @Parameter(required = false)
  private boolean productOSSpecific;

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getProductVersion() {
    return productVersion;
  }

  public void setProductVersion(String productVersion) {
    this.productVersion = productVersion;
  }

  public String getProductDescription() {
    return productDescription;
  }

  public void setProductDescription(String productDescription) {
    this.productDescription = productDescription;
  }

  public String getProductMain() {
    return productMain;
  }

  public void setProductMain(String productMain) {
    this.productMain = productMain;
  }

  public String getProductType() {
    return productType;
  }

  public void setProductType(String productType) {
    this.productType = productType;
  }

  public boolean isProductOSSpecific() {
    return productOSSpecific;
  }

  public void setProductOSSpecific(boolean productOSSpecific) {
    this.productOSSpecific = productOSSpecific;
  }

  @Override
  public void doExecute() throws MojoExecutionException {

    Artifact mainArtifact = getProject().getArtifact();

    File destDir =
        DependencyUtil.getFormattedOutputDirectory(
            useSubDirectoryPerScope,
            useSubDirectoryPerType,
            useSubDirectoryPerArtifact,
            useRepositoryLayout,
            stripVersion,
            stripType,
            outputDirectory,
            mainArtifact);

    File propertyFile = new File(destDir + File.separator + Distribution.BUILD_PROPERTIES_FILE);
    File digestFile = new File(destDir + File.separator + Distribution.BUILD_DIGEST_FILE);

    /** Create all deps in configured directory */
    super.doExecute();

    try {

      /*
         clean up any previous digest so it doesn't get indexed. We should run clean
         anyway.
      */
      Files.deleteIfExists(digestFile.toPath());

      /** Add the main JAR from the target directory */
      Files.copy(
          mainArtifact.getFile().toPath(),
          destDir.toPath().resolve(mainArtifact.getFile().toPath().getFileName()),
          StandardCopyOption.REPLACE_EXISTING);

      /** Create the MD5 digest */
      Digest.createMD5Digest(destDir, Distribution.BUILD_DIGEST_FILE);

      /** Create the product definition file */
      Properties properties = new Properties();

      OffsetDateTime now = OffsetDateTime.now();
      String timestamp =
          now.getYear()
              + String.format("%02d", now.getMonthValue())
              + String.format("%02d", now.getDayOfMonth())
              + String.format("%02d", now.getHour())
              + String.format("%02d", now.getMinute());

      properties.setProperty(Distribution.PRODUCT_NAME_PROPERTY, productName);
      properties.setProperty(
          Distribution.PRODUCT_DESCRIPTION_PROPERTY,
          productDescription == null ? "No " + "description given" : productDescription);
      properties.setProperty(
          Distribution.PRODUCT_OSSPECIFIC_PROPERTY, productOSSpecific ? "true" : "false");
      properties.setProperty(Distribution.PRODUCT_PLATFORM_PROPERTY, productType);
      properties.setProperty(Distribution.PRODUCT_TYPE_PROPERTY, productClass.name());
      properties.setProperty(Distribution.PRODUCT_MAINCLASS_PROPERTY, productMain);
      properties.setProperty(Distribution.BUILD_TIME_PROPERTY, System.currentTimeMillis() + "");
      properties.setProperty(Distribution.BUILD_VERSION_PROPERTY, productVersion + "." + timestamp);
      if (javaOptions != null) {
        properties.setProperty(Distribution.PRODUCT_JAVA_OPTIONS_PROPERTY, javaOptions);
      }
      if (additionalDirectories != null) {
        properties.setProperty(
            Distribution.PRODUCT_ADDITIONAL_DIRECTORIES_PROPERTY, additionalDirectories);
      }

      try (OutputStream out = new FileOutputStream(propertyFile)) {
        properties.store(out, "Created by klab.product Maven plugin on " + Instant.now());
      } catch (IOException e) {
        throw new MojoExecutionException(e.getMessage());
      }

      getLog().info("Product distribution for " + productName + " generated in " + destDir);

    } catch (Throwable throwable) {
      throw new MojoExecutionException(throwable.getMessage());
    }
  }
}
