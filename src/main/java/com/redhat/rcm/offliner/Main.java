/**
 * Copyright (C) 2015 Red Hat, Inc. (jcasey@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.rcm.offliner;

import com.redhat.rcm.offliner.alist.ArtifactListReader;
import com.redhat.rcm.offliner.alist.FoloReportArtifactListReader;
import com.redhat.rcm.offliner.alist.PlaintextArtifactListReader;
import com.redhat.rcm.offliner.alist.PomArtifactListReader;
import com.redhat.rcm.offliner.model.ArtifactList;
import com.redhat.rcm.offliner.model.DownloadResult;
import com.redhat.rcm.offliner.util.UrlUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.AbstractExecutionAwareRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
import org.commonjava.maven.atlas.ident.version.SingleVersion;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;

public class Main
{
    private static final String SEP = "---------------------------------------------------------------";

    private final Options opts;

    private CloseableHttpClient client;

    private HttpClientContext contextPrototype;

    private ExecutorService executorService;

    private ExecutorCompletionService<DownloadResult> executor;

    private int downloaded = 0;

    private int avoided = 0;

    private ConcurrentHashMap<String, Throwable> errors;

    private List<ArtifactListReader> artifactListReaders;

    private List<String> baseUrls;

    public Main( final Options opts )
            throws MalformedURLException
    {
        this.opts = opts;
        init();
    }

    public static void main( final String[] args )
    {
        final Options opts = new Options();
        boolean start = false;
        try
        {
            start = opts.parseArgs( args );
        }
        catch ( final CmdLineException e )
        {
            e.printStackTrace();
            System.err.println( "Cannot start. See error output above." );
            System.exit( 1 );
        }

        if ( start )
        {
            try
            {
                new Main( opts ).run();
            }
            catch ( final MalformedURLException e )
            {
                e.printStackTrace();
                System.err.println( "Cannot initialize HTTP client / context. See error output above." );
                System.exit( 2 );
            }
        }
    }

    public Main run()
    {
        final List<String> files = opts.getLocations();
        if ( files == null || files.isEmpty() )
        {
            System.out.println( "Nothing to do!" );
            return this;
        }

        try
        {
            Set<String> seen = new HashSet<>();
            int total = 0;
            for ( final String file : files )
            {
                total += download( file, seen );
            }

            for ( int i = 0; i < total; i++ )
            {
                System.out.printf( "Waiting for %d downloads\n", ( total - i ) );

                Future<DownloadResult> task = executor.take();
                DownloadResult result = task.get();
                if ( result == null )
                {
                    System.err.println( "BUG: DownloadResult returned from execution should NEVER be null!" );
                }
                else if ( result.isSuccess() )
                {
                    downloaded++;
                    System.out.printf( "<<<SUCCESS: %s\n", result.getPath() );
                }
                else if ( result.isAvoided() )
                {
                    avoided++;
                    System.out.printf( "<<<Avoided: %s\n", result.getPath() );
                }
                else
                {
                    errors.put( result.getPath(), result.getError() );
                    System.out.printf( "<<<FAIL: %s\n", result.getPath() );
                }
            }

            Set<String> pomPaths = new HashSet<>();
            File download = opts.getDownloads().getAbsoluteFile();
            searchForPomPaths( download, pomPaths );
            generateMetadata( pomPaths );

            logErrors();

            executorService.shutdown();
            executorService.awaitTermination( 30, TimeUnit.SECONDS );
        }
        catch ( final InterruptedException e )
        {
            System.err.println( "Interrupted waiting for download executor to shutdown." );
        }
        catch ( final ExecutionException e )
        {
            // TODO: Handle suppressed exceptions
            System.err.println( "Download execution manager failed." );
            e.printStackTrace();
        }
        catch ( IOException | OfflinerException e )
        {
            e.printStackTrace();
        }
        finally
        {
            IOUtils.closeQuietly( client );
        }

        return this;
    }

    private void logErrors()
    {
        System.out.printf( "%d downloads succeeded.\n%d downloads avoided.\n%d downloads failed.\n\n", downloaded,
                           avoided, errors.size() );

        if ( !errors.isEmpty() )
        {
            System.err.printf( "See %s for details.", Options.ERROR_LOG );

            final File errorLog = new File( Options.ERROR_LOG );
            try (PrintWriter writer = new PrintWriter( new FileWriter( errorLog ) ))
            {
                for ( final Map.Entry<String, Throwable> entry : errors.entrySet() )
                {
                    writer.printf( "Path: %s\n%s\n", entry.getKey(), SEP );
                    entry.getValue().printStackTrace( writer );
                    writer.printf( "\n%s\n\n", SEP );
                }
            }
            catch ( final IOException e )
            {
                e.printStackTrace();
                System.err.println( "Failed to write download errors to: " + Options.ERROR_LOG
                                            + ". See above for more information." );
            }
        }
    }

    private int download( final String filepath, Set<String> seen )
            throws IOException, OfflinerException
    {
        System.out.println( "Downloading artifacts from: " + filepath );

        final List<String> paths;
        List<String> baseUrls = this.baseUrls;
        Map<String, String> checksums = new HashMap<String, String>();
        try
        {
            File file = new File( filepath );
            ArtifactListReader reader = getArtifactListReader( file );
            ArtifactList artifactList = reader.readPaths( file );

            if ( baseUrls == null || baseUrls.isEmpty() )
            {
                baseUrls = artifactList.getRepositoryUrls();
                if ( baseUrls == null )
                {
                    baseUrls = new ArrayList<>();
                }

                if ( baseUrls.isEmpty() )
                {
                    baseUrls.add( Options.DEFAULT_REPO_URL );
                    baseUrls.add( Options.CENTRAL_REPO_URL );
                }
            }

            paths = artifactList.getPaths();
            checksums = artifactList.getChecksums();
        }
        catch ( final IOException e )
        {
            System.err.printf( "\n\nFailed to read paths from file: %s. See above for more information.\n", filepath );
            throw e;
        }

        if ( paths == null || paths.isEmpty() )
        {
            System.err.println( "Nothing to download!" );
            return 0;
        }

        int count = 0;
        for ( final String path : paths )
        {
            if ( !seen.contains( path ) )
            {
                executor.submit( newDownloader( baseUrls, path, checksums ) );
                count++;
            }
        }

        return count;
    }

    private ArtifactListReader getArtifactListReader( File file )
            throws OfflinerException
    {
        for ( ArtifactListReader reader : artifactListReaders )
        {
            if ( reader.supports( file ) )
            {
                return reader;
            }
        }
        throw new OfflinerException( "No reader supports file %s.", file.getPath() );
    }

    private Callable<DownloadResult> newDownloader( final List<String> baseUrls, final String path,
                                                    final Map<String, String> checksums )
    {
        return () -> {
            final String name = Thread.currentThread().getName();
            Thread.currentThread().setName( "download--" + path );
            try
            {
                final File target = new File( opts.getDownloads(), path );

                if ( target.exists() )
                {
                    if ( null == checksums || checksums.isEmpty() || !checksums.containsKey( path ) || null == checksums
                            .get( path ) )
                    {
                        return DownloadResult.avoid( path, true );
                    }

                    byte[] b = FileUtils.readFileToByteArray( target );
                    String original = checksums.get( path );
                    String current = sha256Hex( b );

                    if ( original.equals( current ) )
                    {
                        return DownloadResult.avoid( path, true );
                    }
                }

                final File dir = target.getParentFile();
                dir.mkdirs();

                final File part = new File( dir, target.getName() + ".part" );
                part.deleteOnExit();

                int reposRemaining = baseUrls.size();
                for ( String baseUrl : baseUrls )
                {
                    reposRemaining--;
                    String url;
                    try
                    {
                        url = UrlUtils.buildUrl( baseUrl, path );
                    }
                    catch ( final Exception e )
                    {
                        return DownloadResult.error( path, e );
                    }

                    System.out.println( ">>>Downloading: " + url );

                    final HttpClientContext context = new HttpClientContext( contextPrototype );
                    final HttpGet request = new HttpGet( url );
                    try (CloseableHttpResponse response = client.execute( request, context ))
                    {
                        int statusCode = response.getStatusLine().getStatusCode();
                        if ( statusCode == 200 )
                        {
                            try (FileOutputStream out = new FileOutputStream( part ))
                            {
                                byte[] b = IOUtils.toByteArray( response.getEntity().getContent() );
                                out.write( b );

                                if ( null == checksums || checksums.isEmpty() || !checksums.containsKey( path )
                                        || null == checksums.get( path ) )
                                {
                                    out.flush();
                                    out.close();
                                }
                                else
                                {
                                    String original = checksums.get( path );
                                    String current = sha256Hex( b );

                                    if ( !original.equals( current ) )
                                    {
                                        return DownloadResult.error( path, new IOException(
                                                "Checksum checked error on file: " + path ) );
                                    }
                                    ChecksumOutputStream checksumOutputStream =
                                            new ChecksumOutputStream( out, current );
                                    checksumOutputStream.flush();
                                    checksumOutputStream.close();
                                }
                            }

                            part.renameTo( target );
                            return DownloadResult.success( baseUrl, path );
                        }
                        else if ( statusCode == 404 )
                        {
                            System.out.println( "<<<Not Found: " + url );
                            if ( reposRemaining == 0 )
                            {
                                return DownloadResult.error( path, new IOException(
                                        "Error downloading path: " + path + ". The artifact was not "
                                                + "found in any of the provided repositories." ) );
                            }
                        }
                        else
                        {
                            final String serverError = IOUtils.toString( response.getEntity().getContent() );

                            String message = String.format(
                                    "Error downloading path: %s.\n%s\nServer status: %s\nServer response was:\n%s\n%s",
                                    path, SEP, response.getStatusLine(), serverError, SEP );

                            if ( reposRemaining == 0 )
                            {
                                return DownloadResult.error( path, new IOException( message ) );
                            }
                            else
                            {
                                System.out.println( "<<<" + message );
                            }
                        }

                    }
                    catch ( final IOException e )
                    {
                        return DownloadResult.error( path, new IOException( "URL: " + url + " failed.", e ) );
                    }
                    finally
                    {
                        if ( request != null )
                        {
                            request.releaseConnection();

                            if ( request instanceof AbstractExecutionAwareRequest )
                            {
                                ( (AbstractExecutionAwareRequest) request ).reset();
                            }
                        }
                    }
                }
            }
            finally
            {
                Thread.currentThread().setName( name );
            }

            return null;
        };
    }

    private void init()
            throws MalformedURLException
    {
        int cpus = Runtime.getRuntime().availableProcessors();
        executorService = Executors.newFixedThreadPool( cpus * 2, ( final Runnable r ) -> {
            //        executorService = Executors.newCachedThreadPool( ( final Runnable r ) -> {
            final Thread t = new Thread( r );
            t.setDaemon( true );

            return t;
        } );

        executor = new ExecutorCompletionService<>( executorService );

        errors = new ConcurrentHashMap<String, Throwable>();

        final PoolingHttpClientConnectionManager ccm = new PoolingHttpClientConnectionManager();
        ccm.setMaxTotal( opts.getConnections() );

        final HttpClientBuilder builder = HttpClients.custom().setConnectionManager( ccm );

        final String proxy = opts.getProxy();
        String proxyHost = proxy;
        int proxyPort = 8080;
        if ( proxy != null )
        {
            final int portSep = proxy.lastIndexOf( ':' );

            if ( portSep > -1 )
            {
                proxyHost = proxy.substring( 0, portSep );
                proxyPort = Integer.parseInt( proxy.substring( portSep + 1 ) );
            }
            final HttpRoutePlanner planner = new DefaultProxyRoutePlanner( new HttpHost( proxyHost, proxyPort ) );

            builder.setRoutePlanner( planner );
        }

        client = builder.build();

        final CredentialsProvider creds = new BasicCredentialsProvider();

        contextPrototype = HttpClientContext.create();
        contextPrototype.setCredentialsProvider( creds );

        baseUrls = opts.getBaseUrls();
        if ( baseUrls == null )
        {
            baseUrls = new ArrayList<>();
        }

        if ( baseUrls.isEmpty() )
        {
            baseUrls.add( Options.DEFAULT_REPO_URL );
            baseUrls.add( Options.CENTRAL_REPO_URL );
        }

        System.out.println( "Planning download from:\n  " + StringUtils.join( baseUrls, "\n  " ) );

        for ( String baseUrl : baseUrls )
        {
            if ( baseUrl != null )
            {
                final String user = opts.getUser();
                if ( user != null )
                {
                    final URL u = new URL( baseUrl );
                    final AuthScope as = new AuthScope( u.getHost(), UrlUtils.getPort( u ) );

                    creds.setCredentials( as, new UsernamePasswordCredentials( user, opts.getPassword() ) );
                }
            }

            if ( proxy != null )
            {
                final String proxyUser = opts.getProxyUser();
                if ( proxyUser != null )
                {
                    creds.setCredentials( new AuthScope( proxyHost, proxyPort ),
                                          new UsernamePasswordCredentials( proxyUser, opts.getProxyPassword() ) );
                }
            }
        }

        artifactListReaders = new ArrayList<>( 3 );
        artifactListReaders.add( new FoloReportArtifactListReader() );
        artifactListReaders.add( new PlaintextArtifactListReader() );
        artifactListReaders.add( new PomArtifactListReader( opts.getSettingsXml(), opts.getTypeMapping(), creds ) );
    }

    private void searchForPomPaths( File root, Set<String> pomPaths )
    {
        if ( null == root || null == pomPaths )
        {
            return;
        }
        if ( root.isDirectory() )
        {
            for ( File file : root.listFiles() )
            {
                searchForPomPaths( file, pomPaths );
            }
        }
        else if ( root.isFile() && root.getName().endsWith( ".pom" ) )
        {
            pomPaths.add( root.getPath().substring( opts.getDownloads().getAbsolutePath().length() + 1 ) );
        }
    }

    private void generateMetadata( Set<String> pomPaths )
    {
        Map<ProjectRef, List<SingleVersion>> metas = new HashMap<ProjectRef, List<SingleVersion>>();
        for ( String path : pomPaths )
        {
            ArtifactPathInfo artifactPathInfo = ArtifactPathInfo.parse( path );
            ProjectVersionRef gav = artifactPathInfo.getProjectId();
            List<SingleVersion> singleVersions = new ArrayList<SingleVersion>();
            if ( !metas.isEmpty() && metas.containsKey( gav.asProjectRef() ) )
            {
                singleVersions = metas.get( gav.asProjectRef() );
            }
            singleVersions.add( (SingleVersion) gav.getVersionSpec() );
            metas.put( gav.asProjectRef(), singleVersions );
        }
        for ( ProjectRef ga : metas.keySet() )
        {
            List<SingleVersion> singleVersions = metas.get( ga );
            Collections.sort( singleVersions );

            Metadata master = new Metadata();
            master.setGroupId( ga.getGroupId() );
            master.setArtifactId( ga.getArtifactId() );
            Versioning versioning = new Versioning();
            for ( SingleVersion v : singleVersions )
            {
                versioning.addVersion( v.renderStandard() );
            }
            String latest = singleVersions.get( singleVersions.size() - 1 ).renderStandard();
            versioning.setLatest( latest );
            versioning.setRelease( latest );
            master.setVersioning( versioning );

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            File metadataFile = Paths.get( opts.getDownloads().getAbsolutePath(),
                                           ga.getGroupId().replace( '.', File.separatorChar ), ga.getArtifactId(),
                                           "maven-metadata.xml" ).toFile();
            try
            {
                new MetadataXpp3Writer().write( baos, master );
                FileUtils.writeByteArrayToFile( metadataFile, baos.toByteArray() );
            }
            catch ( IOException e )
            {
                e.printStackTrace();
                System.err.printf( "\n\nFailed to generate maven-metadata file: %s. See above for more information.\n",
                                   metadataFile );
            }
        }
    }

    public int getDownloaded()
    {
        return downloaded;
    }

    public int getAvoided()
    {
        return avoided;
    }

    public ConcurrentHashMap<String, Throwable> getErrors()
    {
        return errors;
    }

    public List<String> getBaseUrls()
    {
        return baseUrls;
    }
}
