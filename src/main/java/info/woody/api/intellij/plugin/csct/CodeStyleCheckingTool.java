package info.woody.api.intellij.plugin.csct;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import info.woody.api.intellij.plugin.csct.bean.CodeStyleCheckLineError;
import info.woody.api.intellij.plugin.csct.bean.CodeStyleCheckReport;
import info.woody.api.intellij.plugin.csct.bean.CodeStyleCheckSummaryData;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

import static info.woody.api.intellij.plugin.csct.bean.CodeStyleCheckDetailFileData.LINE_BREAK_TAG;
import static info.woody.api.intellij.plugin.csct.util.Const.LINE_SEPARATOR;
import static info.woody.api.intellij.plugin.csct.util.EditorUtils.openFileInEditor;
import static info.woody.api.intellij.plugin.csct.util.RichTextMaker.newHighlight;
import static info.woody.api.intellij.plugin.csct.util.RichTextMaker.newLink;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

/**
 * This action will be triggered by the menu. The tutorial could be found at below link.
 * <p>https://www.jetbrains.org/intellij/sdk/docs/welcome.html</p>
 *
 * @author Woody
 */
public class CodeStyleCheckingTool extends AnAction {

    public static final String SUMMARY_TEXT_PANE = "summaryTextPane";
    public static final String DETAILS_TEXT_PANE = "detailsTextPane";
    public static final String REPORT_INFO = "REPORT_INFO";
    private static final String ACTION_ID_SAVE_ALL = "SaveAll";
    private static final String DEFAULT_CONFIGURATION_XML = "DefaultConfiguration.xml";
    private static final String DEFAULT_MODULE_CONTENT = "tRetailAPI";

    @Override
    public void actionPerformed(AnActionEvent e) {
        Optional<ContentEntry> contentEntry = Optional.ofNullable(null);
        Optional<Module> module = Arrays.stream(ModuleManager.getInstance(e.getProject()).getModules())
                .filter(description -> description.getName().matches("^t-?RetailAPI$")).findAny();
        if (module.isPresent()) {
            contentEntry = Arrays.stream(ModuleRootManager.getInstance(module.orElse(null)).getContentEntries())
                    .filter(entry -> entry.getFile().getPath().endsWith(DEFAULT_MODULE_CONTENT)).findAny();
        }
        Path projectPath = Paths.get(e.getProject().getBaseDir().getPath());
        Path configurationFilePath = Paths.get(projectPath + "/CodeStyleCheckingTool.xml");
        File configurationFile = configurationFilePath.toFile();
        if (Files.notExists(configurationFilePath)) {
            int exitCode = Messages.showOkCancelDialog(
                    "The configuration file CodeStyleCheckingTool.xml isn't in project. Do you want to create now?",
                    "", Messages.getWarningIcon());
            if (Messages.OK == exitCode && createConfigurationFile(configurationFilePath)) {
                openFileInEditor(configurationFile.getAbsolutePath(), e.getProject());
            }
            return;
        }

        ActionManager.getInstance().getAction(ACTION_ID_SAVE_ALL).actionPerformed(e);

        String defaultModuleRootDir = contentEntry.map(ContentEntry::getFile).map(VirtualFile::getPath).orElse(null);
        CodeStyleCheckContext context = CodeStyleCheckContext.newInstance(configurationFile, defaultModuleRootDir);
        if (null == context) {
            showWarningMessage("The configuration file is corrupted. Please delete it!");
            return;
        }
        if (null == context.MY_SOURCE_DIR()) {
            showWarningMessage("The path configured in `SourceDir` is incorrectly configured.");
            openFileInEditor(configurationFile.getAbsolutePath(), e.getProject());
            return;
        }
        CodeStyleCheckImpl codeStyleCheck = new CodeStyleCheckImpl();
        codeStyleCheck.MY_SOURCE_DIR = context.MY_SOURCE_DIR();
        codeStyleCheck.FILENAME_PATTERN_TO_SKIP = context.FILENAME_PATTERN_TO_SKIP();
        codeStyleCheck.FILES_TO_SKIP = context.FILES_TO_SKIP();
        codeStyleCheck.GIT_FILES_TO_MERGE = context.GIT_FILES_TO_MERGE();
        CodeStyleCheckReport report = codeStyleCheck.doCheck().calculateStatistics();

        ToolWindow codeStyleCheckResultView = ToolWindowManager.getInstance(e.getProject()).getToolWindow("Code scanning results");
        JComponent rootComponent = codeStyleCheckResultView.getContentManager().getSelectedContent().getComponent();
        JTextPane summaryTextPane = (JTextPane) rootComponent.getClientProperty(SUMMARY_TEXT_PANE);
        JTextPane detailsTextPane = (JTextPane) rootComponent.getClientProperty(DETAILS_TEXT_PANE);
        rootComponent.putClientProperty(REPORT_INFO, report);

        // update the tool window
        summaryTextPane.setText(generateSummaryReport(report, configurationFile));
        summaryTextPane.setCaretPosition(0);
        detailsTextPane.setText(null);
        codeStyleCheckResultView.setTitle("Time: " + LocalDateTime.now());
        codeStyleCheckResultView.show(null);
    }

