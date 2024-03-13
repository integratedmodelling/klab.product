# k.LAB product management plugin

This package is a simple Maven plugin that automates the building and testing of k.LAB products,
documentation and other derived artifacts when compiling the k.LAB main stack or components.

Currently, the only implemented feature is the generation of k.LAB product releases with the correspondent
digest and properties needed for distribution. It is used internally in the k.LAB deployment
process. Later it will also produce AsciiDoc documentation files from components, functions and adapters configured
in the code, and it will expose goals that execute and report on k.LAB test suites implemented in k.Actors.

The plugin extends the dependency:copy-dependencies mojo of the [Apache Maven dependencies plugin](https://github.com/apache/maven-dependency-plugin)  
and accepts all the goals and configuration options of the latter. In addition, the following goals are supported:

| Name                | Description                                                                                                     |
|---------------------|-----------------------------------------------------------------------------------------------------------------|
| package-release     | Creates a release directory with all dependencies, a release.properties file and a file digest                  |
| update-distribution | Create the distribution.properties and product.properties files for all products found in the compiled targets. |


## package-product

The plugin is available on Maven central. It is configured as follows:

```xml
    <build>
        <plugins>
            <plugin>
                <groupId>org.integratedmodelling</groupId>
                <artifactId>klab.product</artifactId>
                <version>1.0-SNAPSHOT</version>
                <executions>
                    <execution>
                        <goals>
                            <goal>package-release</goal>
                        </goals>
                    </execution>
                </executions>
                <configuration>
                    <outputDirectory>target/release</outputDirectory>
                    <productName>klab.server.resources</productName>
                    <productMain>org.integratedmodelling.resources.server.ResourcesServer</productMain>
                    <productDescription>The reference implementation of the k.LAB Resources server</productDescription>
                    <productClass>RESOURCES_SERVICE</productClass>
                </configuration>
            </plugin>
        </plugins>
    </build>
```

The configuration parameters for the `package-release` goal are:

| Name               | Required                    | Values                                                                                                                                                    | Description                                                                                                                                          |
|--------------------|-----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| outputDirectory    | no (default `dependencies`) | valid subdirectory string, e.g. `target/product`                                                                                                          | The subdirectory of the project directory where the product will be created                                                                          |
| productName        | yes                         | any string                                                                                                                                                | The name of the k.LAB product                                                                                                                        |
| productMain        | yes                         | a Java class or file name                                                                                                                                 | The name of the Java class containing the main() method for JAR products, or the executable script or file name for executable or installer products |
| productDescription | no                          | any description                                                                                                                                           | The description of the product                                                                                                                       |
| productVersion     | no                          | A semantic version number                                                                                                                                 | The product version. A timestamp will be appended to it as a build number.                                                                           |
| productType        | no (default `jar`)          | One of `jar`, `exe`, `script` `installer`                                                                                                                 | The type of product (`jar` supported currently)                                                                                                      |
| productClass       | yes                         | A value of the [Product.ProductType](https://docs.integratedmodelling.org/javadocs/org/integratedmodelling/klab/api/engine/Product.ProductType.html) enum | The class of the generated product                                                                                                                   |
| productOSSpecific  | no (default false)          | `true` or `false`                                                                                                                                         | Whether the product needs OS-specific installation                                                                                                   |

After successful execution, the output directory will contain the release files with the file digest and properties that enable 
construction of a product with incremental download for k.LAB installable products.

## update-distribution

Create the distribution.properties and product.properties files for all products found in the compiled 
targets. If a distributionUrl parameter is passed, use the correspondent files as templates and update
them, adding any new release and new product found. In that case, the maxReleasesPerProduct parameter
controls the number of releases kept around.

Default release structure is in target/distribution of the main project and contains the distribution.properties
file with the list of products, each corresponding to a subdirectory with the product's name and the same structure
for all the releases. Each release contains one of more versions that are placed in a subdirectory build from the current date (precise to the minute). The Maven plugin builds one release only, but synchronizes with the contents of anything
already on the web at the remoteDistributionUrl parameter (if passed), so that the contents can be uploaded
as a unit without changes. The local path to the release files is also filled in to allow local use right out of the box.

The goal should be used in a submodule that depends on all the modules that produce builds using `package-product`. After it ran, the builds will be moved to the configured subdirectory of the `update-distribution` goal (by default `target/distribution`). Because the builds will be tagged with the current date, it is important to run `clean` before, unless the older releases need to be kept. Not doing so will create serious storage occupation.

