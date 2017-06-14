package com.hpe.application.automation.tools.run;

import com.hpe.application.automation.tools.results.lrscriptresultparser.LrScriptHtmlReportAction;
import com.hpe.application.automation.tools.results.lrscriptresultparser.LrScriptResultsSanitizer;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.tasks.junit.JUnitResultArchiver;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import jenkins.util.VirtualFile;
import net.sf.json.JSONObject;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Created by kazaky on 14/03/2017.
 */

/**
 * This step enables to run LoadRunner scripts directly and collecting their results by converting them to JUnit
 */
public class RunLRScript extends Builder implements SimpleBuildStep {
    public static final String LR_SCRIPT_HTML_REPORT_CSS = "PResults.css";
    private static final String LINUX_MDRV_PATH = "/bin/mdrv";
    private static final String WIN_MDRV_PATH = "\\bin\\mmdrv.exe";
    private static final String LR_SCRIPT_HTML_XSLT = "PDetails.xsl";
    private static final String LR_SCRIPT_HTML_CSS = "LR_SCRIPT_REPORT.css";
    private final String lrScriptPath;
    private Jenkins jenkinsInstance;
    private PrintStream logger;

    @DataBoundConstructor
    public RunLRScript(String scriptsPath) {
        this.lrScriptPath = scriptsPath;
    }


    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher,
                        @Nonnull TaskListener listener) throws InterruptedException, IOException {
        try {
            jenkinsInstance = Jenkins.getInstance();
            logger = listener.getLogger();
            ArgumentListBuilder args = new ArgumentListBuilder();
            EnvVars env;
            String scriptName = FilenameUtils.getBaseName(this.lrScriptPath);
            FilePath buildWorkDir = workspace.child(build.getId());
            buildWorkDir.mkdirs();
            buildWorkDir = buildWorkDir.absolutize();
            env = build.getEnvironment(listener);
            FilePath scriptPath = workspace.child(env.expand(this.lrScriptPath));
            FilePath scriptWorkDir = buildWorkDir.child(scriptName);
            scriptWorkDir.mkdirs();
            scriptWorkDir = scriptWorkDir.absolutize();


            if (runScriptMdrv(launcher, args, env, scriptPath, scriptWorkDir)) {
                build.setResult(Result.FAILURE);
                return;
            }

            final VirtualFile root = build.getArtifactManager().root();

            File masterBuildWorkspace = new File(new File(root.toURI()), "LRReport");
            if (!masterBuildWorkspace.exists()) {
                if (!root.exists()) {
                    (new File(root.toURI())).mkdirs();
                }
                masterBuildWorkspace.mkdirs();
            }

            FilePath outputHTML = buildWorkDir.child(scriptName);
            outputHTML.mkdirs();
            outputHTML = outputHTML.child("result.html");
            FilePath xsltOnNode = copyXsltToNode(workspace);
            createHtmlReports(buildWorkDir, scriptName, outputHTML, xsltOnNode);
            LrScriptResultsParser lrScriptResultsParser = new LrScriptResultsParser(listener);
            lrScriptResultsParser.parseScriptResult(scriptName, buildWorkDir);
            copyScriptsResultToMaster(build, listener, buildWorkDir, new FilePath(masterBuildWorkspace));
            parseJunitResult(build, launcher, listener, buildWorkDir, scriptName);
            addLrScriptHtmlReportAcrion(build, scriptName);

            build.setResult(Result.SUCCESS);

        } catch (IllegalArgumentException e) {
            build.setResult(Result.FAILURE);
            logger.println(e);
        } catch (IOException | InterruptedException e) {
            listener.error("Failed loading build environment " + e);
            build.setResult(Result.FAILURE);
        } catch (XMLStreamException e) {
            listener.error(e.getMessage(), e);
            build.setResult(Result.FAILURE);
        }
    }

    private FilePath copyXsltToNode(@Nonnull FilePath workspace) throws IOException, InterruptedException {
        final URL xsltPath = jenkinsInstance.pluginManager.uberClassLoader.getResource(LR_SCRIPT_HTML_XSLT);
        logger.println("loading XSLT from " + xsltPath.getFile());
        FilePath xsltOnNode = workspace.child("resultsHtml.xslt");
        if (!xsltOnNode.exists()) {
            xsltOnNode.copyFrom(xsltPath);
        }
        return xsltOnNode;
    }

    private boolean runScriptMdrv(@Nonnull Launcher launcher, ArgumentListBuilder args,
                                  EnvVars env, FilePath scriptPath, FilePath scriptWorkDir)
            throws IOException, InterruptedException {
        FilePath mdrv;
        //base command line mmdrv.exe -usr "%1\%1.usr" -extra_ext NVReportExt -qt_result_dir
        // "c:\%1_results"
        //Do run the script on linux or windows?
        mdrv = getMDRVPath(launcher, env);
        args.add(mdrv);
        args.add("-usr");
        args.add(scriptPath);
        args.add("-extra_ext NVReportExt");
        args.add("-qt_result_dir");
        args.add(scriptWorkDir);

        int returnCode = launcher.launch().cmds(args).stdout(logger).pwd(scriptWorkDir).join();
        return returnCode != 0;
    }

    private static FilePath getMDRVPath(@Nonnull Launcher launcher, EnvVars env) {
        FilePath mdrv;
        if (launcher.isUnix()) {
            String lrPath = env.get("M_LROOT", "");
            if ("".equals(lrPath)) {
                throw new LrScriptParserException(
                        "Please make sure environment variables are set correctly on the running node - " +
                                "LR_PATH for windows and M_LROOT for linux");
            }
            lrPath += LINUX_MDRV_PATH;
            mdrv = new FilePath(launcher.getChannel(), lrPath);
        } else {
            String lrPath = env.get("LR_PATH", "");
            if ("".equals(lrPath)) {
                throw new LrScriptParserException("P1lease make sure environment variables are set correctly on the " +
                        "running node - " +
                        "LR_PATH for windows and M_LROOT for linux");
            }
            lrPath += WIN_MDRV_PATH;
            mdrv = new FilePath(launcher.getChannel(), lrPath);
        }
        return mdrv;
    }

    private void addLrScriptHtmlReportAcrion(@Nonnull Run<?, ?> build, String scriptName) {
        synchronized (build) {
            LrScriptHtmlReportAction action = build.getAction(LrScriptHtmlReportAction.class);
            if (action == null) {
                action = new LrScriptHtmlReportAction(build);
                action.mergeResult(build, scriptName);
                build.addAction(action);
            } else {
                action.mergeResult(build, scriptName);
            }
        }
    }

    private static void parseJunitResult(@Nonnull Run<?, ?> build, @Nonnull Launcher launcher, @Nonnull TaskListener
            listener,
                                         FilePath buildWorkDir, String scriptName)
            throws InterruptedException, IOException {
        JUnitResultArchiver jUnitResultArchiver = new JUnitResultArchiver("JunitResult.xml");
        jUnitResultArchiver.setKeepLongStdio(true);
        jUnitResultArchiver.setAllowEmptyResults(true);
        jUnitResultArchiver.perform(build, buildWorkDir.child(scriptName), launcher, listener);
    }

    private void createHtmlReports(FilePath buildWorkDir, String scriptName, FilePath outputHTML, FilePath xsltOnNode)
            throws IOException, InterruptedException, XMLStreamException {
        if (!buildWorkDir.exists()) {
            throw new IllegalArgumentException("Build worker doesn't exist");
        }
        if ("".equals(scriptName)) {
            throw new IllegalArgumentException("Script name is empty");
        }
        if (!xsltOnNode.exists()) {
            throw new IllegalArgumentException("LR Html report doesn't exist on the node");
        }
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            StreamSource xslStream = new StreamSource(xsltOnNode.read());
            Transformer transformer = factory.newTransformer(xslStream);

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.onMalformedInput(CodingErrorAction.REPLACE).replacement();

            final InputStreamReader inputStreamReader = new InputStreamReader(new BOMInputStream(buildWorkDir
                    .child(scriptName).child("Results.xml").read()), decoder);

            StreamSource in = new StreamSource(new LrScriptResultsSanitizer(inputStreamReader));
            StreamResult out = new StreamResult(outputHTML.write());
            transformer.transform(in, out);
            final URL lrHtmlCSSPath = jenkinsInstance.pluginManager.uberClassLoader.getResource(LR_SCRIPT_HTML_CSS);
            if (lrHtmlCSSPath == null) {
                throw new LrScriptParserException(
                        "For some reason the jenkins instance is null - is it an improper set tests?");
            }

            FilePath lrScriptHtmlReportCss = buildWorkDir.child(scriptName).child(LR_SCRIPT_HTML_REPORT_CSS);
            lrScriptHtmlReportCss.copyFrom(lrHtmlCSSPath);

            logger.println("The generated HTML file is:" + outputHTML);
        } catch (TransformerConfigurationException e) {
            logger.println("TransformerConfigurationException");
            logger.println(e);
        } catch (TransformerException e) {
            logger.println("TransformerException");
            logger.println(e);
        } catch (LrScriptParserException e) {
            logger.println("General exception");
            logger.println(e);
        }
    }

    private static void copyScriptsResultToMaster(@Nonnull Run<?, ?> build, @Nonnull TaskListener listener,
                                                  FilePath buildWorkDir, FilePath masterBuildWorkspace)
            throws IOException, InterruptedException {
        listener.getLogger().printf("Copying script results, from '%s' on '%s' to '%s' on the master. %n"
                , buildWorkDir.toURI(), Computer.currentComputer().getNode(), build.getRootDir().toURI());

        buildWorkDir.copyRecursiveTo(masterBuildWorkspace);
    }

    public String getScriptsPath() {
        return lrScriptPath;
    }

    @Symbol("RunLoadRunnerScript")
    @Extension
    public static class Descriptor extends BuildStepDescriptor<Builder> {

        public Descriptor() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Run LoadRunner script";
        }

        @Override

        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            formData.getString("scriptsPath");
            save();
            return super.configure(req, formData);
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

    }


    public static final class LrScriptParserException extends IllegalArgumentException {

        public LrScriptParserException(String s) {
            super(s);
        }

    }
}
