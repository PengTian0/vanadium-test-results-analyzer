package io.v.jenkins.plugins.vanadium_test_results_analyzer;

import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import javax.annotation.Nonnull;

public class VTestResultsAnalyzerStep extends AbstractStepImpl {

    private boolean sendJenkinsBuildResults;
    private boolean sendTestResults;   

    @DataBoundConstructor
    public VTestResultsAnalyzerStep(boolean sendJenkinsBuildResults, boolean sendTestResults){
        this.sendJenkinsBuildResults = sendJenkinsBuildResults;
        this.sendTestResults = sendTestResults;
    }

    public boolean getSendJenkinsBuildResults() {
        return sendJenkinsBuildResults;
    }

    public boolean getSendTestResults() {
        return sendTestResults;
    }
   

    @Extension
    public static class DescriptorImpl extends AbstractStepDescriptorImpl {
        public DescriptorImpl() { super(VTestResultsAnalyzerStepExecution.class); }

        @Override
        public String getFunctionName() {
            return "VTestResultsAnalyzer";
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "vanadium test results analyzer.";
        }
    }
}
