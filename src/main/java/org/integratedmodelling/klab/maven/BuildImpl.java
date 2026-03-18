package org.integratedmodelling.klab.maven;//package org.integratedmodelling.klab.api.engine.distribution.impl;

import org.integratedmodelling.klab.api.data.Version;
import org.integratedmodelling.klab.api.engine.distribution.RunningInstance;
import org.integratedmodelling.klab.api.scope.Scope;
import org.integratedmodelling.klab.api.utils.PropertyBean;

import java.io.File;
import java.time.Instant;

public class BuildImpl extends PropertyBean {

//    private ProductImpl product;
//    private ReleaseImpl release;
    private File localWorkspace;
    private Instant buildDate;
    private Version version;
    private boolean osSpecific;
    private String executable;

    public BuildImpl() {
        super(null);
    }

    public BuildImpl(File propertiesFile) {
        super(propertiesFile);
        // TODO read the properties. This creates a stub so we create the product as well.
        readProperties();
//        this.product = new ProductImpl() {
//            @Override
//            public Status getStatus() {
//                return null;
//            }
//
//            @Override
//            public RunningInstance getInstance(Scope scope) {
//                return null;
//            }
//
//            @Override
//            public RunningInstance launch(Scope scope) {
//                return null;
//            }
//        };
//        this.product.setName(getProperty(Product.PRODUCT_NAME_PROPERTY));
    }

    private void readProperties() {
//        var localWs = getProperty(BUILD_WORKSPACE_PROPERTY);
//        if (localWs != null) {
//            setLocalWorkspace(new File(localWs));
//        }
//        setVersion(Version.create(getProperty(BUILD_VERSION_PROPERTY)));
//        setOsSpecific(Boolean.valueOf(getProperty(PRODUCT_OSSPECIFIC_PROPERTY)));
//        setExecutable(getProperty(BUILD_MAINCLASS_PROPERTY));
//        setBuildDate(Instant.ofEpochMilli(Long.valueOf(getProperty(BUILD_TIME_PROPERTY,
//                "" + System.currentTimeMillis()))));
    }

//    public ProductImpl getProduct() {
//        return product;
//    }

    public File getLocalWorkspace() {
        return localWorkspace;
    }

    public Instant getBuildDate() {
        return buildDate;
    }

    public Version getVersion() {
        return version;
    }

    public boolean isOsSpecific() {
        return osSpecific;
    }

//    public void setProduct(ProductImpl product) {
//        this.product = product;
//    }

    public void setLocalWorkspace(File localWorkspace) {
        this.localWorkspace = localWorkspace;
    }

    public void setBuildDate(Instant buildDate) {
        this.buildDate = buildDate;
    }

    public void setVersion(Version version) {
        this.version = version;
    }

    public void setOsSpecific(boolean osSpecific) {
        this.osSpecific = osSpecific;
    }
//
//    public ReleaseImpl getRelease() {
//        return release;
//    }
//
//    public void setRelease(ReleaseImpl release) {
//        this.release = release;
//    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }
}
