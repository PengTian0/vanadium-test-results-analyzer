package io.v.jenkins.plugins.vanadium_test_results_analyzer;
import org.jenkinsci.plugins.workflow.steps.AbstractSynchronousNonBlockingStepExecution;
import hudson.Extension;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import com.google.inject.Inject;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.BuildListener;

public class VTestResultsAnalyzerStepExecution extends AbstractSynchronousNonBlockingStepExecution<Void> {

    @StepContextParameter
    private transient BuildListener listener;

    @StepContextParameter
    private transient Run<?,?> build;

    @StepContextParameter
    private transient Launcher launcher;

    @Inject
    private transient VTestResultsAnalyzerStep step;
    

    @Override
    protected Void run() throws Exception {
        System.out.println("Running vanadium test results analyzer step");
        VTestResultsAnalyzerPublisher vrap = new VTestResultsAnalyzerPublisher(step.getSendJenkinsBuildResults(), step.getSendTestResults());
        vrap.perform(build, launcher, listener);
        
        return null;
    }

}

