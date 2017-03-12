package io.v.jenkins.plugins.vanadium_test_results_analyzer;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
//import org.kohsuke.stapler.DataBoundSetter;
import org.jenkinsci.Symbol;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.AbstractProject;
import hudson.matrix.MatrixRun;
import hudson.FilePath;
import hudson.tasks.Publisher;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TabulatedResult;
import hudson.tasks.test.TestResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.io.IOException;
import jenkins.tasks.SimpleBuildStep;
import io.v.jenkins.plugins.vanadium_test_results_analyzer.TestResultsSender.TestResultsSenderEventHandler;

public class VTestResultsAnalyzerStep extends Recorder implements SimpleBuildStep {

    private static final int TEST_RESULT_SENDER_THREAD_POOL_SIZE = 32;
    private static final int TEST_RESULT_SENDER_THREAD_POOL_WAIT_TIMEOUT_MIN = 15;

    private static final Logger LOGGER =
      Logger.getLogger(VTestResultsAnalyzerPublisher.class.getName());


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
 
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }
  
    @Override
    public void perform(Run<?,?> build, FilePath workspace, Launcher launcher,
            TaskListener listener) throws InterruptedException, IOException {
        VTestResultsAnalyzerPluginImpl plugin = VTestResultsAnalyzerPluginImpl.getInstance();
        if(plugin.getPluginDisabled()) {
            Util.logToConsole(
            listener.getLogger(), "Plugin is disabled globally. Not sending build/test results.");
        }

        String ip = plugin.getServerIP();
        String password = plugin.getRootPassword();
        if (sendJenkinsBuildResults) {
            doSendJenkinsResults(build, ip, password, listener);
        } else {
            Util.logToConsole(
            listener.getLogger(), "Not sending jenkins build results (function disabled).\n");
        }
        if (sendTestResults) {
            doSendTestResults(build, ip, password, listener);
        } else {
            Util.logToConsole(listener.getLogger(), "Not sending test results (function disabled).\n");
        }
    }

    private void doSendJenkinsResults(
      Run<?,?> build, String ip, String password, TaskListener listener)
      throws InterruptedException, IOException {
        Connection conn = null;
        try {
            conn = Util.getConnection(ip, password, VTestResultsAnalyzerMgmtLink.DB_NAME);
            Util.logToConsole(listener.getLogger(), "Sending jenkins build stats. Please wait.\n");
            String sqlInsert =
            String.format(
              "INSERT INTO "
                  + VTestResultsAnalyzerMgmtLink.TB_JENKINS_BUILDS
                  + "(jenkins_project, build_number, sub_build_labels, node, "
                  + "start_time, duration, result, url, update_time) VALUES "
                  + "(?,?,?,?,?,?,?,?,?)");
            PreparedStatement stmt = conn.prepareStatement(sqlInsert);
            String project_name = StringUtils.strip(build.getFullDisplayName().split("#")[0]);
            stmt.setString(1,project_name);
            stmt.setInt(2, build.getNumber()); // build_number
            if (build instanceof MatrixRun) {
                stmt.setString(3, build.getParent().getName()); // sub build labels for sub builds.
            } else {
                stmt.setString(3, null); // null for root build.
            }
            stmt.setString(4,null);
            stmt.setTimestamp(5, new Timestamp(build.getStartTimeInMillis())); // start_time
            long curMs = System.currentTimeMillis();
            stmt.setInt(6, (int) ((curMs - build.getStartTimeInMillis()) / 1000));
            stmt.setString(7, build.getResult().toString());
            stmt.setString(8, build.getUrl());
            stmt.setTimestamp(9, new Timestamp(curMs));
            stmt.executeUpdate();
            Util.logToConsole(listener.getLogger(), "Build stats sent.\n");
        }catch (SQLException e) {
            Util.logToConsole(
            listener.getLogger(), "Failed to send build stats to Vanadium Test Results Analyzer.\n");
            e.printStackTrace(listener.getLogger());
            return;
        }finally {
            if(conn != null) {
                try{
                    conn.close();
                }catch (SQLException e) {
                    e.printStackTrace(listener.getLogger());
                }
            }
        }
    }

  private void doSendTestResults(
      Run<?,?> build, String ip, String password, final TaskListener listener)
      throws InterruptedException, IOException {

      Util.logToConsole(listener.getLogger(), "Sending test results. Please wait.\n");

      long startMs = System.currentTimeMillis();
      final List<TestResultsSender> senders = new ArrayList<>();
      List<AbstractTestResultAction> testActions = build.getActions(AbstractTestResultAction.class);
      int numTestCases = 0;
      for (AbstractTestResultAction testAction : testActions) {
          TabulatedResult testResult = (TabulatedResult) testAction.getResult();
          Collection<? extends TestResult> packageResults = testResult.getChildren();
          for (TestResult packageResult : packageResults) {
              Collection<? extends TestResult> classResults =
              ((TabulatedResult) packageResult).getChildren();
              for (final TestResult classResult : classResults) {
                  numTestCases += ((TabulatedResult) classResult).getChildren().size();
                  TestResultsSender sender =
                      new TestResultsSender(
                          ip,
                          password,
                          packageResult,
                          (TabulatedResult) classResult,
                          build,
                          new TestResultsSenderEventHandler() {
                          @Override
                          public void onFinish(String errMsg) {
                              // Show errors.
                              if (!errMsg.isEmpty()) {
                                  String msg =
                                  classResult.getFullDisplayName() + ": FAILED!\n" + errMsg + "\n";
                                  Util.logToConsole(listener.getLogger(), msg);
                              }
                          }
                      });
                   senders.add(sender);
              }
          }
      }
      // Run senders in a worker pool.
      ExecutorService executor = Executors.newFixedThreadPool(TEST_RESULT_SENDER_THREAD_POOL_SIZE);
      for (Runnable r : senders) {
          executor.execute(r);
      }
      executor.shutdown();
      executor.awaitTermination(TEST_RESULT_SENDER_THREAD_POOL_WAIT_TIMEOUT_MIN, TimeUnit.MINUTES);
      Util.logToConsole(
          listener.getLogger(),
          String.format(
              "%d test results sent. Took %.1f seconds.\n",
              numTestCases, (System.currentTimeMillis() - startMs) / 1000.0));
    }    


    @Extension @Symbol("VTestResultsAnalyzer")
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher>{
        @Override
        public String getDisplayName() {
            return "vanadium test results analyzer.";
        }
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
