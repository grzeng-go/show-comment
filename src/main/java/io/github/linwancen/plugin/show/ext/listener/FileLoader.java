package io.github.linwancen.plugin.show.ext.listener;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.concurrency.EdtExecutorService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ExtensionPointName: fileLoader
 */
public abstract class FileLoader {

    public static final ExtensionPointName<FileLoader> EPN =
            ExtensionPointName.create("io.github.linwancen.show-comment.fileLoader");

    public final Map<VirtualFile, String> fileDoc = new ConcurrentHashMap<>();

    @Nullable
    public String treeDoc(@NotNull VirtualFile virtualFile) {
        return fileDoc.get(virtualFile);
    }

    public void clearAll() {
        fileDoc.clear();
    }

    /**
     * not need skipFile(file) skipFile(newFile)
     */
    public void copyImpl(@NotNull VirtualFile file, @NotNull VirtualFile newFile) {
        String s = fileDoc.get(file);
        if (s != null) {
            fileDoc.put(newFile, s);
        }
    }

    public void remove(@NotNull VirtualFile file, @Nullable String oldName) {
        if (oldName == null) {
            fileDoc.remove(file);
        }
    }

    public boolean skipFile(@NotNull VirtualFile file) {
        return skipExt(file.getExtension());
    }

    public boolean skipExt(@Nullable String ext) {
        return false;
    }

    /**
     * protected because not in runReadAction & executeOnPooledThread
     */
    protected abstract void loadAllImpl(Project project);

    /**
     * not need skipFile(file)
     */
    protected abstract void loadFileImpl(@NotNull VirtualFile file, @Nullable Project project);

    public static void loadAll(@NotNull Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (project.isDisposed()) {
                return;
            }
            FileLoader.EPN.getExtensionList()
                    .forEach(fileLoader -> fileLoader.loadAllImpl(project));
            EdtExecutorService.getInstance()
                    .execute(() -> {
                        if (!project.isDisposed()) {
                            ProjectView.getInstance(project).refresh();
                        }
                    });
        });
    }

    public void visitChildrenRecursively(@NotNull Project project, @NotNull VirtualFile dir, @NotNull StringBuilder sb) {
        @NotNull Collection<VirtualFile> files = DumbService.getInstance(project).runReadActionInSmartMode(() -> {
            @NotNull Collection<VirtualFile> found = new ArrayList<>();
            VfsUtil.visitChildrenRecursively(dir, new VirtualFileVisitor<Void>() {
                @Override
                public boolean visitFile(@NotNull VirtualFile file) {
                    if (!file.isDirectory()) {
                        found.add(file);
                    }
                    return true;
                }
            });
            return found;
        });
        for (@NotNull VirtualFile file : files) {
            if (project.isDisposed()) {
                return;
            }
            loadFileInSmartReadAction(project, file);
            sb.append(file.getPath()).append("\n");
        }
    }

    protected final @NotNull Collection<VirtualFile> getAllFilesByExt(@NotNull Project project,
                                                                      @NotNull String extension) {
        return DumbService.getInstance(project).runReadActionInSmartMode(() ->
                FilenameIndex.getAllFilesByExt(project, extension));
    }

    protected final void loadFileInSmartReadAction(@NotNull Project project, @NotNull VirtualFile file) {
        if (project.isDisposed()) {
            return;
        }
        DumbService.getInstance(project).runReadActionInSmartMode(() -> {
            if (!project.isDisposed() && file.isValid()) {
                loadFileImpl(file, project);
            }
        });
    }

    void loadFile(@NotNull VirtualFile file, @Nullable Project project) {
        if (skipFile(file)) return;
        if (project != null && DumbService.isDumb(project)) return;
        ApplicationManager.getApplication().executeOnPooledThread(() ->
                ApplicationManager.getApplication().runReadAction(() -> {
                    loadFileImpl(file, project);
                    if (project != null && !project.isDisposed()) {
                        EdtExecutorService.getInstance().execute(() -> ProjectView.getInstance(project).refresh());
                    }
                }));
    }

    public void copy(@NotNull VirtualFile file, @NotNull VirtualFile newFile) {
        if (skipFile(file)) return;
        if (skipFile(newFile)) return;
        copyImpl(file, newFile);
    }
}