    private void showWarningMessage(String message) {
        Messages.showMessageDialog(message, "", Messages.getWarningIcon());
    }

    /**
     * Generate formatted report.
     *
     * @param report Report data.
     * @param configurationFile The configuration file.
     * @return The formatted report of HTML format.
     */
    private String generateSummaryReport(CodeStyleCheckReport report, File configurationFile) {
        StringBuilder summaryReportBuilder = new StringBuilder("<pre>");
        CodeStyleCheckSummaryData summaryData = report.getSummaryData();
        String authors = summaryData.getAuthorsKeySet().stream()
                //.sorted(author1, author2 -> report.getDetailDetailData().getMapAuthorsErrors().computeIfAbsent(author1, (v) -> 0))
                .map(author -> format("<span style='text-align:center'>%s delivered %d issues.</span>",
                        newLink("AUTHOR#" + author, author, author),
                        report.getDetailData().getMapAuthorsErrors().computeIfAbsent(author, (v) -> 0)))
                .collect(joining("<br>"));
        int fileCount = report.getFileCount();
        int fileCountWithIssues = summaryData.getFileCountWithIssues();
        int fileCountWithoutIssues = fileCount - fileCountWithIssues;
        int globalErrorCount = summaryData.getGlobalErrorsGroupByFilePath().entrySet().stream()
                .mapToInt(entry -> entry.getValue().size()).sum();
        Map<String, List<CodeStyleCheckLineError>> lineErrorsGroupByFilePath = summaryData.getLineErrorsGroupByFilePath();
        int lineErrorCount = lineErrorsGroupByFilePath.entrySet().stream()
                .mapToInt(entry -> entry.getValue().size()).sum();
        int errorCount = globalErrorCount + lineErrorCount;
        String configurationFileName = configurationFile.getName();
        summaryReportBuilder.append(String.format("The configuration file: %s",
                newLink(configurationFile.getAbsolutePath(), configurationFileName, configurationFileName)))
                .append(LINE_BREAK_TAG).append(format("Thanks for:<br>%s", authors))
                .append("<br>").append(format("%d issue(s) were found in %d file(s).", errorCount, fileCountWithIssues, fileCount))
                .append("<br>").append(format("Totally %d out of %d files were clear.", fileCountWithoutIssues, fileCount))
                .append("<br>").append(format("Cleanness rate is %,.2f%%.", (fileCountWithoutIssues + 0.0d) / fileCount * 100))
                .append("<hr>");

        summaryData.getFilesGroupByError().entrySet().stream().forEach(entry -> {
            String size = String.valueOf(entry.getValue().size());
            String error = entry.getKey();
            summaryReportBuilder.append("<br>").append(format("%s ERRORS FOR: %s",
                    newLink("ISSUE#" + error, error, size), newHighlight(error)));
        });
        return summaryReportBuilder.append("</pre>").toString();
    }

    /**
     * Create the configuration file as to the given path.
     *
     * @param configurationFilePath The configuration file path.
     * @return
     */
    private boolean createConfigurationFile(Path configurationFilePath) {
        try {
            File configurationFile = configurationFilePath.toFile();
            if (configurationFile.createNewFile()) {
                InputStream resourceAsStream = this.getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIGURATION_XML);
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(resourceAsStream));
                Files.write(configurationFilePath, bufferedReader.lines().collect(joining(LINE_SEPARATOR)).getBytes(UTF_8.name()));
                Messages.showMessageDialog("Configuration file is successfully created. Please try again.",
                        "", Messages.getInformationIcon());
                VirtualFileManager.getInstance().asyncRefresh(null);
                return true;
            } else {
                Messages.showMessageDialog("Configuration file is failed to create.", "", Messages.getErrorIcon());
            }
        } catch (IOException e) {
            Messages.showMessageDialog("Configuration file is failed to create.", "", Messages.getErrorIcon());
        }
        return false;
    }
}
