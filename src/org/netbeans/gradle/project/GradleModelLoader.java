package org.netbeans.gradle.project;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gradle.tooling.BuildException;
import org.gradle.tooling.GradleConnectionException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProgressEvent;
import org.gradle.tooling.ProgressListener;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.GradleTask;
import org.gradle.tooling.model.Model;
import org.gradle.tooling.model.idea.IdeaContentRoot;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaModuleDependency;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaSourceDirectory;
import org.netbeans.api.progress.ProgressHandle;
import org.netbeans.api.progress.ProgressHandleFactory;
import org.netbeans.gradle.project.model.NbDependencyGroup;
import org.netbeans.gradle.project.model.NbDependencyType;
import org.netbeans.gradle.project.model.NbGradleModel;
import org.netbeans.gradle.project.model.NbGradleModule;
import org.netbeans.gradle.project.model.NbModuleDependency;
import org.netbeans.gradle.project.model.NbOutput;
import org.netbeans.gradle.project.model.NbSourceGroup;
import org.netbeans.gradle.project.model.NbSourceType;
import org.netbeans.gradle.project.model.NbUriDependency;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.util.Utilities;

public final class GradleModelLoader {
    private static final Logger LOGGER = Logger.getLogger(GradleModelLoader.class.getName());

    private static final RequestProcessor PROJECT_LOADER
            = new RequestProcessor("Gradle-Project-Loader", 1, true);

    private static GradleModelCache CACHE = new GradleModelCache(100);

    public static void fetchModel(
            final FileObject projectDir,
            final ModelRetrievedListener listener) {
        fetchModel(projectDir, false, listener);
    }

    public static void fetchModel(
            final FileObject projectDir,
            final boolean mayFetchFromCache,
            final ModelRetrievedListener listener) {
        if (projectDir == null) throw new NullPointerException("projectDir");
        if (listener == null) throw new NullPointerException("listener");

        PROJECT_LOADER.execute(new Runnable() {
            @Override
            public void run() {
                NbGradleModel model = null;
                Throwable error = null;
                try {
                    if (mayFetchFromCache) {
                        File buildFile = FileUtil.toFile(NbGradleModel.getBuildFile(projectDir));
                        FileObject settingFileObj = NbGradleModel.findSettingsGradle(projectDir);
                        File settingsFile = settingFileObj != null
                                ? FileUtil.toFile(settingFileObj)
                                : null;

                        if (buildFile != null) {
                            model = CACHE.tryGet(buildFile, settingsFile);
                        }
                    }
                    if (model == null) {
                        model = loadModel(projectDir);
                    }
                } catch (IOException ex) {
                    error = ex;
                } catch (BuildException ex) {
                    error = ex;
                } catch (GradleConnectionException ex) {
                    error = ex;
                } finally {
                    listener.onComplete(model, error);
                }
            }
        });
    }

    private static NbOutput createDefaultOutput(File projectDir) {
        File buildDir = new File(projectDir, "build" + File.pathSeparator + "classes");

        return new NbOutput(
                new File(buildDir, "main"),
                new File(buildDir, "test"));
    }

    public static NbGradleModel createEmptyModel(FileObject projectDir) throws IOException {
        File projectDirAsFile = FileUtil.toFile(projectDir);
        String name = projectDir.getNameExt();
        NbGradleModule.Properties properties = new NbGradleModule.Properties(
                name,
                projectDirAsFile,
                createDefaultOutput(projectDirAsFile),
                Collections.<String>emptyList());

        NbGradleModule mainModule = new NbGradleModule(properties,
                Collections.<NbSourceType, NbSourceGroup>emptyMap(),
                Collections.<NbDependencyType, NbDependencyGroup>emptyMap());

        return new NbGradleModel(projectDir, mainModule);
    }

    private static <T extends Model> T getModelWithProgress(
            final ProgressHandle progress,
            ProjectConnection projectConnection,
            Class<T> model) {
        ModelBuilder<T> builder = projectConnection.model(model);
        builder.addProgressListener(new ProgressListener() {
            @Override
            public void statusChanged(ProgressEvent pe) {
                progress.progress(pe.getDescription());
            }
        });

        return builder.get();
    }

    private static File tryGetModuleDir(IdeaModule module) {
        DomainObjectSet<? extends IdeaContentRoot> contentRoots = module.getContentRoots();
        return contentRoots.isEmpty() ? null : contentRoots.getAt(0).getRootDirectory();
    }

    private static IdeaModule tryFindMainModule(FileObject projectDir, IdeaProject ideaModel) {
        File projectDirAsFile = FileUtil.toFile(projectDir);
        for (IdeaModule module: ideaModel.getModules()) {
            File moduleDir = tryGetModuleDir(module);
            if (moduleDir != null && moduleDir.equals(projectDirAsFile)) {
                return module;
            }
        }
        return null;
    }

