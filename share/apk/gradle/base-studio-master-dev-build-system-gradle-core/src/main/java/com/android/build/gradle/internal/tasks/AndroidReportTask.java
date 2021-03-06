/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.internal.tasks;

import static com.android.builder.core.BuilderConstants.CONNECTED;
import static com.android.builder.core.BuilderConstants.DEVICE;
import static com.android.builder.core.BuilderConstants.FD_ANDROID_RESULTS;
import static com.android.builder.core.BuilderConstants.FD_ANDROID_TESTS;
import static com.android.builder.core.BuilderConstants.FD_FLAVORS_ALL;

import com.android.annotations.NonNull;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.tasks.factory.TaskCreationAction;
import com.android.build.gradle.internal.test.report.ReportType;
import com.android.build.gradle.internal.test.report.TestReport;
import com.android.builder.core.VariantType;
import com.android.utils.FileUtils;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.logging.ConsoleRenderer;

/**
 * Task doing test report aggregation.
 */
public class AndroidReportTask extends DefaultTask implements AndroidTestTask {

    private final List<AndroidTestTask> subTasks = Lists.newArrayList();

    private ReportType reportType;

    private boolean ignoreFailures;

    private boolean testFailed;

    private Supplier<File> reportsDir;
    private Supplier<File> resultsDir;

    @OutputDirectory
    public File getReportsDir() {
        return reportsDir.get();
    }

    public void setReportsDir(@NonNull Supplier<File> reportsDir) {
        this.reportsDir = TaskInputHelper.memoize(reportsDir);
    }

    @Override
    @OutputDirectory
    public File getResultsDir() {
        return resultsDir.get();
    }

    public void setResultsDir(@NonNull Supplier<File> resultsDir) {
        this.resultsDir = TaskInputHelper.memoize(resultsDir);
    }

    @Override
    public boolean getTestFailed() {
        return testFailed;
    }

    @Override
    public boolean getIgnoreFailures() {
        return ignoreFailures;
    }

    @Override
    public void setIgnoreFailures(boolean ignoreFailures) {
        this.ignoreFailures = ignoreFailures;
    }

    public ReportType getReportType() {
        return reportType;
    }

    public void setReportType(ReportType reportType) {
        this.reportType = reportType;
    }

    public void addTask(AndroidTestTask task) {
        subTasks.add(task);
        this.dependsOn(task);
    }

    @InputFiles
    public List<File> getResultsDirectories() {
        return subTasks.stream().map(AndroidTestTask::getResultsDir).collect(Collectors.toList());
    }

    /**
     * Sets that this current task will run and therefore needs to tell its children
     * class to not stop on failures.
     */
    public void setWillRun() {
        for (AndroidTestTask task : subTasks) {
            task.setIgnoreFailures(true);
        }
    }

    @TaskAction
    public void createReport() throws IOException {
        File resultsOutDir = resultsDir.get();
        File reportOutDir = reportsDir.get();

        // empty the folders
        FileUtils.cleanOutputDir(resultsOutDir);
        FileUtils.cleanOutputDir(reportOutDir);

        // do the copy.
        copyResults(resultsOutDir);

        // create the report.
        TestReport report = new TestReport(reportType, resultsOutDir, reportOutDir);
        report.generateReport();

        // fail if any of the tasks failed.
        for (AndroidTestTask task : subTasks) {
            if (task.getTestFailed()) {
                testFailed = true;
                String reportUrl = new ConsoleRenderer().asClickableFileUrl(
                        new File(reportOutDir, "index.html"));
                String message = "There were failing tests. See the report at: " + reportUrl;

                if (getIgnoreFailures()) {
                    getLogger().warn(message);
                } else {
                    throw new GradleException(message);
                }

                break;
            }
        }
    }

    private void copyResults(File reportOutDir) throws IOException {
        List<File> resultDirectories = getResultsDirectories();

        for (File directory : resultDirectories) {
            FileUtils.copyDirectory(directory, reportOutDir);
        }
    }

    public static class CreationAction extends TaskCreationAction<AndroidReportTask> {

        public enum TaskKind { CONNECTED, DEVICE_PROVIDER }

        private final GlobalScope scope;

        private final TaskKind taskKind;

        public CreationAction(@NonNull GlobalScope scope, @NonNull TaskKind taskKind) {
            this.scope = scope;
            this.taskKind = taskKind;
        }

        @NonNull
        @Override
        public String getName() {
            return (taskKind == TaskKind.CONNECTED ? CONNECTED : DEVICE)
                    + VariantType.ANDROID_TEST_SUFFIX;
        }

        @NonNull
        @Override
        public Class<AndroidReportTask> getType() {
            return AndroidReportTask.class;
        }

        @Override
        public void configure(@NonNull AndroidReportTask task) {

            task.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
            task.setDescription((taskKind == TaskKind.CONNECTED) ?
                    "Installs and runs instrumentation tests for all flavors on connected devices.":
                    "Installs and runs instrumentation tests using all Device Providers.");
            task.setReportType(ReportType.MULTI_FLAVOR);


            final String defaultReportsDir = scope.getReportsDir().getAbsolutePath()
                    + "/" + FD_ANDROID_TESTS;
            final String defaultResultsDir = scope.getOutputsDir().getAbsolutePath()
                    + "/" + FD_ANDROID_RESULTS;

            final String subfolderName =
                    taskKind == TaskKind.CONNECTED ? "/connected/" : "/devices/";

            task.resultsDir = TaskInputHelper.memoize(() -> {
                        String dir = scope.getExtension().getTestOptions().getResultsDir();
                        String rootLocation = dir != null && !dir.isEmpty() ?
                                dir : defaultResultsDir;
                        return scope.getProject().file(
                                rootLocation + subfolderName + FD_FLAVORS_ALL);
                    });

            task.reportsDir = TaskInputHelper.memoize(() -> {
                String dir = scope.getExtension().getTestOptions().getReportDir();
                String rootLocation = dir != null && !dir.isEmpty() ? dir : defaultReportsDir;
                return scope.getProject()
                        .file(rootLocation + subfolderName + FD_FLAVORS_ALL);
            });
        }
    }
}

