package hudson.plugins.cvs_tag;

import hudson.Plugin;
import hudson.tasks.BuildStep;


/**
 * @author Brendt Lucas
 */
public class PluginImpl extends Plugin
{
	public void start() throws Exception
	{
		BuildStep.PUBLISHERS.add(CvsTagPublisher.DESCRIPTOR);
	}
}
