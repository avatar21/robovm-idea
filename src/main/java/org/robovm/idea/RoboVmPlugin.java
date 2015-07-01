/*
 * Copyright (C) 2015 RoboVM AB
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/gpl-2.0.html>.
 */
package org.robovm.idea;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.compiler.CompileTask;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.util.ui.UIUtil;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.robovm.compiler.Version;
import org.robovm.compiler.clazz.Path;
import org.robovm.compiler.config.Arch;
import org.robovm.compiler.config.Config;
import org.robovm.compiler.config.OS;
import org.robovm.compiler.config.Resource;
import org.robovm.compiler.log.Logger;
import org.robovm.compiler.target.ios.InfoPList;
import org.robovm.idea.compilation.RoboVmCompileTask;
import org.robovm.idea.interfacebuilder.RoboVmFileEditorManagerListener;
import org.robovm.idea.sdk.RoboVmSdkType;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Provides util for the other components of the plugin such
 * as logging.
 */
public class RoboVmPlugin {
    private static final String ROBOVM_TOOLWINDOW_ID = "RoboVM";
    static volatile Map<Project, ConsoleView> consoleViews = new ConcurrentHashMap<>();
    static volatile Map<Project, ToolWindow> toolWindows = new ConcurrentHashMap<>();
    static final List<UnprintedMessage> unprintedMessages = new ArrayList<UnprintedMessage>();

    static class UnprintedMessage {
        final String string;
        final ConsoleViewContentType type;

        public UnprintedMessage(String string, ConsoleViewContentType type) {
            this.string = string;
            this.type = type;
        }
    }

