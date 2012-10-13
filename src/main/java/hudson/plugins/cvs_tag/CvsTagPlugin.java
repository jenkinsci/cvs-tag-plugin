/*
 * The MIT License
 *
 * Copyright (c) 2008-2012, Brendt Lucas, Derek Mahar, Bradley Trimby, Michael Clarke.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.cvs_tag;

import java.io.IOException;
import java.io.PrintStream;
import java.text.DateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.scm.CVSSCM;
import hudson.util.ArgumentListBuilder;
import org.codehaus.groovy.control.CompilerConfiguration;


/**
 * @author Brendt Lucas
 */
public class CvsTagPlugin {
    static final String DESCRIPTION = "Perform CVS tagging on successful build";

    static final String CONFIG_PREFIX = "cvstag.";

    private CvsTagPlugin() {
    }


    public static boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, String tagName, boolean moveTag) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();

        if (!Result.SUCCESS.equals(build.getResult())) {
            logger.println("Skipping CVS Tagging as build result was not successful.");

            return true;
        }

        AbstractProject rootProject = build.getProject().getRootProject();
        AbstractBuild<?,?> rootBuild = build.getRootBuild();

        if (!(rootProject.getScm() instanceof CVSSCM)) {
            logger.println("CVS Tag plugin does not support tagging for SCM " + rootProject.getScm() + ".");

            return true;
        }

        CVSSCM scm = CVSSCM.class.cast(rootProject.getScm());

        // Evaluate the groovy tag name
        Map<String, String> env = build.getEnvironment(listener);
        tagName = evalGroovyExpression(env, tagName);

        // -D option for rtag command.
        // Tag the most recent revision no later than <date> ...
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL, Locale.US);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        String date = df.format( rootBuild.getTimestamp().getTime());

        ArgumentListBuilder cmd = new ArgumentListBuilder();
        cmd.add(scm.getDescriptor().getCvsExeOrDefault(), "-d", scm.getCvsRoot());

        String branch = scm.getBranch();
        if ( branch != null)
        {
            // cvs -d cvsRoot tag -r branch tagName modules
            cmd.add("tag");
            if( moveTag )
            {
                cmd.add("-F");
            }
            cmd.add("-r",scm.getBranch(), tagName);
        }
        else
        {
            // cvs -d cvsRoot rtag -D date tagName modules
            cmd.add("rtag");
            if( moveTag )
            {
                cmd.add("-F");
            }
            cmd.add("-D", date, tagName);
        }


        String[] modulesNormalized = scm.getAllModulesNormalized();
        if( branch == null || scm.isLegacy() || modulesNormalized.length > 1 )
        {
            cmd.add(scm.getAllModulesNormalized());
        }

        logger.println("Executing tag command: " + cmd.toStringWithQuote());
        FilePath workingDir = rootBuild.getWorkspace();
        if( branch == null )
        {
            workingDir = rootBuild.getWorkspace().createTempDir("jenkins-cvs-tag","");
        }

        try {
            int exitCode = launcher.launch().cmds(cmd).envs(env).stdout(logger).pwd(workingDir).join();
            if (exitCode != 0) {
                listener.fatalError(DESCRIPTION + " failed. exit code=" + exitCode);
                build.setResult(Result.UNSTABLE);
            }
        } catch (IOException e) {
            e.printStackTrace(listener.error(e.getMessage()));
            logger.println("IOException occurred: " + e);
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace(listener.error(e.getMessage()));
            logger.println("InterruptedException occurred: " + e);
            return false;
        } finally {
            try {
                if ( !workingDir.equals(rootBuild.getWorkspace()) )
                {
                    logger.println("cleaning up " + workingDir);
                    workingDir.deleteRecursive();
                }
            } catch (IOException e) {
                e.printStackTrace(listener.error(e.getMessage()));
            }
        }

        return true;
    }


    static String evalGroovyExpression(Map<String, String> env, String expression) {
        Binding binding = new Binding();
        binding.setVariable("env", env);
        binding.setVariable("sys", System.getProperties());
        CompilerConfiguration config = new CompilerConfiguration();
        //config.setDebug(true);
        GroovyShell shell = new GroovyShell(binding, config);
        Object result = shell.evaluate("return \"" + expression + "\"");
        if (result == null) {
            return "";
        } else {
            return result.toString().trim();
        }
    }
}
