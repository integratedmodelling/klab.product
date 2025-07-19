package org.integratedmodelling.klab.maven;

import java.io.File;
import java.time.Instant;
import java.util.*;
import java.util.Map;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.plugins.shade.DefaultShader;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.integratedmodelling.klab.api.engine.distribution.*;

/**
 * Create a k.LAB component plug-in with the same name as the original Jar but with a -klab
 * classifier appended. The plug-in jar is added to the artifact so that it's deployed in Maven.
 *
 * <p>The finished plug-in is an uber-jar with p4j plugin metadata, containing binary classes for
 * all dependencies in its closure except those already included by the core k.LAB distribution that
 * will be provided by the plug-in runtime. The logic to remove the components is primitive for now,
 * just checking for Maven coordinates starting with <code>org.integratedmodelling:klab</code>.
 * Configuration for these criteria using regexps or lists of prefixes is very easy to add later.
 */
@Mojo(
    name = "package-component",
    requiresDependencyResolution = ResolutionScope.COMPILE,
    defaultPhase = LifecyclePhase.PACKAGE,
    threadSafe = true)
public class PackageComponentMojo extends AbstractMojo {

  @Parameter(readonly = true, defaultValue = "${project}")
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true)
  private MavenSession mavenSession;

  @Component private RepositorySystem repositorySystem;

  @Parameter(required = true)
  private String componentName;

  @Parameter(required = false)
  private String componentVersion;

  @Parameter(required = true)
  private String componentDescription;

  @Parameter(required = false)
  private String componentVendorId;

  @Parameter(required = true)
  private String componentVendorName;

  @Parameter(required = false)
  private String componentVendorUrl;

  @Parameter(required = true)
  private String componentVendorEmail;

  @Parameter(required = true)
  private String componentLicenseName;

  @Parameter(required = false)
  private String componentLicenseUrl;

  @Parameter(required = true)
  private String componentUsageRights;

  @Parameter(required = true)
  private String componentClass;

  @Parameter(required = false, defaultValue = "target/distribution")
  private String outputDirectory;

  @Parameter(
      required = false,
      defaultValue = "${project.build.directory}/${project.artifactId}-klab-${project.version}.jar")
  private String outputJar;

  @Parameter(required = false)
  private Map<String, Object> manifestEntries = new HashMap<>();

  private Set<String> getProjectDependencies() {
    Set<String> dependencies = new HashSet<>();
    project
        .getDependencies()
        .forEach(
            dependency ->
                dependencies.add(
                    dependency.getGroupId()
                        + ":"
                        + dependency.getArtifactId()
                        + ":"
                        + dependency.getVersion()));
    return dependencies;
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {

      var projectDependencies = getProjectDependencies();

      Map<String, Set<Artifact>> resolvedArtifacts = new HashMap<>();

      // fetch non-transitive dependencies;
      for (String dependency : projectDependencies) {

        String[] parts = dependency.split(":");
        Artifact artifact = repositorySystem.createArtifact(parts[0], parts[1], parts[2], "jar");

        ArtifactResolutionRequest request =
            new ArtifactResolutionRequest()
                .setArtifact(artifact)
                .setResolveRoot(true)
                .setResolveTransitively(true)
                .setLocalRepository(mavenSession.getLocalRepository())
                .setRemoteRepositories(project.getRemoteArtifactRepositories());

        ArtifactResolutionResult result = repositorySystem.resolve(request);
        resolvedArtifacts.put(dependency, new HashSet<>(result.getArtifacts()));
      }

      Set<Artifact> artifactsIncluded = new HashSet<>();
      Set<Artifact> artifactsExcluded = new HashSet<>();

      for (String dependency : projectDependencies) {
        var deps = resolvedArtifacts.get(dependency);
        artifactsIncluded.addAll(deps);
        if (isCoreProject(dependency)) {
          artifactsExcluded.addAll(deps);
        }
      }

      // these are the artifacts to keep in the plug-in
      artifactsIncluded.removeAll(artifactsExcluded);

      for (Artifact artifact : artifactsIncluded) {
        getLog()
            .info(
                "Keep "
                    + artifact.getGroupId()
                    + ":"
                    + artifact.getArtifactId()
                    + ":"
                    + artifact.getVersion());
      }

      getLog()
          .info(
              "k.LAB component plug-in: "
                  + artifactsIncluded.size()
                  + " artifacts included,  "
                  + artifactsExcluded.size()
                  + " excluded");

      // default manifest entries
      manifestEntries.put("Implementation-Title", project.getName());
      manifestEntries.put("Implementation-Version", project.getVersion());
      manifestEntries.put("Implementation-Vendor-Id", project.getGroupId());
      manifestEntries.put("Built-By", System.getProperty("user.name"));
      manifestEntries.put("Built-Date", Instant.now().toString());

      // p4j specific entries
      manifestEntries.put(
          "Plugin-Id", componentName == null ? project.getArtifactId() : componentName);
      manifestEntries.put(
          "Plugin-Version", componentVersion == null ? project.getVersion() : componentVersion);
      manifestEntries.put("Plugin-Description", componentDescription);
      manifestEntries.put("Plugin-Provider", componentVendorName);
      manifestEntries.put("Plugin-License", componentUsageRights);
      manifestEntries.put("Plugin-Class", componentClass);

      // additional for k.LAB usage
      manifestEntries.put(
          "Plugin-Vendor-Id", componentVendorId == null ? project.getGroupId() : componentVendorId);
      manifestEntries.put(
          "Plugin-Vendor-Url", componentVendorUrl == null ? "No vendor URL" : componentVendorUrl);
      manifestEntries.put(
          "Plugin-Vendor-Email",
          componentVendorEmail == null ? "No vendor email" : componentVendorEmail);
      manifestEntries.put(
          "Plugin-License-Name",
          componentLicenseName == null ? "No license name" : componentLicenseName);
      manifestEntries.put(
          "Plugin-License-Url",
          componentLicenseUrl == null ? "No license URL" : componentLicenseUrl);

      // Create the output jar using Maven Shade Plugin
      getLog().info("Packaging component to: " + outputJar);
      try {
        createOutputJar(artifactsIncluded);
        getLog().info("Component packaged successfully to: " + outputJar);

        // Attach the created JAR as an additional artifact
        File jarFile = new File(outputJar);
        if (jarFile.exists()) {
          // Create the artifact using RepositorySystem
          Artifact klabArtifact =
              repositorySystem.createArtifactWithClassifier(
                  project.getGroupId(), project.getArtifactId(), project.getVersion(), "kar", null);

          // Set the file for the created artifact
          klabArtifact.setFile(jarFile);

          // Attach it to the project
          project.addAttachedArtifact(klabArtifact);

          getLog()
              .info("Attached K.LAB component JAR as additional artifact with classifier 'klab'");
        }
      } catch (Exception e) {
        throw new MojoExecutionException("Failed to create output jar", e);
      }

    } catch (Exception e) {
      throw new MojoExecutionException("Failed to process dependencies", e);
    }
  }

  private boolean isCoreProject(String dependency) {
    return dependency.startsWith("org.integratedmodelling:klab");
  }

  /**
   * Creates the output jar file containing the project classes and all included artifacts.
   *
   * @param artifactsIncluded the set of artifacts to include in the jar
   * @throws Exception if there's an error creating the jar
   */
  private void createOutputJar(Set<Artifact> artifactsIncluded) throws Exception {
    DefaultShader shader = new DefaultShader();
    ShadeRequest shadeRequest = new ShadeRequest();

    // Set up the project artifact
    File projectArtifact =
        new File(
            project.getBuild().getDirectory(),
            project.getBuild().getFinalName() + "." + project.getPackaging());
    Set<File> artifacts = new HashSet<>();
    artifacts.add(projectArtifact);

    // Add all included artifacts
    for (Artifact artifact : artifactsIncluded) {
      if (artifact.getFile() != null && artifact.getFile().exists()) {
        artifacts.add(artifact.getFile());
      } else {
        getLog().warn("Skipping artifact with null or non-existent file: " + artifact);
      }
    }

    // Configure the shade request
    shadeRequest.setJars(artifacts);
    shadeRequest.setUberJar(new File(outputJar));
    shadeRequest.setFilters(Collections.emptyList());
    shadeRequest.setRelocators(Collections.emptyList());

    // Configure the manifest transformer
    ManifestResourceTransformer manifestTransformer = new ManifestResourceTransformer();
    manifestTransformer.setManifestEntries(manifestEntries);

    // Add the transformer to the shade request
    shadeRequest.setResourceTransformers(Collections.singletonList(manifestTransformer));

    // Shade the jar
    shader.shade(shadeRequest);
  }
}
