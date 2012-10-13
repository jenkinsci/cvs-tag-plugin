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
import java.util.HashMap;

import javax.servlet.ServletException;

import hudson.tasks.Publisher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.Extension;
import hudson.Launcher;
import hudson.scm.cvs.Messages;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.codehaus.groovy.control.CompilationFailedException;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.QueryParameter;

import static hudson.plugins.cvs_tag.CvsTagPlugin.CONFIG_PREFIX;
import static hudson.plugins.cvs_tag.CvsTagPlugin.DESCRIPTION;


/**
 * @author Brendt Lucas
 */
public class CvsTagPublisher extends Recorder {
    /**
     * The tag name
     */
    private String tagName;

    /**
     * Move the tag.
     */
    private boolean moveTag;

    @DataBoundConstructor
    public CvsTagPublisher(String tagName, boolean moveTag) {
        super();
        this.tagName = tagName;
        this.moveTag = moveTag;
    }


    /**
     * @return the tag name
     */
    public String getTagName() {
        if (tagName == null || tagName.length() == 0) {
            return getDescriptor().getDefaultTagName();
        }

        return tagName;
    }

    /**
     * Determine whether to move the tag if it exists
     *
     * @return true if the tag should be moved, else false.
     */
    public boolean isMoveTag() {
        return moveTag;
    }

    /**
     *
     * @return whether CVS should move any existing tag with the same name
     * @deprecated use #isMoveTag()
     */
    @Deprecated
    public boolean getMoveTag() {
        return moveTag;
    }

    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        return CvsTagPlugin.perform(build, launcher, listener, tagName, moveTag);
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        return true;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public CvsTagDescriptorImpl getDescriptor() {
        return (CvsTagDescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class CvsTagDescriptorImpl extends BuildStepDescriptor<Publisher> {
        private String defaultTagName;

        public CvsTagDescriptorImpl() {
            super(CvsTagPublisher.class);
            this.defaultTagName = "${env['JOB_NAME']}-${env['BUILD_NUMBER']}-${new java.text.SimpleDateFormat(\"yyyy_MM_dd\").format(new Date())}";
            load();
        }

        @Override
        public String getDisplayName() {
            return DESCRIPTION;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            this.defaultTagName = req.getParameter(CONFIG_PREFIX + "tagName");

            save();

            return super.configure(req, formData);
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        public String getDefaultTagName() {
            return defaultTagName;
        }

        public FormValidation doTagNameCheck(@QueryParameter("value") final String tagName) throws IOException, ServletException {
            if (tagName == null || tagName.length() == 0) {
                return FormValidation.error("Please specify a name for this tag.");
            } else {
                // Test to make sure the tag name is valid
                String s = null;
                try {
                    s = CvsTagPlugin.evalGroovyExpression(new HashMap<String, String>(), tagName);
                } catch (CompilationFailedException e) {
                    return FormValidation.error("Check if quotes, braces, or brackets are balanced. " + e.getMessage());
                }

                if (s != null) {
                    String errorMessage = isInvalidTag(s);

                    if (errorMessage != null) {
                        return FormValidation.error(errorMessage);
                    }
                }
            }
            return FormValidation.ok();
        }

        /**
         * Checks if the given value is a valid CVS tag.
         * <p/>
         * If it's invalid, this method gives you the reason as string.
         *
         * @param tagName the tag name
         * @return the error message, or null if tag is valid
         */
        private String isInvalidTag(String tagName) {
            // source code from CVS rcs.c
            //void
            //RCS_check_tag (tag)
            //    const char *tag;
            //{
            //    char *invalid = "$,.:;@";		/* invalid RCS tag characters */
            //    const char *cp;
            //
            //    /*
            //     * The first character must be an alphabetic letter. The remaining
            //     * characters cannot be non-visible graphic characters, and must not be
            //     * in the set of "invalid" RCS identifier characters.
            //     */
            //    if (isalpha ((unsigned char) *tag))
            //    {
            //    for (cp = tag; *cp; cp++)
            //    {
            //        if (!isgraph ((unsigned char) *cp))
            //        error (1, 0, "tag `%s' has non-visible graphic characters",
            //               tag);
            //        if (strchr (invalid, *cp))
            //        error (1, 0, "tag `%s' must not contain the characters `%s'",
            //               tag, invalid);
            //    }
            //    }
            //    else
            //    error (1, 0, "tag `%s' must start with a letter", tag);
            //}
            if (tagName == null || tagName.length() == 0) {
                return Messages.CVSSCM_TagIsEmpty();
            }

            char ch = tagName.charAt(0);
            if (!(('A' <= ch && ch <= 'Z') || ('a' <= ch && ch <= 'z'))) {
                return Messages.CVSSCM_TagNeedsToStartWithAlphabet();
            }

            for (char invalid : "$,.:;@".toCharArray()) {
                if (tagName.indexOf(invalid) >= 0) {
                    return Messages.CVSSCM_TagContainsIllegalChar(invalid);
                }
            }

            return null;
        }
    }
}
