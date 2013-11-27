package net.masterthought.jenkins;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.slaves.SlaveComputer;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.FormValidation;
import net.masterthought.cucumber.ReportBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.DirectoryScanner;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CucumberReportPublisher extends Recorder {

    public final String jsonReportDirectory;
    public final String pluginUrlPath;
    public final boolean skippedFails;
    public final boolean undefinedFails;
    public final boolean noFlashCharts;
    public final boolean ignoreFailedTests;

    @DataBoundConstructor
    public CucumberReportPublisher(String jsonReportDirectory, String pluginUrlPath, boolean skippedFails, boolean undefinedFails, boolean noFlashCharts, boolean ignoreFailedTests) {
        this.jsonReportDirectory = jsonReportDirectory;
        this.pluginUrlPath = pluginUrlPath;
        this.skippedFails = skippedFails;
        this.undefinedFails = undefinedFails;
        this.noFlashCharts = noFlashCharts;
        this.ignoreFailedTests = ignoreFailedTests;
    }

    private String[] findJsonFiles(File targetDirectory) {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setIncludes(new String[]{"**/*.json"});
        scanner.setBasedir(targetDirectory);
        scanner.scan();
        return scanner.getIncludedFiles();
    }

    private String[] findPngFiles(File targetDirectory) {
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setIncludes(new String[]{"**/*.png"});
        scanner.setBasedir(targetDirectory);
        scanner.scan();
        return scanner.getIncludedFiles();
    }
    

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
    throws IOException, InterruptedException {

        listener.getLogger().println("[CucumberReportPublisher] Compiling Cucumber Html Reports ...");

        File workspaceJsonReportDirectory = new File(build.getWorkspace().toURI().getPath());
        if (!jsonReportDirectory.isEmpty()) {
            workspaceJsonReportDirectory = new File(build.getWorkspace().toURI().getPath(), jsonReportDirectory);
        }
        File targetBuildDirectory = new File(build.getRootDir(), "cucumber-html-reports");

        String buildNumber = Integer.toString(build.getNumber());
        String buildProject = build.getProject().getName();

        if (!targetBuildDirectory.exists()) {
            targetBuildDirectory.mkdirs();
        }

        // if we are on a slave
        /*


    Check build folder / workspace flow


        */
    if (Computer.currentComputer() instanceof SlaveComputer) {
        listener.getLogger().println("[CucumberReportPublisher] detected this build is running on a slave ");
        FilePath projectWorkspaceOnSlave = build.getProject().getSomeWorkspace();
        FilePath masterDirectory = new FilePath(targetBuildDirectory);
        listener.getLogger().println("[CucumberReportPublisher] copying json from: " + projectWorkspaceOnSlave.toURI() + "to reports directory: " + masterDirectory.toURI());
        projectWorkspaceOnSlave.copyRecursiveTo("**/*.json", "", masterDirectory);

        listener.getLogger().println("[CucumberReportPublisher] copying images from: " + projectWorkspaceOnSlave.toURI() + "to reports directory: " + masterDirectory.toURI());
        projectWorkspaceOnSlave.copyRecursiveTo("**/*.png", "", masterDirectory);
    } else {
            // if we are on the master
        listener.getLogger().println("[CucumberReportPublisher] detected this build is running on the master ");
        String[] files = findJsonFiles(workspaceJsonReportDirectory);

        if (files.length != 0) {
            listener.getLogger().println("[CucumberReportPublisher] copying json to reports directory: " + targetBuildDirectory);
            for (String file : files) {
                FileUtils.copyFile(new File(workspaceJsonReportDirectory.getPath() + "/" + file), new File(targetBuildDirectory, file));
            }
        } else {
            listener.getLogger().println("[CucumberReportPublisher] there were no json results found in: " + workspaceJsonReportDirectory);
        }
    }

        // generate the reports from the targetBuildDirectory
    Result result = Result.NOT_BUILT;
    String[] jsonReportFiles = findJsonFiles(targetBuildDirectory);
    String[] pngFiles = findPngFiles(targetBuildDirectory);

    if (jsonReportFiles.length != 0) {

        listener.getLogger().println("[CucumberReportPublisher] Found the following number of json files: " + jsonReportFiles.length);
        int jsonIndex = 0;
        for (String jsonReportFile : jsonReportFiles) {
            listener.getLogger().println("[CucumberReportPublisher] " + jsonIndex + ". Found a json file: " + jsonReportFile);
            jsonIndex++;
        }


        listener.getLogger().println("[CucumberReportPublisher] Found the following number of png images: " + pngFiles.length);
        int pngIndex = 0;
        for (String pngFile : pngFiles) {
            listener.getLogger().println("[CucumberReportPublisher] " + pngIndex + ". Found a png file: " + pngFile);
            pngIndex++;
        }


        listener.getLogger().println("[CucumberReportPublisher] Generating HTML reports");

        try {
            ReportBuilder reportBuilder = new ReportBuilder(
                fullPathToJsonFiles(jsonReportFiles, targetBuildDirectory),
                targetBuildDirectory,
                pluginUrlPath,
                buildNumber,
                buildProject,
                skippedFails,
                undefinedFails,
                !noFlashCharts,
                true,
                false,
                "",
                false);
            reportBuilder.generateReports();

            boolean buildSuccess = reportBuilder.getBuildStatus();

            if (buildSuccess)
            {
             result = Result.SUCCESS;
         }
         else
         {
             result = ignoreFailedTests ? Result.UNSTABLE : Result.FAILURE;
         }

     } catch (Exception e) {
        e.printStackTrace();
        result = Result.FAILURE;
        listener.getLogger().println("[CucumberReportPublisher] there was an error generating the reports: " + e);
        for(StackTraceElement error : e.getStackTrace()){
           listener.getLogger().println(error);
       }
   }
} else {
   result = Result.SUCCESS;
   listener.getLogger().println("[CucumberReportPublisher] there were no json results found in: " + targetBuildDirectory);
}

build.addAction(new CucumberReportBuildAction(build));
build.setResult(result);

return true;
}

private List<String> fullPathToJsonFiles(String[] jsonFiles, File targetBuildDirectory) {
    List<String> fullPathList = new ArrayList<String>();
    for (String file : jsonFiles) {
        String absolutePath = new File(targetBuildDirectory, file).getAbsolutePath();
        fullPathList.add(absolutePath);
    }
    return fullPathList;
}

@Override
public Action getProjectAction(AbstractProject<?, ?> project) {
    return new CucumberReportProjectAction(project);
}

@Extension
public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    @Override
    public String getDisplayName() {
        return "Publish cucumber results as a report";
    }


        // Performs on-the-fly validation on the file mask wildcard.
    public FormValidation doCheck(@AncestorInPath AbstractProject project,
      @QueryParameter String value) throws IOException, ServletException {
        FilePath ws = project.getSomeWorkspace();
        return ws != null ? ws.validateRelativeDirectory(value) : FormValidation.ok();
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> jobType) {
        return true;
    }
}

public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
}
}
