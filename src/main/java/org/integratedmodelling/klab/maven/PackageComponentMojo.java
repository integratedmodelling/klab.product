package org.integratedmodelling.klab.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import java.util.Map;
import java.util.jar.JarOutputStream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.plugins.shade.DefaultShader;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.integratedmodelling.klab.api.data.Version;

/**
 * Create a k.LAB component plug-in with the same name as the original Jar but with a -klab
 * classifier appended. The plug-in jar is added to the artifact with <code>kar</code> type, so that
 * it's deployed in Maven as <code><type>kar</type></code>and can be retrieved along with the normal
 * jar.
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
  @Component private ArtifactHandlerManager artifactHandlerManager;

  @Parameter(required = true)
  private String componentName;

  @Parameter(required = false)
  private String componentVersion;

  @Parameter(required = false)
  private String klabVersion;

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
      defaultValue =
          "${project.build.directory}/${project.artifactId}-component-${project.version}.jar")
  private String outputJar;

  @Parameter(required = false)
  private Map<String, Object> manifestEntries = new HashMap<>();

  @Parameter(required = false)
  private String excludedDependencies;

  /**
   * Comma-separated Maven coordinates for the host runtime artifact(s) whose dependency closure is
   * provided to the component at runtime. Supported forms:
   * groupId:artifactId:version
   * groupId:artifactId:type:version
   * groupId:artifactId:type:classifier:version
   */
  @Parameter(property = "klab.hostRuntimeArtifacts", required = true)
  private String hostRuntimeArtifacts;

  private Set<String> getProjectDependencies() {
    Set<String> dependencies = new HashSet<>();

    project.getDependencies().forEach(dependency -> {
      if (!isRuntimeRelevantScope(dependency.getScope())) {
        getLog().debug(
                "Skipping non-runtime dependency "
                        + dependency.getGroupId()
                        + ":"
                        + dependency.getArtifactId()
                        + " with scope "
                        + dependency.getScope());
        return;
      }

      if (dependency.isOptional()) {
        getLog().debug(
                "Skipping optional dependency "
                        + dependency.getGroupId()
                        + ":"
                        + dependency.getArtifactId());
        return;
      }

      String type = dependency.getType() == null ? "jar" : dependency.getType();
      if (!"jar".equals(type)) {
        getLog().debug(
                "Skipping non-jar dependency "
                        + dependency.getGroupId()
                        + ":"
                        + dependency.getArtifactId()
                        + " with type "
                        + type);
        return;
      }

      dependencies.add(
              dependency.getGroupId()
                      + ":"
                      + dependency.getArtifactId()
                      + ":"
                      + dependency.getVersion());
    });

    return dependencies;
  }

  private boolean isRuntimeRelevantScope(String scope) {
    return scope == null || "compile".equals(scope) || "runtime".equals(scope);
  }

  private Set<String> getExcludedDependencies() {
    if (excludedDependencies == null || excludedDependencies.isEmpty()) {
      return Set.of();
    }
    return Set.of(excludedDependencies.split(","));
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    try {

      var projectDependencies = getProjectDependencies();
      var excludedDependencies = getExcludedDependencies();

      Map<String, Set<Artifact>> resolvedArtifacts = new HashMap<>();

      Set<String> excludedByGroup = new HashSet<>();
      Set<String> excludedByArtifact = new HashSet<>();
      for (String dependency : excludedDependencies) {
        String[] parts = dependency.trim().split(":");

        if (parts.length < 2) {
          throw new MojoExecutionException(
                  "Invalid excluded dependency '"
                          + dependency
                          + "'. Expected groupId:* or groupId:artifactId.");
        }

        if ("*".equals(parts[1])) {
          excludedByGroup.add(parts[0]);
        } else {
          excludedByArtifact.add(parts[0] + ":" + parts[1]);
        }
      }

      getLog().debug("Size of the excluded groups: " + excludedByGroup.size());
      getLog().debug("Size of the excluded artifacts: " + excludedByArtifact.size());

      // fetch non-transitive dependencies;
      for (String dependency : projectDependencies) {

        String[] parts = dependency.split(":");

        if (excludedByGroup.stream().anyMatch(art -> art.equals(parts[0]))) {
          // exclude
          getLog().warn("Excluded by group " + parts[0]);
          continue;
        }
        if (excludedByArtifact.stream().anyMatch(art -> art.equals(parts[0] + ":" + parts[1]))) {
          // exclude
          getLog().warn("Excluded by artifact " + parts[0] + ":" + parts[1]);
          continue;
        } else {
          getLog().debug("Accepted artifact " + parts[0] + ":" + parts[1]);
        }

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

      
      Set<Artifact> candidateArtifacts = new HashSet<>();
      Set<Artifact> artifactsIncluded = new HashSet<>();
      Set<Artifact> artifactsExcluded = new HashSet<>();

      /*
       * The runtime application provides k.LAB core dependencies and their transitive closure.
       *
       * Exclusion must be based on groupId:artifactId, not exact groupId:artifactId:version.
       * If the component requests the same GA at a different version, we still exclude it and warn,
       * because packaging a second version in the component can create duplicate classes at runtime.
       */
      Map<ArtifactKey, Set<String>> hostProvidedVersions = resolveHostProvidedVersions();

      for (String dependency : projectDependencies) {
        var deps = resolvedArtifacts.get(dependency);
        if (deps == null) {
          continue;
        }

        /*
         * Any dependency reachable through a k.LAB core dependency is considered provided by the
         * main application runtime.
         */
        if (isCoreProject(dependency)) {
          for (var dep : deps) {
            if (!isRuntimeRelevant(dep)) {
              continue;
            }

            ArtifactKey key = ArtifactKey.of(dep);
            hostProvidedVersions
                    .computeIfAbsent(key, ignored -> new TreeSet<>())
                    .add(dep.getVersion());

            artifactsExcluded.add(dep);
          }
        }

        /*
         * Build the component candidate set, applying explicit manual exclusions first.
         */
        for (var dep : deps) {
          if (!isRuntimeRelevant(dep)) {
            continue;
          }

          if (excludedByGroup.stream().anyMatch(art -> art.equals(dep.getGroupId()))) {
            getLog().warn("Excluded by group " + dep.getGroupId());
            artifactsExcluded.add(dep);
            continue;
          }

          if (excludedByArtifact.stream()
                  .anyMatch(art -> art.equals(dep.getGroupId() + ":" + dep.getArtifactId()))) {
            getLog().warn("Excluded by artifact " + dep.getGroupId() + ":" + dep.getArtifactId());
            artifactsExcluded.add(dep);
            continue;
          }

          candidateArtifacts.add(dep);
        }
      }

      /*
       * Final host-runtime exclusion pass.
       *
       * This is the important part:
       * - same GA, same version      -> exclude quietly/debug
       * - same GA, different version -> exclude and warn
       */
      for (var artifact : candidateArtifacts) {
        ArtifactKey key = ArtifactKey.of(artifact);
        Set<String> providedVersions = hostProvidedVersions.get(key);

        if (providedVersions != null) {
          artifactsExcluded.add(artifact);

          if (!providedVersions.contains(artifact.getVersion())) {
            getLog()
                    .warn(
                            "Excluded host-provided artifact "
                                    + key
                                    + ":"
                                    + artifact.getVersion()
                                    + "; host runtime provides version(s) "
                                    + providedVersions);
          } else {
            getLog()
                    .debug(
                            "Excluded host-provided artifact "
                                    + key
                                    + ":"
                                    + artifact.getVersion());
          }

          continue;
        }

        artifactsIncluded.add(artifact);
      }

      // last pass, only keep the same versions of the same artifacts if it's there in multiple
      // versions, and give a warning
      LinkedHashMap<String, Artifact> artifactVersions = new LinkedHashMap<>();
      for (var artifact : artifactsIncluded) {
        var existing = artifactVersions.get(artifact.getGroupId() + ":" + artifact.getArtifactId());
        if (existing == null) {
          artifactVersions.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact);
        } else if (!existing.getVersion().equals(artifact.getVersion())) {
          DefaultArtifactVersion existingVersion =
              new DefaultArtifactVersion(existing.getVersion());
          DefaultArtifactVersion newVersion = new DefaultArtifactVersion(artifact.getVersion());

          if (existingVersion.compareTo(newVersion) < 0) {
            artifactVersions.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact);
            getLog()
                .warn(
                    "Multiple versions of "
                        + artifact.getGroupId()
                        + ":"
                        + artifact.getArtifactId()
                        + " found in the remaining component dependencies. Using "
                        + artifact.getVersion()
                        + " instead of "
                        + existing.getVersion());
          } else if (existingVersion.compareTo(newVersion) > 0) {
            getLog()
                .warn(
                    "Multiple versions of "
                        + artifact.getGroupId()
                        + ":"
                        + artifact.getArtifactId()
                        + " found in the remaining component dependencies. Using "
                        + existing.getVersion()
                        + " instead of "
                        + artifact.getVersion());
          }
        }
      }

      artifactsIncluded.clear();
      artifactsIncluded.addAll(artifactVersions.values());

      for (Artifact artifact : artifactsIncluded) {
        getLog()
            .info(
                "Artifact "
                    + artifact.getGroupId()
                    + ":"
                    + artifact.getArtifactId()
                    + ":"
                    + artifact.getVersion()
                    + " included in k.LAB component");
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
      manifestEntries.put(
          "Plugin-Klab-Version",
          project.getParent() != null && project.getParent().getVersion() != null
              ? project.getParent().getVersion()
              : Version.EMPTY_VERSION);
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
      try {
        createOutputJar(artifactsIncluded);

        // Attach the created JAR as an additional artifact
        File jarFile = new File(outputJar);
        if (jarFile.exists()) {
          /*
          Create the component artifact using RepositorySystem. Archive will have -component classifier and
          kar as a type.Cannot retrieve any sub-artifact without a classifier, apparently. This
          should enable the deployment of the .kar to remote repositories. The .md5 hash is made for the
          jar, but still serves as a checksum for the component as long as it's not recomputed directly on
          it.
          */
          Artifact klabArtifact =
              repositorySystem.createArtifactWithClassifier(
                  project.getGroupId(),
                  project.getArtifactId(),
                  project.getVersion(),
                  "kar",
                  "component");

          // Set the file for the created artifact
          klabArtifact.setFile(jarFile);

          // Attach it to the project
          project.addAttachedArtifact(klabArtifact);

          getLog()
              .info(
                  "------------------------------------------------------------------------------------");
          getLog()
              .info(
                  "Created K.LAB component KAR file as additional artifact with classifier 'component'");
          getLog()
              .info(
                  "------------------------------------------------------------------------------------");
        }
      } catch (Exception e) {
        throw new MojoExecutionException("Failed to create output jar", e);
      }

    } catch (Exception e) {
      throw new MojoExecutionException("Failed to process dependencies", e);
    }
  }

  private Map<ArtifactKey, Set<String>> resolveHostProvidedVersions()
          throws MojoExecutionException {

    if (hostRuntimeArtifacts == null || hostRuntimeArtifacts.isBlank()) {
      throw new MojoExecutionException(
              "klab.hostRuntimeArtifacts must be configured to identify the actual "
                      + "application runtime that will load this component.");
    }

    Map<ArtifactKey, Set<String>> hostProvidedVersions = new HashMap<>();

    for (String coordinate : hostRuntimeArtifacts.split(",")) {
      coordinate = coordinate.trim();
      if (coordinate.isEmpty()) {
        continue;
      }

      Artifact hostArtifact = createArtifactFromCoordinate(coordinate);

      ArtifactResolutionRequest request =
              new ArtifactResolutionRequest()
                      .setArtifact(hostArtifact)
                      .setResolveRoot(true)
                      .setResolveTransitively(true)
                      .setLocalRepository(mavenSession.getLocalRepository())
                      .setRemoteRepositories(project.getRemoteArtifactRepositories());

      ArtifactResolutionResult result = repositorySystem.resolve(request);

      if (result.hasExceptions()) {
        throw new MojoExecutionException(
                "Could not resolve host runtime dependency closure for " + coordinate,
                result.getExceptions().get(0));
      }

      for (Artifact artifact : result.getArtifacts()) {
        if (!isRuntimeRelevant(artifact)) {
          continue;
        }

        ArtifactKey key = ArtifactKey.of(artifact);
        hostProvidedVersions
                .computeIfAbsent(key, ignored -> new TreeSet<>())
                .add(artifact.getVersion());
      }
    }

    getLog().info(
            "Resolved "
                    + hostProvidedVersions.size()
                    + " host-provided runtime artifact coordinates.");

    return hostProvidedVersions;
  }

  private Artifact createArtifactFromCoordinate(String coordinate)
          throws MojoExecutionException {

    String[] parts = coordinate.split(":");

    return switch (parts.length) {
      case 3 ->
              repositorySystem.createArtifact(
                      parts[0], parts[1], parts[2], "jar");

      case 4 ->
              repositorySystem.createArtifact(
                      parts[0], parts[1], parts[3], parts[2]);

      case 5 ->
              repositorySystem.createArtifactWithClassifier(
                      parts[0], parts[1], parts[4], parts[2], parts[3]);

      default ->
              throw new MojoExecutionException(
                      "Invalid host runtime artifact coordinate '"
                              + coordinate
                              + "'. Expected groupId:artifactId:version, "
                              + "groupId:artifactId:type:version, or "
                              + "groupId:artifactId:type:classifier:version.");
    };
  }

  private boolean isCoreProject(String dependency) {
    return dependency.startsWith("org.integratedmodelling:klab");
  }

  private boolean isRuntimeRelevant(Artifact artifact) {
    if (artifact == null) {
      return false;
    }

    if (!isRuntimeRelevantScope(artifact.getScope())) {
      return false;
    }

    if (artifact.isOptional()) {
      return false;
    }

    return "jar".equals(artifact.getType());
  }

  private record ArtifactKey(String groupId, String artifactId) {

    static ArtifactKey of(Artifact artifact) {
      return new ArtifactKey(artifact.getGroupId(), artifact.getArtifactId());
    }

    @Override
    public String toString() {
      return groupId + ":" + artifactId;
    }
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
      if (artifact.getFile() != null
          && artifact.getFile().exists()
          && artifact.getType().equals("jar")) {
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

    // Add resource transformer that filters out plugin.properties, which may come from shaded
    // artifacts
    ResourceTransformer filterTransformer =
        new ResourceTransformer() {
          @Override
          public boolean canTransformResource(String resource) {
            return "plugin.properties".equals(resource);
          }

          @Override
          public void processResource(String resource, InputStream is, List<Relocator> relocators)
              throws IOException {
            // Skip the resource
          }

          @Override
          public boolean hasTransformedResource() {
            return false;
          }

          @Override
          public void modifyOutputStream(JarOutputStream os) throws IOException {}
        };

    // Remove all signature files with yet another filter transformer, or the plugin will never be
    // 1oaded
    ResourceTransformer signatureFilterTransformer =
        new ResourceTransformer() {
          @Override
          public boolean canTransformResource(String resource) {
            return resource.endsWith(".SF")
                || resource.endsWith(".RSA")
                || resource.endsWith(".DSA")
                || resource.contains("SIG-");
          }

          @Override
          public void processResource(String resource, InputStream is, List<Relocator> relocators) {
            // Skip the resource
          }

          @Override
          public boolean hasTransformedResource() {
            return false;
          }

          @Override
          public void modifyOutputStream(JarOutputStream os) {}
        };

    // Add all transformers to the shade request
    shadeRequest.setResourceTransformers(
        Arrays.asList(manifestTransformer, filterTransformer, signatureFilterTransformer));

    // Shade the jar
    shader.shade(shadeRequest);
  }
}
