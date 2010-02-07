package com.google.code.maven.plugin.dependency;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;


import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.install.AbstractInstallMojo;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.codehaus.plexus.digest.Digester;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.WriterFactory;


/**
 * Acquire external Maven artifacts and install 
 * into local Maven (M2) repository.
 *
 * @goal process-external
 * @aggregator 
 *
 * @phase process-resources
 * 
 * @author <a href="mailto:robert@savage7.com">Robert Savage</a>
 * @see  http://code.google.com/p/maven-external-dependency-plugin/
 * @version 0.1
 * @category Maven Plugin
 */
public class ExternalDependencyMojo extends AbstractInstallMojo
{
    /**
    * @component
    */
    protected ArtifactInstaller installer;
    
    /**
     * Used to look up Artifacts in the remote repository.
     * 
     * @parameter expression="${component.org.apache.maven.artifact.factory.ArtifactFactory}"
     * @required
     * @readonly
     */
    protected ArtifactFactory artifactFactory;    
    
    /**
    * @parameter expression="${localRepository}"
    * @required
    * @readonly
    */
    protected ArtifactRepository localRepository;
    
   /**
    * Collection of ArtifactItems to work on. (ArtifactItem contains groupId,
    * artifactId, version, type, classifier, location, destFile, markerFile and overwrite.)
    * See "Usage" and "Javadoc" for details.
    * 
    * @parameter
    * @required
    */
    protected ArrayList<ArtifactItem> artifactItems;    
    
    
    /**
     * Digester for MD5.
     *
     * @component default-value="sha1"
     */
    protected Digester md5Digester;

    /**
     * Digester for SHA-1.
     *
     * @component default-value="sha1"
     */
    protected Digester sha1Digester;
    
    
    /**
     * Flag whether to create checksums (MD5, SHA-1) or not.
     *
     * @parameter expression="${createChecksum}" default-value="true"
     */
    protected boolean createChecksum = true;

