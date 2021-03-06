package org.jenkinsci.plugins.pretestedintegration.scm.git;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Plugin;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.*;
import hudson.plugins.git.extensions.GitClientType;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import hudson.plugins.git.util.GitUtils;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.gitclient.ChangelogCommand;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.jenkinsci.plugins.gitclient.MergeCommand;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategy;
import org.jenkinsci.plugins.pretestedintegration.IntegrationStrategyDescriptor;
import org.jenkinsci.plugins.pretestedintegration.exceptions.EstablishingWorkspaceFailedException;
import org.jenkinsci.plugins.pretestedintegration.exceptions.IntegrationFailedException;
import org.jenkinsci.plugins.pretestedintegration.exceptions.NothingToDoException;
import org.jenkinsci.plugins.pretestedintegration.exceptions.UnsupportedConfigurationException;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;

import static org.eclipse.jgit.lib.Constants.HEAD;

/**
 * The Pretested Integration Plugin - automating The Phlow for git in Jenkins - as a Git Plugin Extension
 */
public class PretestedIntegrationAsGitPluginExt extends GitSCMExtension {
    final static String LOG_PREFIX = "[PREINT] ";
    private static final Logger LOGGER = Logger.getLogger(PretestedIntegrationAsGitPluginExt.class.getName());
    public String integrationBranch = "master";
    public String repoName;
    public IntegrationStrategy gitIntegrationStrategy;


    /**
     * Constructor for GitBridge.
     * DataBound for use in the UI.
     *
     * @param gitIntegrationStrategy The selected IntegrationStrategy
     * @param integrationBranch      The Integration Branch name
     * @param repoName               The Integration Repository name
     */
    @DataBoundConstructor
    public PretestedIntegrationAsGitPluginExt(IntegrationStrategy gitIntegrationStrategy, String integrationBranch, String repoName) {
        this.integrationBranch = integrationBranch;
        this.repoName = repoName;
        this.gitIntegrationStrategy = gitIntegrationStrategy;

    }

    public IntegrationStrategy getGitIntegrationStrategy() {
        return this.gitIntegrationStrategy;
    }


    public String getIntegrationBranch() {
        return this.integrationBranch;
    }


    public GitBridge getGitBridge() {
        return new GitBridge(gitIntegrationStrategy, integrationBranch, repoName);
    }

    /**
     * @return the plugin version
     */
    public String getVersion() {
        Plugin pretested = Jenkins.getActiveInstance().getPlugin("pretested-integration");
        if (pretested != null) return pretested.getWrapper().getVersion();
        else return "unable to retrieve plugin version";
    }

    @Override
    public Revision decorateRevisionToBuild(
            GitSCM scm,
            Run<?, ?> run,
            GitClient git,
            TaskListener listener,
            Revision marked,
            Revision triggeredRevision) throws IOException, InterruptedException {
        listener.getLogger().println(String.format("%s Pretested Integration Plugin v%s", LOG_PREFIX, getVersion()));

        GitBridge gitBridge = getGitBridge();

        EnvVars environment = run.getEnvironment(listener);

        // TODO: Should this be last branch in stead of?
        Branch triggeredBranch = triggeredRevision.getBranches().iterator().next();
        String expandedIntegrationBranch = gitBridge.getExpandedIntegrationBranch(environment);
        String expandedRepo = gitBridge.getExpandedRepository(environment);
        String ucCredentialsId = "";

        
        
        for (UserRemoteConfig uc : scm.getUserRemoteConfigs()) {
            String credentialsRepoName = StringUtils.isBlank(uc.getName()) ? "origin" : uc.getName();
            if (credentialsRepoName != null && credentialsRepoName.equals(expandedRepo)) {
                String ucCred = uc.getCredentialsId();
                if (ucCred != null) {
                    ucCredentialsId = uc.getCredentialsId();
                }
            }
        }

        try {
            gitBridge.evalBranchConfigurations(triggeredBranch, expandedIntegrationBranch, expandedRepo);
            listener.getLogger().println(String.format(LOG_PREFIX + "Checking out integration branch %s:", expandedIntegrationBranch));
            git.checkout().branch(expandedIntegrationBranch).ref(expandedRepo + "/" + expandedIntegrationBranch).deleteBranchIfExist(true).execute();
            ((GitIntegrationStrategy) gitBridge.integrationStrategy).integrateAsGitPluginExt(scm, run, git, listener, marked, triggeredRevision, gitBridge);


        } catch (NothingToDoException e) {
            run.setResult(Result.NOT_BUILT);
            String logMessage = LOG_PREFIX + String.format("%s - setUp() - NothingToDoException - %s", LOG_PREFIX, e.getMessage());
            listener.getLogger().println(logMessage);
            LOGGER.log(Level.SEVERE, logMessage, e);
            git.checkout().branch(expandedIntegrationBranch).ref(expandedRepo + "/" + expandedIntegrationBranch).deleteBranchIfExist(true).execute();
        } catch (IntegrationFailedException | EstablishingWorkspaceFailedException | UnsupportedConfigurationException e) {
            run.setResult(Result.FAILURE);
            String logMessage = String.format("%s - setUp() - %s - %s", LOG_PREFIX, e.getClass().getSimpleName(), e.getMessage());
            listener.getLogger().println(logMessage);
            LOGGER.log(Level.SEVERE, logMessage, e);
            git.checkout().branch(expandedIntegrationBranch).ref(expandedRepo + "/" + expandedIntegrationBranch).deleteBranchIfExist(true).execute();
        } catch (IOException | InterruptedException e) {
            run.setResult(Result.FAILURE);
            String logMessage = String.format("%s - Unexpected error. %n%s", LOG_PREFIX, e.getMessage());
            LOGGER.log(Level.SEVERE, logMessage, e);
            listener.getLogger().println(logMessage);
            e.printStackTrace(listener.getLogger());
            git.checkout().branch(expandedIntegrationBranch).ref(expandedRepo + "/" + expandedIntegrationBranch).deleteBranchIfExist(true).execute();
        }

        run.addAction(new PretestTriggerCommitAction(triggeredBranch, expandedIntegrationBranch, expandedRepo, ucCredentialsId));
        if (run.getResult() == null || run.getResult() == Result.SUCCESS) {
            Revision mergeRevision = new GitUtils(listener, git).getRevisionForSHA1(git.revParse(HEAD));

            return mergeRevision;
        } else {
            // We could not integrate, but we must return a revision for recording it so it does not retrigger
            git.checkout().ref(triggeredBranch.getName()).execute();
            return triggeredRevision;
        }
    }

    @Override
    public void decorateMergeCommand(GitSCM scm, Run<?, ?> build, GitClient git, TaskListener listener, MergeCommand cmd) throws IOException, InterruptedException, GitException {
    }

    @Override
    public GitClientType getRequiredClient() {
        return GitClientType.GITCLI;
    }

    @Symbol("pretestedIntegration")
    @Extension
    public static class DescriptorImpl extends GitSCMExtensionDescriptor {

        @Override
        public String getDisplayName() {
            return "Use pretested integration";
        }

        public List<IntegrationStrategyDescriptor<?>> getIntegrationStrategies() {
            List<IntegrationStrategyDescriptor<?>> list = new ArrayList<>();
            for (IntegrationStrategyDescriptor<?> descr : IntegrationStrategy.all()) {
                list.add(descr);
            }
            return list;
        }

        /**
         * @return The default Integration Strategy
         */
        public IntegrationStrategy getDefaultStrategy() {
            return new SquashCommitStrategy();
        }
    }
}