    private static Map<NbDependencyType, NbDependencyGroup> getDependencies(
            IdeaModule module, Map<String, NbGradleModule> parsedModules) {

        DependencyBuilder dependencies = new DependencyBuilder();

        for (IdeaDependency dependency: module.getDependencies()) {
            String scope = dependency.getScope().getScope();
            NbDependencyType dependencyType;
            if ("COMPILE".equals(scope)) {
                dependencyType = NbDependencyType.COMPILE;
            }
            else if ("TEST".equals(scope)) {
                dependencyType = NbDependencyType.TEST_COMPILE;
            }
            else if ("RUNTIME".equals(scope)) {
                dependencyType = NbDependencyType.RUNTIME;
            }
            else {
                dependencyType = NbDependencyType.OTHER;
            }

            if (dependency instanceof IdeaModuleDependency) {
                IdeaModuleDependency moduleDep = (IdeaModuleDependency)dependency;

                NbGradleModule parsedDependency = tryParseModule(moduleDep.getDependencyModule(), parsedModules);
                if (parsedDependency != null) {
                    dependencies.addModuleDependency(
                            dependencyType,
                            new NbModuleDependency(parsedDependency, true));
                }
            }
            else if (dependency instanceof ExternalDependency) {
                ExternalDependency externalDep = (ExternalDependency)dependency;
                URI uri = Utilities.toURI(externalDep.getFile());

                dependencies.addUriDependency(
                        dependencyType,
                        new NbUriDependency(uri, true));
            }
            else {
                LOGGER.log(Level.WARNING, "Unknown dependency: {0}", dependency);
            }
        }
        Map<NbDependencyType, NbDependencyGroup> dependencyMap
                = new EnumMap<NbDependencyType, NbDependencyGroup>(NbDependencyType.class);
        for (NbDependencyType type: NbDependencyType.values()) {
            NbDependencyGroup group = dependencies.getGroup(type);
            if (!group.isEmpty()) {
                dependencyMap.put(type, group);
            }
        }
        return dependencyMap;
    }

    private static boolean isResourcePath(IdeaSourceDirectory srcDir) {
        return srcDir.getDirectory().getName().toLowerCase(Locale.US).startsWith("resource");
    }

    private static Map<NbSourceType, NbSourceGroup> getSources(IdeaModule module) {
        List<File> sources = new LinkedList<File>();
        List<File> resources = new LinkedList<File>();
        List<File> testSources = new LinkedList<File>();
        List<File> testResources = new LinkedList<File>();

        for (IdeaContentRoot contentRoot: module.getContentRoots()) {
            for (IdeaSourceDirectory ideaSrcDir: contentRoot.getSourceDirectories()) {
                if (isResourcePath(ideaSrcDir)) {
                    resources.add(ideaSrcDir.getDirectory());
                }
                else {
                    sources.add(ideaSrcDir.getDirectory());
                }
            }
            for (IdeaSourceDirectory ideaTestDir: contentRoot.getTestDirectories()) {
                if (isResourcePath(ideaTestDir)) {
                    testResources.add(ideaTestDir.getDirectory());
                }
                else {
                    testSources.add(ideaTestDir.getDirectory());
                }
            }
        }

        Map<NbSourceType, NbSourceGroup> groups = new EnumMap<NbSourceType, NbSourceGroup>(NbSourceType.class);
        if (!sources.isEmpty()) {
            groups.put(NbSourceType.SOURCE, new NbSourceGroup(sources));
        }
        if (!resources.isEmpty()) {
            groups.put(NbSourceType.RESOURCE, new NbSourceGroup(resources));
        }
        if (!testSources.isEmpty()) {
            groups.put(NbSourceType.TEST_SOURCE, new NbSourceGroup(testSources));
        }
        if (!testResources.isEmpty()) {
            groups.put(NbSourceType.TEST_RESOURCE, new NbSourceGroup(testResources));
        }
        return groups;
    }

    private static NbGradleModule tryParseModule(IdeaModule module,
            Map<String, NbGradleModule> parsedModules) {
        String uniqueName = module.getGradleProject().getPath();

        if (parsedModules.containsKey(uniqueName)) {
            NbGradleModule parsedModule = parsedModules.get(uniqueName);
            if (parsedModule == null) {
                LOGGER.log(Level.WARNING, "Circular or missing dependency: {0}", uniqueName);
            }
            return parsedModule;
        }
        parsedModules.put(uniqueName, null);

        Map<NbDependencyType, NbDependencyGroup> dependencies
                = getDependencies(module, parsedModules);

        Map<NbSourceType, NbSourceGroup> sources = getSources(module);

        File moduleDir = tryGetModuleDir(module);
        if (moduleDir == null) {
            LOGGER.log(Level.WARNING, "Unable to find the project directory: {0}", uniqueName);
            return null;
        }

        List<String> taskNames = new LinkedList<String>();
        for (GradleTask task: module.getGradleProject().getTasks()) {
            taskNames.add(task.getName());
        }

        NbGradleModule.Properties properties = new NbGradleModule.Properties(
                uniqueName,
                moduleDir,
                createDefaultOutput(moduleDir),
                taskNames);

        NbGradleModule result = new NbGradleModule(properties, sources, dependencies);
        parsedModules.put(uniqueName, result);
        return result;
    }

