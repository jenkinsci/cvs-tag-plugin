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

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.scm.CVSSCM;
import hudson.scm.CvsRevisionState;
import hudson.scm.cvstagging.CvsTagAction;
import hudson.scm.cvstagging.CvsTagActionWorker;
import hudson.scm.cvstagging.LegacyTagAction;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.netbeans.lib.cvsclient.command.CommandException;
import org.netbeans.lib.cvsclient.connection.AuthenticationException;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;


/**
 * @author Brendt Lucas
 */
public class CvsTagPlugin {
    static final String DESCRIPTION = "Perform CVS tagging on successful build";

    static final String CONFIG_PREFIX = "cvstag.";

    private CvsTagPlugin() {
    }


    public static boolean perform(AbstractBuild<?, ?> build, BuildListener listener, String tagName, boolean moveTag) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();

        if (!Result.SUCCESS.equals(build.getResult())) {
            logger.println("Skipping CVS Tagging as build result was not successful.");

            return true;
        }

        AbstractProject rootProject = build.getProject().getRootProject();

        if (!(rootProject.getScm() instanceof CVSSCM)) {
            logger.println("CVS Tag plugin does not support tagging for SCM " + rootProject.getScm() + ".");

            return true;
        }

        CVSSCM scm = CVSSCM.class.cast(rootProject.getScm());

        // Evaluate the groovy tag name
        Map<String, String> env = build.getEnvironment(listener);
        tagName = evalGroovyExpression(env, tagName);


        // attempt to use tag information from CVS V2+
        CvsRevisionState state = build.getAction(CvsRevisionState.class);

        if (state != null) {
            try {
                new CvsTagActionWorker(state, tagName, true, build, new CvsTagAction(build, scm), moveTag).perform(listener);
            } catch (CommandException e) {
                e.printStackTrace(listener.error(e.getMessage()));
                return false;
            } catch (AuthenticationException e) {
                e.printStackTrace(listener.error(e.getMessage()));
                return false;
            }
            return true;
        }

        // attempt to use tag infomration from CVS V2+, created by older versions
        LegacyTagAction legacyTagAction = build.getAction(LegacyTagAction.class);

        if (legacyTagAction != null) {
           legacyTagAction.perform(tagName, moveTag, listener);
            return true;
        }

        //attempt to use tag information from versions before CVS v2
        CVSSCM.TagAction tagAction = build.getAction(CVSSCM.TagAction.class);

        if (tagAction != null) {
            try {
                Method performMethod = tagAction.getClass().getMethod("perform", String.class, TaskListener.class);
                performMethod.invoke(tagName, listener);
                return true;
            } catch (InvocationTargetException ex) {
                logger.println("Could not perform tagging - " + ex.getLocalizedMessage());
                ex.printStackTrace(logger);
                return false;
            } catch (NoSuchMethodException ex) {
                logger.println("Could not perform tagging - " + ex.getLocalizedMessage());
                ex.printStackTrace(logger);
                return false;
            } catch (IllegalAccessException ex) {
                logger.println("Could not perform tagging - " + ex.getLocalizedMessage());
                ex.printStackTrace(logger);
                return false;
            }
        }

        logger.println("Could not find any tagging information for CVS in this build");

        return false;

    }


    protected static String evalGroovyExpression(Map<String, String> env, String expression) {
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