    public void execute() throws MojoExecutionException, MojoFailureException  
    {
        try
        {
            getLog().info("EXECUTING MOJO: 'maven-external-dependency-plugin'");
            
            // update base configuration parameters
            // (not sure why this is needed, but doesn't see to work otherwise?)
            super.createChecksum = this.createChecksum;
            super.artifactFactory = this.artifactFactory;
            super.localRepository = this.localRepository;
            super.md5Digester = this.md5Digester;
            super.sha1Digester = this.sha1Digester;

            // loop over and process all configured artifacts 
            for(ArtifactItem artifactItem : artifactItems)
            {
                getLog().info("PROCESSING ARTIFACT: " + artifactItem.toString());
                
                // local variables
                File tempDownloadFile = null;
                File tempArtifactPomFile = null;

                //
                // CREATE MAVEN ARTIFACT
                //
                Artifact artifact = createArtifact(artifactItem);

                // determine if the artifact is already installed in the local Maven repository
                Boolean artifactAlreadyInstalled = getLocalRepoFile(artifact).exists();
                
                // only proceed with this artifact if it is not already 
                // installed or it is configured to be forced.
                if(!artifactAlreadyInstalled || 
                    artifactItem.getForce())
                {
                    if(artifactItem.getForce())
                    {
                        getLog().debug("FORCING ARTIFACT: " + artifactItem.toString());
                    }
                    
                    //
                    // DOWNLOAD FILE FROM URL
                    //
                    if(artifactItem.getDownloadUrl() != null)
                    {
                        getLog().info("DOWNLOADING ARTIFACT FROM: " + artifactItem.getDownloadUrl());
                        
                        // if the user did not specify a local file 
                        // name, then create a temporary file 
                        if(artifactItem.getLocalFile() == null)
                        {
                            tempDownloadFile = File.createTempFile( "download", ".tmp" );
                            artifactItem.setLocalFile(tempDownloadFile.getCanonicalPath());
                            
                            getLog().debug("CREATING TEMP FILE FOR DOWNLOAD: " + tempDownloadFile.getCanonicalPath());
                        }
                        else
                        {
                            getLog().info("SAVING ARTIFACT TO: " + artifactItem.getLocalFile());
                        }
                        
                        // download file from URL 
                        FileUtils.copyURLToFile(new URL(artifactItem.getDownloadUrl()), new File(artifactItem.getLocalFile()));
                        
                        getLog().debug("ARTIFACT FILE DOWNLOADED SUCCESSFULLY.");
                    }
    
                    
                    //
                    // INSTALL MAVEN ARTIFACT TO LOCAL REPOSITORY
                    //
                    if(artifact != null &&
                       artifactItem.getInstall())
                    {
                        getLog().info("INSTALLING ARTIFACT TO M2 REPO: " + localRepository.getId() );

                        // create Maven artifact POM file
                        tempArtifactPomFile = generatePomFile(artifactItem);
                        if(tempArtifactPomFile != null && tempArtifactPomFile.exists())
                        {
                            ArtifactMetadata artifactPomMetadata = new ProjectArtifactMetadata( artifact, tempArtifactPomFile );
                            artifact.addMetadata( artifactPomMetadata );
                        }
                        
                        // install artifact to local repository
                        installer.install(new File(artifactItem.getLocalFile()),artifact,localRepository);
                        
                        // install checksum files to local repository
                        installChecksums( artifact );
                    }
                    else
                    {
                        getLog().debug("CONFIGURED TO NOT INSTALL ARTIFACT: " + artifactItem.toString());
                    }


                    //
                    // DEPLOY TO ALTERNATE MAVEN REPOSITORY
                    //
                    //TODO: implement Maven deployment
                    if( artifactItem.getDeploy() )
                    {
                        getLog().warn("ARTIFACT DEPLOYMENT NOT YET IMPLEMENTED: " + artifactItem.toString());
                    }
                    
                    
                    //
                    // DELETE TEMPORARY FILES 
                    //
                    
                    // delete temporary POM file
                    if ( tempArtifactPomFile != null )
                    {
                        getLog().debug("DELETING TEMP POM FILE: " + tempArtifactPomFile.getCanonicalPath());
                        tempArtifactPomFile.delete();
                    }
                    
                    // delete temporary file if one exists
                    if( tempDownloadFile != null )
                    {
                        getLog().debug("DELETING TEMP DOWNLOAD FILE: " + tempDownloadFile.getCanonicalPath());
                        tempDownloadFile.delete();
                    }
                }
                else
                {
                    getLog().info("ARTIFACT ALREADY EXISTS IN LOCAL REPO: " + artifactItem.toString());
                }
            }
            
            getLog().info("EXITING MOJO: 'maven-external-dependency-plugin'");
        } 
        catch (Exception e) 
        {
            getLog().error(e);
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    

    /**
     * Create Maven Artifact object from ArtifactItem configuration descriptor
     *  
     * @param item
     * @return Artifact
     */
    private Artifact createArtifact(ArtifactItem item)
    {
        Artifact artifact = null; 
    
       // create Maven artifact with a classifier 
        artifact = artifactFactory.createArtifactWithClassifier(
                            item.getGroupId(),
                            item.getArtifactId(),
                            item.getVersion(),
                            item.getPackaging(), 
                            item.getClassifier());

        return artifact;        
    }
    
    
    /**
     * Generates a (temporary) POM file from the plugin configuration. It's the responsibility of the caller to delete
     * the generated file when no longer needed.
     *
     * @return The path to the generated POM file, never <code>null</code>.
     * @throws MojoExecutionException If the POM file could not be generated.
     */
    private File generatePomFile(ArtifactItem artifact) throws MojoExecutionException
    {
        Model model = generateModel(artifact);

        Writer writer = null;
        try
        {
            File pomFile = File.createTempFile( "temp-m2-artifact", ".pom" );

            writer = WriterFactory.newXmlWriter( pomFile );
            new MavenXpp3Writer().write( writer, model );

            return pomFile;
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Error writing temporary POM file: " + e.getMessage(), e );
        }
        finally
        {
            IOUtil.close( writer );
        }
    }    
    
    
    /**
     * Generates a minimal model from the user-supplied artifact information.
     *
     * @return The generated model, never <code>null</code>.
     */
    private Model generateModel(ArtifactItem artifact)
    {
        Model model = new Model();
        model.setModelVersion( "4.0.0" );
        model.setGroupId( artifact.getGroupId() );
        model.setArtifactId( artifact.getArtifactId() );
        model.setVersion( artifact.getVersion() );
        model.setPackaging( artifact.getPackaging() );

        return model;
    }    
}