    private static NbGradleModel parseFromIdeaModel(
            FileObject projectDir, IdeaProject ideaModel) throws IOException {
        IdeaModule mainModule = tryFindMainModule(projectDir, ideaModel);
        if (mainModule == null) {
            throw new IOException("Unable to find the main project in the model.");
        }

        Map<String, NbGradleModule> parsedModules = new HashMap<String, NbGradleModule>();
        NbGradleModule parsedMainModule = tryParseModule(mainModule, parsedModules);
        if (parsedMainModule == null) {
            throw new IOException("Unable to parse the main project from the model.");
        }

        for (IdeaModule module: ideaModel.getModules()) {
            String uniqueName = module.getGradleProject().getPath();
            if (!parsedModules.containsKey(uniqueName)) {
                tryParseModule(module, parsedModules);
            }
        }

        NbGradleModel mainModel = new NbGradleModel(projectDir, parsedMainModule);
        FileObject settings = mainModel.getSettingsFile();

        for (NbGradleModule module: parsedModules.values()) {
            if (module != null && module != parsedMainModule) {
                FileObject moduleDir = FileUtil.toFileObject(module.getModuleDir());
                try {
                    if (moduleDir != null) {
                        NbGradleModel model = new NbGradleModel(moduleDir, settings, module);
                        CACHE.addToCache(model);
                    }
                } catch (IOException ex) {
                    LOGGER.log(Level.INFO, "Gradle project without build.gradle: {0}", module.getUniqueName());
                }
            }
        }
        CACHE.addToCache(mainModel);

        return mainModel;
    }

    private static NbGradleModel loadModelWithProgress(
            FileObject projectDir,
            ProgressHandle progress) throws IOException {

        IdeaProject ideaModel;

        GradleConnector gradleConnector = GradleConnector.newConnector();
        gradleConnector.forProjectDirectory(FileUtil.toFile(projectDir));
        ProjectConnection projectConnection = null;
        try {
            projectConnection = gradleConnector.connect();

            ideaModel = getModelWithProgress(progress, projectConnection, IdeaProject.class);
        } finally {
            if (projectConnection != null) {
                projectConnection.close();
            }
        }

        return parseFromIdeaModel(projectDir, ideaModel);
    }

    private static NbGradleModel loadModel(FileObject projectDir) throws IOException {
        LOGGER.log(Level.INFO, "Loading Gradle project from directory: {0}", projectDir);

        ProgressHandle progress = ProgressHandleFactory.createHandle(
                NbBundle.getMessage(NbGradleProject.class, "LBL_LoadingProject", projectDir.getNameExt()));
        try {
            progress.start();
            return loadModelWithProgress(projectDir, progress);
        } finally {
            progress.finish();
        }
    }


    private static class DependencyBuilder {
        private final Map<NbDependencyType, List<NbModuleDependency>> moduleDependencies;
        private final Map<NbDependencyType, List<NbUriDependency>> uriDependencies;

        public DependencyBuilder() {
            this.moduleDependencies = new EnumMap<NbDependencyType, List<NbModuleDependency>>(NbDependencyType.class);
            this.uriDependencies = new EnumMap<NbDependencyType, List<NbUriDependency>>(NbDependencyType.class);
        }

        public static <T> void addDependency(
                NbDependencyType type,
                T dependency,
                Map<NbDependencyType, List<T>> storage) {
            List<T> list = storage.get(type);
            if (list == null) {
                list = new LinkedList<T>();
                storage.put(type, list);
            }
            list.add(dependency);
        }

        public void addModuleDependency(NbDependencyType type, NbModuleDependency dependency) {
            addDependency(type, dependency, moduleDependencies);
        }

        public void addUriDependency(NbDependencyType type, NbUriDependency dependency) {
            addDependency(type, dependency, uriDependencies);
        }

        private static <T> List<T> getDependencies(
                NbDependencyType type,
                Map<NbDependencyType, List<T>> storage) {
            List<T> dependencies = storage.get(type);
            return dependencies != null ? dependencies : Collections.<T>emptyList();
        }

        public NbDependencyGroup getGroup(NbDependencyType type) {
            return new NbDependencyGroup(
                    getDependencies(type, moduleDependencies),
                    getDependencies(type, uriDependencies));
        }
    }

    private GradleModelLoader() {
        throw new AssertionError();
    }
}