    public static void logBalloon(final Project project, final MessageType messageType, final String message) {
        UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {
                if (project != null) {
                    // this may throw an exception, see #88. It appears to be a timing
                    // issue
                    try {
                        ToolWindowManager.getInstance(project).notifyByBalloon(ROBOVM_TOOLWINDOW_ID, MessageType.ERROR, message);
                    } catch (Throwable t) {
                        logError(project, message, t);
                    }
                }
            }
        });
    }

    public static void logInfo(Project project, String format, Object... args) {
        log(project, ConsoleViewContentType.SYSTEM_OUTPUT, "[INFO] " + format, args);
    }

    public static void logError(Project project, String format, Object... args) {
        log(project, ConsoleViewContentType.ERROR_OUTPUT, "[ERROR] " + format, args);
    }

    public static void logErrorThrowable(Project project, String s, Throwable t, boolean showBalloon) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        t.printStackTrace(writer);
        log(project, ConsoleViewContentType.ERROR_OUTPUT, "[ERROR] %s\n%s", s, stringWriter.toString());
        logBalloon(project, MessageType.ERROR, s);
    }

    public static void logWarn(Project project, String format, Object... args) {
        log(project, ConsoleViewContentType.ERROR_OUTPUT, "[WARNING] " + format, args);
    }

    public static void logDebug(Project project, String format, Object... args) {
        log(project, ConsoleViewContentType.NORMAL_OUTPUT, "[DEBUG] " + format, args);
    }

    private static void log(final Project project, final ConsoleViewContentType type, String format, Object... args) {
        final String s = String.format(format, args) + "\n";
        UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {
                ConsoleView consoleView = project == null? null: consoleViews.get(project);
                if (consoleView != null) {
                    for (UnprintedMessage unprinted : unprintedMessages) {
                        consoleView.print(unprinted.string, unprinted.type);
                    }
                    unprintedMessages.clear();
                    consoleView.print(s, type);
                } else {
                    unprintedMessages.add(new UnprintedMessage(s, type));
                    if (type == ConsoleViewContentType.ERROR_OUTPUT) {
                        System.err.print(s);
                    } else {
                        System.out.print(s);
                    }
                }
            }
        });
    }

    public static Logger getLogger(final Project project) {
        return new Logger() {
            @Override
            public void debug(String s, Object... objects) {
                logDebug(project, s, objects);
            }

            @Override
            public void info(String s, Object... objects) {
                logInfo(project, s, objects);
            }

            @Override
            public void warn(String s, Object... objects) {
                logWarn(project, s, objects);
            }

            @Override
            public void error(String s, Object... objects) {
                logError(project, s, objects);
            }
        };
    }

    public static void initializeProject(final Project project) {
        // setup a compile task if there isn't one yet
        boolean found = false;
        for (CompileTask task : CompilerManager.getInstance(project).getAfterTasks()) {
            if (task instanceof RoboVmCompileTask) {
                found = true;
                break;
            }
        }
        if (!found) {
            CompilerManager.getInstance(project).addAfterTask(new RoboVmCompileTask());
        }

        // hook ito the message bus so we get to know if a storyboard/xib
        // file is opened
        project.getMessageBus().connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new RoboVmFileEditorManagerListener(project));

        // initialize our tool window to which we
        // log all messages
        UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {
                if (project.isDisposed()) {
                    return;
                }
                ToolWindow toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(ROBOVM_TOOLWINDOW_ID, false, ToolWindowAnchor.BOTTOM, project, true);
                ConsoleView consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
                Content content = toolWindow.getContentManager().getFactory().createContent(consoleView.getComponent(), "Console", true);
                toolWindow.getContentManager().addContent(content);
                toolWindow.setIcon(RoboVmIcons.ROBOVM_SMALL);
                consoleViews.put(project, consoleView);
                toolWindows.put(project, toolWindow);
                logInfo(project, "RoboVM plugin initialized");
            }
        });
    }

    public static void unregisterProject(Project project) {
        ConsoleView consoleView = consoleViews.remove(project);
        if (consoleView != null) {
            consoleView.dispose();
        }
        toolWindows.remove(project);
        ToolWindowManager.getInstance(project).unregisterToolWindow(ROBOVM_TOOLWINDOW_ID);
    }

    public static void extractSdk() {
        File sdkHome = getSdkHomeBase();
        if (!sdkHome.exists()) {
            if (!sdkHome.mkdirs()) {
                logError(null, "Couldn't create sdk dir in %s", sdkHome.getAbsolutePath());
                throw new RuntimeException("Couldn't create sdk dir in " + sdkHome.getAbsolutePath());
            }
        }
        extractArchive("robovm-dist", sdkHome);

        // create an SDK if it doesn't exist yet
        RoboVmSdkType.createSdkIfNotExists();
    }

    public static File getSdkHome() {
        File sdkHome = new File(getSdkHomeBase(), "robovm-" + Version.getVersion());
        return sdkHome;
    }

    public static File getSdkHomeBase() {
        return new File(System.getProperty("user.home"), ".robovm-sdks");
    }

    public static Sdk getSdk() {
        RoboVmSdkType sdkType = new RoboVmSdkType();
        for(Sdk sdk: ProjectJdkTable.getInstance().getAllJdks()) {
            if(sdkType.suggestSdkName(null, null).equals(sdk.getName())) {
                return sdk;
            }
        }
        return null;
    }

    private static void extractArchive(String archive, File dest) {
        archive = "/" + archive;
        TarArchiveInputStream in = null;
        boolean isSnapshot = Version.getVersion().toLowerCase().contains("snapshot");
        try {
            in = new TarArchiveInputStream(new GZIPInputStream(RoboVmPlugin.class.getResourceAsStream(archive)));
            ArchiveEntry entry = null;
            while ((entry = in.getNextEntry()) != null) {
                File f = new File(dest, entry.getName());
                if (entry.isDirectory()) {
                    f.mkdirs();
                } else {
                    if(!isSnapshot && f.exists()) {
                        continue;
                    }
                    f.getParentFile().mkdirs();
                    OutputStream out = null;
                    try {
                        out = new FileOutputStream(f);
                        IOUtils.copy(in, out);
                    } finally {
                        IOUtils.closeQuietly(out);
                    }
                }
            }
            logInfo(null, "Installed RoboVM SDK %s to %s", Version.getVersion(), dest.getAbsolutePath());

            // make all files in bin executable
            for (File file : new File(getSdkHome(), "bin").listFiles()) {
                file.setExecutable(true);
            }
        } catch (Throwable t) {
            logError(null, "Couldn't extract SDK to %s", dest.getAbsolutePath());
            throw new RuntimeException("Couldn't extract SDK to " + dest.getAbsolutePath(), t);
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    /**
     * @return all sdk runtime libraries and their source jars
     */
    public static List<File> getSdkLibraries() {
        List<File> libs = new ArrayList<File>();
        File libsDir = new File(getSdkHome(), "lib");
        for (File file : libsDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        })) {
            libs.add(file);
        }
        return libs;
    }

    /**
     * @return the source jars of all runtime libraries
     */
    public static List<File> getSdkLibrariesWithoutSources() {
        List<File> libs = getSdkLibraries();
        Iterator<File> iter = libs.iterator();
        while(iter.hasNext()) {
            File file = iter.next();
            if(file.getName().endsWith("-sources.jar")) {
                iter.remove();
            }
        }
        return libs;
    }

    /**
     * @return the source jars of all runtime libraries
     */
    public static List<File> getSdkLibrarySources() {
        List<File> libs = getSdkLibraries();
        Iterator<File> iter = libs.iterator();
        while(iter.hasNext()) {
            File file = iter.next();
            if(!file.getName().endsWith("-sources.jar")) {
                iter.remove();
            }
        }
        return libs;
    }

    public static Config.Home getRoboVmHome() {
        try {
            return Config.Home.find();
        } catch(Throwable t) {
            return new Config.Home(getSdkHome());
        }
    }

    public static List<Module> getRoboVmModules(Project project) {
        List<Module> validModules = new ArrayList<Module>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            if (isRoboVmModule(module)) {
                validModules.add(module);
            }
        }
        return validModules;
    }

    public static boolean isRoboVmModule(Module module) {
        // HACK! to identify if the module uses a robovm sdk
        if (ModuleRootManager.getInstance(module).getSdk() != null) {
            if (ModuleRootManager.getInstance(module).getSdk().getSdkType().getName().toLowerCase().contains("robovm")) {
                return true;
            }
        }

        // check if there's any RoboVM RT libs in the classpath
        OrderEnumerator classes = ModuleRootManager.getInstance(module).orderEntries().recursively().withoutSdk().compileOnly();
        for (String path : classes.getPathsList().getPathList()) {
            if (isSdkLibrary(path)) {
                return true;
            }
        }

        // check if there's a robovm.xml file in the root of the module
        for(VirtualFile file: ModuleRootManager.getInstance(module).getContentRoots()) {
            if(file.findChild("robovm.xml") != null) {
                return true;
            }
        }

        return false;
    }

    public static void focusToolWindow(final Project project) {
        UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {
                ToolWindow toolWindow = toolWindows.get(project);
                if(toolWindow != null) {
                    toolWindow.show(new Runnable() {
                        @Override
                        public void run() {

                        }
                    });
                }
            }
        });
    }

    public static File getModuleLogDir(Module module) {
        File logDir = new File(getModuleBaseDir(module), "robovm-build/logs/");
        if (!logDir.exists()) {
            if (!logDir.mkdirs()) {
                throw new RuntimeException("Couldn't create log dir '" + logDir.getAbsolutePath() + "'");
            }
        }
        return logDir;
    }

    public static File getModuleXcodeDir(Module module) {
        File buildDir = new File(getModuleBaseDir(module), "robovm-build/xcode/");
        if (!buildDir.exists()) {
            if (!buildDir.mkdirs()) {
                throw new RuntimeException("Couldn't create build dir '" + buildDir.getAbsolutePath() + "'");
            }
        }
        return buildDir;
    }

    public static File getModuleBuildDir(Module module, String runConfigName, OS os, Arch arch) {
        File buildDir = new File(getModuleBaseDir(module), "robovm-build/tmp/" + runConfigName + "/" + os + "/" + arch);
        if (!buildDir.exists()) {
            if (!buildDir.mkdirs()) {
                throw new RuntimeException("Couldn't create build dir '" + buildDir.getAbsolutePath() + "'");
            }
        }
        return buildDir;
    }

    public static File getModuleClassesDir(String moduleBaseDir) {
        File classesDir = new File(moduleBaseDir, "robovm-build/classes/");
        if(!classesDir.exists()) {
            if (!classesDir.mkdirs()) {
                throw new RuntimeException("Couldn't create classes dir '" + classesDir.getAbsolutePath() + "'");
            }
        }
        return classesDir;
    }

    public static File getModuleBaseDir(Module module) {
        return new File(ModuleRootManager.getInstance(module).getContentRoots()[0].getPath());
    }

    public static Set<File> getModuleResourcePaths(Module module) {
        try {
            File moduleBaseDir = new File(ModuleRootManager.getInstance(module).getContentRoots()[0].getPath());
            Config.Builder configBuilder = new Config.Builder();
            configBuilder.home(RoboVmPlugin.getRoboVmHome());
            configBuilder.addClasspathEntry(new File(".")); // Fake a classpath to make Config happy
            configBuilder.skipLinking(true);
            RoboVmCompileTask.loadConfig(module.getProject(), configBuilder, moduleBaseDir, false);
            Config config = configBuilder.build();
            Set<File> paths = new HashSet<>();
            for (Resource r : config.getResources()) {
                if (r.getPath() != null) {
                    if (r.getPath().exists() && r.getPath().isDirectory()) {
                        paths.add(r.getPath());
                    }
                } else if (r.getDirectory() != null) {
                    if (r.getDirectory().exists() && r.getDirectory().isDirectory()) {
                        paths.add(r.getDirectory());
                    }
                }
            }
            return paths;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Module isRoboVmModuleResourcePath(Project project, VirtualFile file) {
        try {
            // using reflection here as building the config takes an
            // immense amount of time
            Field field = Config.Builder.class.getDeclaredField("config");
            field.setAccessible(true);

            for (Module module : ModuleManager.getInstance(project).getModules()) {
                File moduleBaseDir = new File(ModuleRootManager.getInstance(module).getContentRoots()[0].getPath());
                Config.Builder builder = new Config.Builder();
                builder.home(RoboVmPlugin.getRoboVmHome());
                builder.addClasspathEntry(new File(".")); // Fake a classpath to make Config happy
                builder.skipLinking(true);
                builder.readProjectProperties(moduleBaseDir, false);
                builder.readProjectConfig(moduleBaseDir, false);
                Config config = (Config)field.get(builder);
                for(Resource res: config.getResources()) {
                    if(new File(file.getCanonicalPath()).getAbsolutePath().startsWith(res.getDirectory().getAbsolutePath())) {
                        return module;
                    }
                }
            }
            return null;
        } catch(Throwable t) {
            return null;
        }
    }

    public static File getModuleInfoPlist(Module module) {
        try {
            File projectRoot = getModuleBaseDir(module);
            Config.Builder configBuilder = new Config.Builder();
            configBuilder.home(RoboVmPlugin.getRoboVmHome());
            // Fake a classpath to make Config happy
            configBuilder.addClasspathEntry(new File("."));
            configBuilder.skipLinking(true);
            RoboVmCompileTask.loadConfig(module.getProject(), configBuilder, projectRoot, false);
            Config config = configBuilder.build();
            InfoPList iosInfoPList = config.getIosInfoPList();
            if(iosInfoPList != null) return iosInfoPList.getFile();
            else return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isSdkLibrary(String path) {
        String name = new File(path).getName();

        return name.startsWith("robovm-rt") ||
                name.startsWith("robovm-objc") ||
                name.startsWith("robovm-cocoatouch") ||
                name.startsWith("robovm-cacerts");
    }

    public static boolean isBootClasspathLibrary(File path) {
        return path.getName().startsWith("robovm-rt");
    }
}
