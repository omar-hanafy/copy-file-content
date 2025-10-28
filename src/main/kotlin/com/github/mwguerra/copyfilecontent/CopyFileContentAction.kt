// file: src/main/kotlin/com/github/mwguerra/copyfilecontent/CopyFileContentAction.kt
package com.github.mwguerra.copyfilecontent

import com.github.mwguerra.copyfilecontent.filter.FilterEngine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyFileContentAction : AnAction() {
    private var fileCount = 0
    private var fileLimitReached = false
    private val logger = Logger.getInstance(CopyFileContentAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showNotification("No project found. Action cannot proceed.", NotificationType.ERROR, null)
            return
        }
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: run {
            showNotification("No files selected.", NotificationType.ERROR, project)
            return
        }

        performCopyFilesContent(e, selectedFiles)
    }

    fun performCopyFilesContent(e: AnActionEvent, filesToCopy: Array<VirtualFile>) {
        fileCount = 0
        fileLimitReached = false
        var totalChars = 0
        var totalLines = 0
        var totalWords = 0
        var totalTokens = 0
        val copiedFilePaths = mutableSetOf<String>()

        val project = e.project ?: return
        val settings = CopyFileContentSettings.getInstance(project) ?: run {
            showNotification("Failed to load settings.", NotificationType.ERROR, project)
            return
        }

        val state = settings.state
        val repositoryRoot = getRepositoryRoot(project)
        val filterEngine = FilterEngine(state, repositoryRoot)

        val fileContents = mutableListOf<String>().apply {
            add(state.preText)
        }

        for (file in filesToCopy) {
            if (state.setMaxFileCount && fileCount >= state.fileCountLimit) {
                fileLimitReached = true
                break
            }

            val content = if (file.isDirectory) {
                processDirectory(
                    directory = file,
                    fileContents = fileContents,
                    copiedFilePaths = copiedFilePaths,
                    project = project,
                    state = state,
                    repositoryRoot = repositoryRoot,
                    engine = filterEngine,
                    addExtraLine = state.addExtraLineBetweenFiles,
                )
            } else {
                processFile(
                    file = file,
                    fileContents = fileContents,
                    copiedFilePaths = copiedFilePaths,
                    project = project,
                    state = state,
                    repositoryRoot = repositoryRoot,
                    engine = filterEngine,
                    addExtraLine = state.addExtraLineBetweenFiles,
                )
            }

            if (content.isEmpty()) {
                continue
            }

            totalChars += content.length
            totalLines += content.count { it == '\n' } + (if (content.isNotEmpty()) 1 else 0)
            totalWords += content.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
            totalTokens += estimateTokens(content)
        }

        if (fileLimitReached) {
            val fileLimitWarningMessage = """
                <html>
                <b>File Limit Reached:</b> The file limit of ${state.fileCountLimit} files was reached.
                </html>
            """.trimIndent()
            showNotificationWithSettingsAction(fileLimitWarningMessage, NotificationType.WARNING, project)
        }

        if (fileCount == 0) {
            val message = """
                <html>
                <b>No files qualified</b> based on your current filtering rules and limits.
                </html>
            """.trimIndent()
            val notification = showNotification(message, NotificationType.INFORMATION, project)
            notification.addAction(NotificationAction.createSimple("View Filter Rules") {
                openSettings(project)
            })
            return
        }

        fileContents.add(state.postText)
        copyToClipboard(fileContents.joinToString(separator = "\n"))

        if (state.showCopyNotification) {
            val fileCountMessage = when (fileCount) {
                1 -> "1 file copied."
                else -> "$fileCount files copied."
            }

            val statisticsMessage = """
                <html>
                Total characters: $totalChars<br>
                Total lines: $totalLines<br>
                Total words: $totalWords<br>
                Estimated tokens: $totalTokens
                </html>
            """.trimIndent()

            showNotification(statisticsMessage, NotificationType.INFORMATION, project)
            showNotification("<html><b>$fileCountMessage</b></html>", NotificationType.INFORMATION, project)
        }
    }

    private fun estimateTokens(content: String): Int {
        val words = content.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val punctuation = Regex("[;{}()\\[\\],]").findAll(content).count()
        return words.size + punctuation
    }

    private fun processFile(
        file: VirtualFile,
        fileContents: MutableList<String>,
        copiedFilePaths: MutableSet<String>,
        project: Project,
        state: CopyFileContentSettings.State,
        repositoryRoot: VirtualFile?,
        engine: FilterEngine,
        addExtraLine: Boolean,
    ): String {
        val fileRelativePath = repositoryRoot?.let { root -> VfsUtil.getRelativePath(file, root, '/') } ?: file.path

        if (fileRelativePath in copiedFilePaths) {
            logger.info("Skipping already copied file: $fileRelativePath")
            return ""
        }

        copiedFilePaths.add(fileRelativePath)

        val (shouldInclude, _) = engine.shouldInclude(file)
        if (!shouldInclude) {
            return ""
        }

        if (isBinaryFile(file)) {
            logger.info("Skipping file: ${file.name} - Binary file type")
            return ""
        }

        if (file.length > state.maxFileSizeKB * 1024) {
            logger.info("Skipping file: ${file.name} - Size limit exceeded")
            return ""
        }

        val header = state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
        val content = if (state.strictMemoryRead) {
            val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            if (fileEditorManager.isFileOpen(file)) {
                FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: readFileContents(file)
            } else {
                readFileContents(file)
            }
        } else {
            FileDocumentManager.getInstance().getCachedDocument(file)?.text ?: readFileContents(file)
        }

        fileContents.add(header)
        fileContents.add(content)
        fileCount++
        if (addExtraLine && content.isNotEmpty()) {
            fileContents.add("")
        }

        return content
    }

    private fun processDirectory(
        directory: VirtualFile,
        fileContents: MutableList<String>,
        copiedFilePaths: MutableSet<String>,
        project: Project,
        state: CopyFileContentSettings.State,
        repositoryRoot: VirtualFile?,
        engine: FilterEngine,
        addExtraLine: Boolean,
    ): String {
        if (!engine.shouldEnterDirectory(directory)) {
            logger.info("Skipping directory due to filters: ${directory.path}")
            return ""
        }

        val directoryContent = StringBuilder()

        for (childFile in directory.children) {
            if (state.setMaxFileCount && fileCount >= state.fileCountLimit) {
                fileLimitReached = true
                break
            }

            val content = if (childFile.isDirectory) {
                processDirectory(
                    directory = childFile,
                    fileContents = fileContents,
                    copiedFilePaths = copiedFilePaths,
                    project = project,
                    state = state,
                    repositoryRoot = repositoryRoot,
                    engine = engine,
                    addExtraLine = addExtraLine,
                )
            } else {
                processFile(
                    file = childFile,
                    fileContents = fileContents,
                    copiedFilePaths = copiedFilePaths,
                    project = project,
                    state = state,
                    repositoryRoot = repositoryRoot,
                    engine = engine,
                    addExtraLine = addExtraLine,
                )
            }

            if (content.isNotEmpty()) {
                directoryContent.append(content)
            }
        }

        return directoryContent.toString()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val data = StringSelection(text)
        clipboard.setContents(data, null)
    }

    private fun readFileContents(file: VirtualFile): String {
        return try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to read file contents: ${e.message}")
            ""
        }
    }

    private fun isBinaryFile(file: VirtualFile): Boolean {
        return FileTypeManager.getInstance().getFileTypeByFile(file).isBinary
    }

    private fun getRepositoryRoot(project: Project): VirtualFile? {
        val projectRootManager = ProjectRootManager.getInstance(project)
        return projectRootManager.contentRoots.firstOrNull()
    }

    companion object {
        fun showNotification(
            message: String,
            notificationType: NotificationType,
            project: Project?
        ): com.intellij.notification.Notification {
            val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Copy File Content")
            val notification = notificationGroup.createNotification(message, notificationType).setImportant(true)
            notification.notify(project)
            return notification
        }
    }

    private fun showNotificationWithSettingsAction(message: String, notificationType: NotificationType, project: Project?) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Copy File Content")
        val notification = notificationGroup.createNotification(message, notificationType).setImportant(true)
        notification.addAction(NotificationAction.createSimple("View Filter Rules") {
            openSettings(project)
        })
        notification.notify(project)
    }

    private fun openSettings(project: Project?) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, "Copy File Content Settings")
    }
}
