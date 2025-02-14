package org.apache.maven.plugin.plugin.report;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;

import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Prerequisites;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptorBuilder;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.plugin.descriptor.EnhancedPluginDescriptorBuilder;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.AbstractMavenReport;
import org.apache.maven.reporting.AbstractMavenReportRenderer;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.tools.plugin.DefaultPluginToolsRequest;
import org.apache.maven.tools.plugin.PluginToolsRequest;
import org.apache.maven.tools.plugin.generator.GeneratorException;
import org.apache.maven.tools.plugin.generator.GeneratorUtils;
import org.apache.maven.tools.plugin.generator.PluginXdocGenerator;
import org.apache.maven.tools.plugin.util.PluginUtils;
import org.codehaus.plexus.configuration.PlexusConfigurationException;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.XmlStreamReader;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Generates the Plugin's documentation report: <code>plugin-info.html</code> plugin overview page,
 * and one <code><i>goal</i>-mojo.html</code> per goal.
 * Relies on one output file from <a href="../maven-plugin-plugin/descriptor-mojo.html">plugin:descriptor</a>.
 *
 * @author <a href="snicoll@apache.org">Stephane Nicoll</a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @since 3.7.0
 */
@Mojo( name = "report", threadSafe = true )
@Execute( phase = LifecyclePhase.PROCESS_CLASSES )
public class PluginReport
    extends AbstractMavenReport
{
    /**
     * Report output directory for mojos' documentation.
     *
     * @since 3.7.0
     */
    @Parameter( defaultValue = "${project.build.directory}/generated-site/xdoc" )
    private File outputDirectory;

    /**
     * Set this to "true" to skip generating the report.
     *
     * @since 3.7.0
     */
    @Parameter( defaultValue = "false", property = "maven.plugin.report.skip" )
    private boolean skip;

    /**
     * Set this to "true" to generate the usage section for "plugin-info.html" with
     * {@code <extensions>true</extensions>}.
     *
     * @since 3.7.0
     */
    @Parameter( defaultValue = "false", property = "maven.plugin.report.hasExtensionsToLoad" )
    private boolean hasExtensionsToLoad;

    /**
     * The Plugin requirements history list.
     * <p>
     * Can be specified as list of <code>requirementsHistory</code>:
     *
     * <pre>
     * &lt;requirementsHistories&gt;
     *   &lt;requirementsHistory&gt;
     *     &lt;version&gt;plugin version&lt;/version&gt;
     *     &lt;maven&gt;maven version&lt;/maven&gt;
     *     &lt;jdk&gt;jdk version&lt;/jdk&gt;
     *   &lt;/requirementsHistory&gt;
     * &lt;/requirementsHistories&gt;
     * </pre>
     *
     * @since 3.7.0
     */
    @Parameter
    private List<RequirementsHistory> requirementsHistories = new ArrayList<>();

    @Component
    private RuntimeInformation rtInfo;

    /**
     * Path to enhanced plugin descriptor to generate the report from (must contain some XHTML values)
     *
     * @since 3.7.0
     */
    @Parameter( defaultValue = "${project.build.directory}/plugin-enhanced.xml", required = true,
                readonly = true )
    private File enhancedPluginXmlFile;

    /**
     * {@inheritDoc}
     */
    @Override
    protected String getOutputDirectory()
    {
        // PLUGIN-191: output directory of plugin.html, not *-mojo.xml
        return project.getReporting().getOutputDirectory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean canGenerateReport()
    {
        return enhancedPluginXmlFile != null && enhancedPluginXmlFile.isFile() && enhancedPluginXmlFile.canRead();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void executeReport( Locale locale )
        throws MavenReportException
    {
        if ( skip  )
        {
            getLog().info( "Maven Plugin Plugin Report generation skipped." );
            return;
        }

        PluginDescriptor pluginDescriptor = extractPluginDescriptor();

        // Generate the mojos' documentation
        generateMojosDocumentation( pluginDescriptor, locale );

        // Write the overview
        PluginOverviewRenderer r =
            new PluginOverviewRenderer( getProject(), requirementsHistories, getSink(),
                                        pluginDescriptor, locale, hasExtensionsToLoad );
        r.render();
    }

    private PluginDescriptor extractPluginDescriptor()
        throws MavenReportException
    {
        PluginDescriptorBuilder builder = new EnhancedPluginDescriptorBuilder( rtInfo );

        try ( Reader input = new XmlStreamReader( Files.newInputStream( enhancedPluginXmlFile.toPath() ) ) )
        {
            return builder.build( input );
        }
        catch ( IOException | PlexusConfigurationException e )
        {
            throw new MavenReportException( "Error extracting plugin descriptor from " + enhancedPluginXmlFile, e );
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDescription( Locale locale )
    {
        return getBundle( locale ).getString( "report.plugin.description" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName( Locale locale )
    {
        return getBundle( locale ).getString( "report.plugin.name" );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getOutputName()
    {
        return "plugin-info";
    }

    /**
     * Generate the mojos documentation, as xdoc files.
     *
     * @param pluginDescriptor not null
     * @param locale           not null
     * @throws MavenReportException if any
     */
    private void generateMojosDocumentation( PluginDescriptor pluginDescriptor, Locale locale )
        throws MavenReportException
    {
        try
        {
            File outputDir = outputDirectory;
            outputDir.mkdirs();

            PluginXdocGenerator generator = new PluginXdocGenerator( getProject(), locale, getReportOutputDirectory() );
            PluginToolsRequest pluginToolsRequest = new DefaultPluginToolsRequest( getProject(), pluginDescriptor );
            generator.execute( outputDir, pluginToolsRequest );
        }
        catch ( GeneratorException e )
        {
            throw new MavenReportException( "Error writing plugin documentation", e );
        }

    }

    /**
     * @param locale not null
     * @return the bundle for this report
     */
    protected static ResourceBundle getBundle( Locale locale )
    {
        return ResourceBundle.getBundle( "plugin-report", locale, PluginReport.class.getClassLoader() );
    }

    /**
     * Generates an overview page with the list of goals
     * and a link to the goal's page.
     */
    static class PluginOverviewRenderer
        extends AbstractMavenReportRenderer
    {
        private final MavenProject project;

        private final List<RequirementsHistory> requirementsHistories;

        private final PluginDescriptor pluginDescriptor;

        private final Locale locale;

        private final boolean hasExtensionsToLoad;

        /**
         * @param project               not null
         * @param requirementsHistories not null
         * @param sink                  not null
         * @param pluginDescriptor      not null
         * @param locale                not null
         */
        PluginOverviewRenderer( MavenProject project,
                                List<RequirementsHistory> requirementsHistories, Sink sink,
                                PluginDescriptor pluginDescriptor, Locale locale, boolean hasExtensionsToLoad )
        {
            super( sink );

            this.project = project;

            this.requirementsHistories = requirementsHistories;

            this.pluginDescriptor = pluginDescriptor;

            this.locale = locale;

            this.hasExtensionsToLoad = hasExtensionsToLoad;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getTitle()
        {
            return getBundle( locale ).getString( "report.plugin.title" );
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void renderBody()
        {
            startSection( getTitle() );

            if ( !( pluginDescriptor.getMojos() != null && pluginDescriptor.getMojos().size() > 0 ) )
            {
                paragraph( getBundle( locale ).getString( "report.plugin.goals.nogoal" ) );
                endSection();
                return;
            }

            paragraph( getBundle( locale ).getString( "report.plugin.goals.intro" ) );

            boolean hasMavenReport = false;
            for ( Iterator<MojoDescriptor> i = pluginDescriptor.getMojos().iterator(); i.hasNext(); )
            {
                MojoDescriptor mojo = i.next();

                if ( GeneratorUtils.isMavenReport( mojo.getImplementation(), project ) )
                {
                    hasMavenReport = true;
                }
            }

            startTable();

            String goalColumnName = getBundle( locale ).getString( "report.plugin.goals.column.goal" );
            String isMavenReport = getBundle( locale ).getString( "report.plugin.goals.column.isMavenReport" );
            String descriptionColumnName = getBundle( locale ).getString( "report.plugin.goals.column.description" );
            if ( hasMavenReport )
            {
                tableHeader( new String[] {goalColumnName, isMavenReport, descriptionColumnName} );
            }
            else
            {
                tableHeader( new String[] {goalColumnName, descriptionColumnName} );
            }

            List<MojoDescriptor> mojos = new ArrayList<>();
            mojos.addAll( pluginDescriptor.getMojos() );
            PluginUtils.sortMojos( mojos );
            for ( MojoDescriptor mojo : mojos )
            {
                String goalName = mojo.getFullGoalName();

                /*
                 * Added ./ to define a relative path
                 * @see AbstractMavenReportRenderer#getValidHref(java.lang.String)
                 */
                String goalDocumentationLink = "./" + mojo.getGoal() + "-mojo.html";

                String description;
                if ( StringUtils.isNotEmpty( mojo.getDeprecated() ) )
                {
                    description =
                        "<strong>" + getBundle( locale ).getString( "report.plugin.goal.deprecated" ) + "</strong> "
                            + mojo.getDeprecated();
                }
                else if ( StringUtils.isNotEmpty( mojo.getDescription() ) )
                {
                    description = mojo.getDescription();
                }
                else
                {
                    description = getBundle( locale ).getString( "report.plugin.goal.nodescription" );
                }

                sink.tableRow();
                tableCell( createLinkPatternedText( goalName, goalDocumentationLink ) );
                if ( hasMavenReport )
                {
                    if ( GeneratorUtils.isMavenReport( mojo.getImplementation(), project ) )
                    {
                        sink.tableCell();
                        sink.text( getBundle( locale ).getString( "report.plugin.isReport" ) );
                        sink.tableCell_();
                    }
                    else
                    {
                        sink.tableCell();
                        sink.text( getBundle( locale ).getString( "report.plugin.isNotReport" ) );
                        sink.tableCell_();
                    }
                }
                tableCell( description, true );
                sink.tableRow_();
            }

            endTable();

            startSection( getBundle( locale ).getString( "report.plugin.systemrequirements" ) );

            paragraph( getBundle( locale ).getString( "report.plugin.systemrequirements.intro" ) );

            startTable();

            String maven = discoverMavenRequirement( project );
            sink.tableRow();
            tableCell( getBundle( locale ).getString( "report.plugin.systemrequirements.maven" ) );
            tableCell( ( maven != null
                ? maven
                : getBundle( locale ).getString( "report.plugin.systemrequirements.nominimum" ) ) );
            sink.tableRow_();

            String jdk = discoverJdkRequirement( project );
            sink.tableRow();
            tableCell( getBundle( locale ).getString( "report.plugin.systemrequirements.jdk" ) );
            tableCell(
                ( jdk != null ? jdk : getBundle( locale ).getString( "report.plugin.systemrequirements.nominimum" ) ) );
            sink.tableRow_();

            endTable();

            endSection();

            renderRequirementsHistories();

            renderUsageSection( hasMavenReport );

            endSection();
        }

        private void renderRequirementsHistories()
        {
            if ( requirementsHistories.isEmpty() )
            {
                return;
            }

            startSection( getBundle( locale ).getString( "report.plugin.systemrequirements.history" ) );
            paragraph( getBundle( locale ).getString( "report.plugin.systemrequirements.history.intro" ) );

            startTable();
            tableHeader( new String[] {
                getBundle( locale ).getString( "report.plugin.systemrequirements.history.version" ),
                getBundle( locale ).getString( "report.plugin.systemrequirements.history.maven" ),
                getBundle( locale ).getString( "report.plugin.systemrequirements.history.jdk" )
            } );

            requirementsHistories.forEach(
                requirementsHistory ->
                {
                    sink.tableRow();
                    tableCell( requirementsHistory.getVersion() );
                    tableCell( requirementsHistory.getMaven() );
                    tableCell( requirementsHistory.getJdk() );
                    sink.tableRow_();
                } );
            endTable();

            endSection();
        }

        /**
         * Render the section about the usage of the plugin.
         *
         * @param hasMavenReport If the plugin has a report or not
         */
        private void renderUsageSection( boolean hasMavenReport )
        {
            startSection( getBundle( locale ).getString( "report.plugin.usage" ) );

            // Configuration
            sink.paragraph();
            text( getBundle( locale ).getString( "report.plugin.usage.intro" ) );
            sink.paragraph_();

            StringBuilder sb = new StringBuilder();
            sb.append( "<project>" ).append( '\n' );
            sb.append( "  ..." ).append( '\n' );
            sb.append( "  <build>" ).append( '\n' );
            sb.append(
                "    <!-- " + getBundle( locale ).getString( "report.plugin.usage.pluginManagement" ) + " -->" ).append(
                '\n' );
            sb.append( "    <pluginManagement>" ).append( '\n' );
            sb.append( "      <plugins>" ).append( '\n' );
            sb.append( "        <plugin>" ).append( '\n' );
            sb.append( "          <groupId>" ).append( pluginDescriptor.getGroupId() ).append( "</groupId>" ).append(
                '\n' );
            sb.append( "          <artifactId>" ).append( pluginDescriptor.getArtifactId() ).append(
                "</artifactId>" ).append( '\n' );
            sb.append( "          <version>" ).append( pluginDescriptor.getVersion() ).append( "</version>" ).append(
                '\n' );
            if ( hasExtensionsToLoad )
            {
                sb.append( "          <extensions>true</extensions>" ).append(
                    '\n' );
            }
            sb.append( "        </plugin>" ).append( '\n' );
            sb.append( "        ..." ).append( '\n' );
            sb.append( "      </plugins>" ).append( '\n' );
            sb.append( "    </pluginManagement>" ).append( '\n' );
            sb.append( "    <!-- " + getBundle( locale ).getString( "report.plugin.usage.plugins" ) + " -->" ).append(
                '\n' );
            sb.append( "    <plugins>" ).append( '\n' );
            sb.append( "      <plugin>" ).append( '\n' );
            sb.append( "        <groupId>" ).append( pluginDescriptor.getGroupId() ).append( "</groupId>" ).append(
                '\n' );
            sb.append( "        <artifactId>" ).append( pluginDescriptor.getArtifactId() ).append(
                "</artifactId>" ).append( '\n' );
            sb.append( "      </plugin>" ).append( '\n' );
            sb.append( "      ..." ).append( '\n' );
            sb.append( "    </plugins>" ).append( '\n' );
            sb.append( "  </build>" ).append( '\n' );

            if ( hasMavenReport )
            {
                sb.append( "  ..." ).append( '\n' );
                sb.append(
                    "  <!-- " + getBundle( locale ).getString( "report.plugin.usage.reporting" ) + " -->" ).append(
                    '\n' );
                sb.append( "  <reporting>" ).append( '\n' );
                sb.append( "    <plugins>" ).append( '\n' );
                sb.append( "      <plugin>" ).append( '\n' );
                sb.append( "        <groupId>" ).append( pluginDescriptor.getGroupId() ).append( "</groupId>" ).append(
                    '\n' );
                sb.append( "        <artifactId>" ).append( pluginDescriptor.getArtifactId() ).append(
                    "</artifactId>" ).append( '\n' );
                sb.append( "        <version>" ).append( pluginDescriptor.getVersion() ).append( "</version>" ).append(
                    '\n' );
                sb.append( "      </plugin>" ).append( '\n' );
                sb.append( "      ..." ).append( '\n' );
                sb.append( "    </plugins>" ).append( '\n' );
                sb.append( "  </reporting>" ).append( '\n' );
            }

            sb.append( "  ..." ).append( '\n' );
            sb.append( "</project>" ).append( '\n' );

            verbatimText( sb.toString() );

            sink.paragraph();
            linkPatternedText( getBundle( locale ).getString( "report.plugin.configuration.end" ) );
            sink.paragraph_();

            endSection();
        }

        /**
         * Try to lookup on the Maven prerequisites property.
         *
         * @param project      not null
         * @return the Maven version or null if not specified
         */
        private static String discoverMavenRequirement( MavenProject project )
        {
            return Optional.ofNullable( project.getPrerequisites() )
                .map( Prerequisites::getMaven )
                .orElse( null );
        }

        /**
         * <ol>
         * <li>use configured jdk requirement</li>
         * <li>use <code>target</code> configuration of <code>org.apache.maven.plugins:maven-compiler-plugin</code></li>
         * <li>use <code>target</code> configuration of <code>org.apache.maven.plugins:maven-compiler-plugin</code> in
         * <code>pluginManagement</code></li>
         * <li>use <code>maven.compiler.target</code> property</li>
         * </ol>
         *
         * @param project      not null
         * @return the JDK version
         */
        private static String discoverJdkRequirement( MavenProject project )
        {

            Plugin compiler = getCompilerPlugin( project.getBuild().getPluginsAsMap() );
            if ( compiler == null )
            {
                compiler = getCompilerPlugin( project.getPluginManagement().getPluginsAsMap() );
            }

            String jdk = getPluginParameter( compiler, "release" );
            if ( jdk != null )
            {
                return jdk;
            }

            jdk = project.getProperties().getProperty( "maven.compiler.release" );
            if ( jdk != null )
            {
                return jdk;
            }

            jdk = getPluginParameter( compiler, "target" );
            if ( jdk != null )
            {
                return jdk;
            }

            // default value
            jdk = project.getProperties().getProperty( "maven.compiler.target" );
            if ( jdk != null )
            {
                return jdk;
            }

            String version = ( compiler == null ) ? null : compiler.getVersion();

            if ( version != null )
            {
                return "Default target for maven-compiler-plugin version " + version;
            }

            return null;
        }

        private static Plugin getCompilerPlugin( Map<String, Plugin> pluginsAsMap )
        {
            return pluginsAsMap.get( "org.apache.maven.plugins:maven-compiler-plugin" );
        }

        private static String getPluginParameter( Plugin plugin, String parameter )
        {
            if ( plugin != null )
            {
                Xpp3Dom pluginConf = (Xpp3Dom) plugin.getConfiguration();

                if ( pluginConf != null )
                {
                    Xpp3Dom target = pluginConf.getChild( parameter );

                    if ( target != null )
                    {
                        return target.getValue();
                    }
                }
            }

            return null;
        }
    }
}
