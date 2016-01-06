/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.updateSettings.impl

import com.google.common.collect.Lists
import com.intellij.diagnostic.IdeErrorsDialog
import com.intellij.externalDependencies.DependencyOnPlugin
import com.intellij.externalDependencies.ExternalDependenciesManager
import com.intellij.ide.IdeBundle
import com.intellij.ide.externalComponents.ExternalComponentManager
import com.intellij.ide.externalComponents.ExternalComponentSource
import com.intellij.ide.externalComponents.UpdatableExternalComponent
import com.intellij.ide.plugins.*
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.*
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.LogUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.updateSettings.UpdateStrategyCustomization
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.BuildNumber
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.PlatformUtils
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.io.URLUtil
import com.intellij.util.ui.UIUtil
import com.intellij.xml.util.XmlStringUtil
import org.apache.http.client.utils.URIBuilder
import org.jdom.JDOMException
import org.jetbrains.annotations.Contract
import java.io.File
import java.io.IOException
import java.net.URISyntaxException
import java.net.URL
import java.util.*

/**
 * See XML file by [ApplicationInfoEx.getUpdateUrls] for reference.

 * @author mike
 * *
 * @since Oct 31, 2002
 */
object UpdateChecker {
  private val LOG = Logger.getInstance("#com.intellij.openapi.updateSettings.impl.UpdateChecker")
  val NO_PLATFORM_UPDATE = "ide.no.platform.update"

  val NOTIFICATIONS = NotificationGroup(IdeBundle.message("update.notifications.group"), NotificationDisplayType.STICKY_BALLOON, true)

  private val INSTALLATION_UID = "installation.uid"
  private val DISABLED_UPDATE = "disabled_update.txt"

  private var ourDisabledToUpdatePlugins: MutableSet<String>? = null
  private val ourAdditionalRequestOptions = hashMapOf<String, String>()
  private val ourUpdatedPlugins = hashMapOf<String, PluginDownloader>()
  private val SHOWN_NOTIFICATION_TYPES = Collections.synchronizedSet(EnumSet.noneOf(NotificationUniqueType::class.java))

  private val UPDATE_URL by lazy { ApplicationInfoEx.getInstanceEx().updateUrls.checkingUrl }
  private val PATCHES_URL by lazy { ApplicationInfoEx.getInstanceEx().updateUrls.patchesUrl }

  val excludedFromUpdateCheckPlugins = hashSetOf<String>()

  private val updateUrl: String
    get() = System.getProperty("idea.updates.url") ?: UPDATE_URL

  private val patchesUrl: String
    get() = System.getProperty("idea.patches.url") ?: PATCHES_URL

  /**
   * For scheduled update checks.
   */
  @JvmStatic
  fun updateAndShowResult(): ActionCallback {
    val callback = ActionCallback()
    ApplicationManager.getApplication().executeOnPooledThread { doUpdateAndShowResult(null, true, false, UpdateSettings.getInstance(), null, callback) }
    return callback
  }

  /**
   * For manual update checks (Help | Check for Updates, Settings | Updates | Check Now)
   * (the latter action may pass customised update settings).
   */
  @JvmStatic
  fun updateAndShowResult(project: Project?, customSettings: UpdateSettings?) {
    val settings = customSettings ?: UpdateSettings.getInstance()
    val fromSettings = customSettings != null

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, IdeBundle.message("updates.checking.progress"), true) {
      override fun run(indicator: ProgressIndicator) {
        doUpdateAndShowResult(getProject(), fromSettings, true, settings, indicator, null)
      }

      override fun isConditionalModal(): Boolean {
        return fromSettings
      }

      override fun shouldStartInBackground(): Boolean {
        return !fromSettings
      }
    })
  }

  private fun doUpdateAndShowResult(project: Project?,
                                    fromSettings: Boolean,
                                    manualCheck: Boolean,
                                    updateSettings: UpdateSettings,
                                    indicator: ProgressIndicator?,
                                    callback: ActionCallback?) {
    // check platform update

    indicator?.text = IdeBundle.message("updates.checking.platform")

    val result = checkPlatformUpdate(updateSettings)

    if (manualCheck && result.state == UpdateStrategy.State.LOADED) {
      val settings = UpdateSettings.getInstance()
      settings.saveLastCheckedInfo()
      settings.setKnownChannelIds(result.allChannelsIds)
    }
    else if (result.state == UpdateStrategy.State.CONNECTION_ERROR) {
      val e = result.error
      if (e != null) LOG.debug(e)
      val cause = e?.message ?: "internal error"
      showErrorMessage(manualCheck, IdeBundle.message("updates.error.connection.failed", cause))
      return
    }

    // check plugins update (with regard to potential platform update)

    indicator?.text = IdeBundle.message("updates.checking.plugins")

    val updatedPlugins: Collection<PluginDownloader>?
    val incompatiblePlugins: MutableCollection<IdeaPluginDescriptor>?
    val externalUpdates: MutableCollection<ExternalUpdate>?

    if (newChannelReady(result.channelToPropose)) {
      updatedPlugins = null
      incompatiblePlugins = null
      externalUpdates = null
    }
    else {
      val buildNumber: BuildNumber? = result.updatedChannel?.latestBuild?.apiVersion

      incompatiblePlugins = if (buildNumber != null) HashSet<IdeaPluginDescriptor>() else null
      try {
        updatedPlugins = checkPluginsUpdate(updateSettings, indicator, incompatiblePlugins, buildNumber)
        externalUpdates = updateExternal(manualCheck, updateSettings, indicator);
      }
      catch (e: IOException) {
        showErrorMessage(manualCheck, IdeBundle.message("updates.error.connection.failed", e.message))
        return
      }

    }

    // show result

    ApplicationManager.getApplication().invokeLater({
      showUpdateResult(project, result, updateSettings, updatedPlugins, incompatiblePlugins, externalUpdates, !fromSettings, manualCheck);
      callback?.setDone()
    }, if (fromSettings) ModalityState.any() else ModalityState.NON_MODAL)
  }

  private fun checkPlatformUpdate(settings: UpdateSettings): CheckForUpdateResult {
    if (System.getProperty(NO_PLATFORM_UPDATE, "false").toBoolean()) {
      return CheckForUpdateResult(UpdateStrategy.State.NOTHING_LOADED, null)
    }

    val updateInfo: UpdatesInfo?
    try {
      val uriBuilder = URIBuilder(updateUrl)
      if (URLUtil.FILE_PROTOCOL != uriBuilder.scheme) {
        prepareUpdateCheckArgs(uriBuilder)
      }
      val updateUrl = uriBuilder.build().toString()
      LogUtil.debug(LOG, "load update xml (UPDATE_URL='%s')", updateUrl)

      updateInfo = HttpRequests.request(updateUrl).forceHttps(settings.canUseSecureConnection()).connect(HttpRequests.RequestProcessor<com.intellij.openapi.updateSettings.impl.UpdatesInfo> { request ->
        try {
          return@RequestProcessor UpdatesInfo(JDOMUtil.load(request.reader))
        }
        catch (e: JDOMException) {
          // corrupted content, don't bother telling user
          LOG.info(e)
          return@RequestProcessor null
        }
      })
    }
    catch (e: URISyntaxException) {
      return CheckForUpdateResult(UpdateStrategy.State.CONNECTION_ERROR, e)
    }
    catch (e: IOException) {
      return CheckForUpdateResult(UpdateStrategy.State.CONNECTION_ERROR, e)
    }
    catch (e: Throwable) {
      // Some other unexpected error related to JRE setup, e.g.
      // java.lang.NoClassDefFoundError: Could not initialize class javax.crypto.SunJCE_b
      //     at javax.crypto.KeyGenerator.a(DashoA13*..)
      //     ....
      // See http://b.android.com/149270 for more.
      return CheckForUpdateResult(UpdateStrategy.State.CONNECTION_ERROR, RuntimeException(e))
    }
    if (updateInfo == null) {
      return CheckForUpdateResult(UpdateStrategy.State.NOTHING_LOADED, null)
    }

    val appInfo = ApplicationInfo.getInstance()
    val majorVersion = Integer.parseInt(appInfo.majorVersion)
    val customization = UpdateStrategyCustomization.getInstance()
    val strategy = UpdateStrategy(majorVersion, appInfo.build, updateInfo, settings, customization)
    return strategy.checkForUpdates()
  }

  private fun checkPluginsUpdate(updateSettings: UpdateSettings,
                                 indicator: ProgressIndicator?,
                                 incompatiblePlugins: MutableCollection<IdeaPluginDescriptor>?,
                                 buildNumber: BuildNumber?): Collection<PluginDownloader>? {
    val updateable = collectUpdateablePlugins()

    if (updateable.isEmpty()) return null

    // check custom repositories and the main one for updates
    val toUpdate = ContainerUtil.newTroveMap<PluginId, PluginDownloader>()

    val hosts = RepositoryHelper.getPluginHosts()
    val state = InstalledPluginsState.getInstance()

    outer@ for (host in hosts) {
      try {
        val forceHttps = host == null && updateSettings.canUseSecureConnection()
        val list = RepositoryHelper.loadPlugins(host, buildNumber, forceHttps, indicator)
        for (descriptor in list) {
          val id = descriptor.pluginId
          if (updateable.containsKey(id)) {
            updateable.remove(id)
            state.onDescriptorDownload(descriptor)
            val downloader = PluginDownloader.createDownloader(descriptor, host, buildNumber)
            downloader.setForceHttps(forceHttps)
            checkAndPrepareToInstall(downloader, state, toUpdate, incompatiblePlugins, indicator)
            if (updateable.isEmpty()) {
              break@outer
            }
          }
        }
      }
      catch (e: IOException) {
        LOG.debug(e)
        if (host != null) {
          LOG.info("failed to load plugin descriptions from " + host + ": " + e.message)
        }
        else {
          throw e
        }
      }

    }

    return if (toUpdate.isEmpty) null else toUpdate.values
  }

  /**
   * Returns a list of plugins which are currently installed or were installed in the previous installation from which
   * we're importing the settings.
   */
  private fun collectUpdateablePlugins(): MutableMap<PluginId, IdeaPluginDescriptor> {
    val updateable = ContainerUtil.newTroveMap<PluginId, IdeaPluginDescriptor>()

    updateable += PluginManagerCore.getPlugins().filter { !it.isBundled }.toMapBy { it.pluginId }

    val onceInstalled = File(PathManager.getConfigPath(), PluginManager.INSTALLED_TXT)
    if (onceInstalled.isFile) {
      try {
        for (line in FileUtil.loadLines(onceInstalled)) {
          val id = PluginId.getId(line.trim { it <= ' ' })
          if (id !in updateable) {
            updateable.put(id, null)
          }
        }
      }
      catch (e: IOException) {
        LOG.error(onceInstalled.path, e)
      }

      //noinspection SSBasedInspection
      onceInstalled.deleteOnExit()
    }

    for (excludedPluginId in excludedFromUpdateCheckPlugins) {
      if (!isRequiredForAnyOpenProject(excludedPluginId)) {
        updateable.remove(PluginId.getId(excludedPluginId))
      }
    }

    return updateable
  }

  private fun isRequiredForAnyOpenProject(pluginId: String) =
      ProjectManager.getInstance().openProjects.any { isRequiredForProject(it, pluginId) }

  private fun isRequiredForProject(project: Project, pluginId: String) =
      ExternalDependenciesManager.getInstance(project).getDependencies(DependencyOnPlugin::class.java).any { it.pluginId == pluginId }

  @Throws(IOException::class)
  @JvmStatic
  fun updateExternal(manualCheck: Boolean,
                     updateSettings: UpdateSettings,
                     indicator: ProgressIndicator?) : MutableCollection<ExternalUpdate> {
    val result: MutableCollection<ExternalUpdate> = Lists.newArrayList()
    val manager: ExternalComponentManager = ExternalComponentManager.getInstance()
    indicator?.text = "Fetching available updates for external components..."

    for (source in manager.componentSources) {
      indicator?.checkCanceled()
      if (!updateSettings.enabledExternalUpdateSources.contains(source.name)) {
        continue
      }
      try {
        val available: MutableCollection<UpdatableExternalComponent> = source.getAvailableVersions(indicator, updateSettings)
        val siteResult: MutableList<UpdatableExternalComponent> = Lists.newArrayList()
        for (component in available) {
          if (component.isUpdateFor(manager.findExistingComponentMatching(component, source))) {
            siteResult.add(component)
          }
        }
        if (!siteResult.isEmpty()) {
          result.add(ExternalUpdate(siteResult, source))
        }
      }
      catch (e: Exception) {
        var msg: String? = e.message
        val message: String
        if (msg != null) {
          message = msg
        } else {
          message = "Unknown"
        }
        LOG.warn(e)
        showErrorMessage(manualCheck, IdeBundle.message("updates.external.error.message", source.name, message))
      }
    }

    return result
  }

  @Throws(IOException::class)
  @JvmStatic
  fun checkAndPrepareToInstall(downloader: PluginDownloader,
                               state: InstalledPluginsState,
                               toUpdate: MutableMap<PluginId, PluginDownloader>,
                               incompatiblePlugins: MutableCollection<IdeaPluginDescriptor>?,
                               indicator: ProgressIndicator?) {
    var downloader = downloader
    val pluginId = downloader.pluginId
    if (PluginManagerCore.getDisabledPlugins().contains(pluginId)) return

    val pluginVersion = downloader.pluginVersion
    val installedPlugin = PluginManager.getPlugin(PluginId.getId(pluginId))
    if (installedPlugin == null || pluginVersion == null || PluginDownloader.compareVersionsSkipBrokenAndIncompatible(installedPlugin, pluginVersion) > 0) {
      var descriptor: IdeaPluginDescriptor?

      val oldDownloader = ourUpdatedPlugins[pluginId]
      if (oldDownloader == null || StringUtil.compareVersionNumbers(pluginVersion, oldDownloader.pluginVersion) > 0) {
        descriptor = downloader.descriptor
        if (descriptor is PluginNode && descriptor.isIncomplete()) {
          if (downloader.prepareToInstall(indicator ?: EmptyProgressIndicator())) {
            descriptor = downloader.descriptor
          }
          ourUpdatedPlugins.put(pluginId, downloader)
        }
      }
      else {
        downloader = oldDownloader
        descriptor = oldDownloader.descriptor
      }

      if (descriptor != null && !PluginManagerCore.isIncompatible(descriptor, downloader.buildNumber) && !state.wasUpdated(descriptor.pluginId)) {
        toUpdate.put(PluginId.getId(pluginId), downloader)
      }
    }

    //collect plugins which were not updated and would be incompatible with new version
    if (incompatiblePlugins != null && installedPlugin != null && installedPlugin.isEnabled &&
        !toUpdate.containsKey(installedPlugin.pluginId) &&
        PluginManagerCore.isIncompatible(installedPlugin, downloader.buildNumber)) {
      incompatiblePlugins.add(installedPlugin)
    }
  }

  private fun showErrorMessage(showDialog: Boolean, message: String) {
    LOG.info(message)
    if (showDialog) {
      UIUtil.invokeLaterIfNeeded { Messages.showErrorDialog(message, IdeBundle.message("updates.error.connection.title")) }
    }
  }

  @Contract("null -> false")
  private fun newChannelReady(channelToPropose: UpdateChannel?): Boolean {
    return channelToPropose != null && channelToPropose.latestBuild != null
  }

  private fun showUpdateResult(project: Project?,
                               checkForUpdateResult: CheckForUpdateResult,
                               updateSettings: UpdateSettings,
                               updatedPlugins: Collection<PluginDownloader>?,
                               incompatiblePlugins: Collection<IdeaPluginDescriptor>?,
                               externalUpdates: Collection<ExternalUpdate>?,
                               enableLink: Boolean,
                               alwaysShowResults: Boolean) {
    val channelToPropose = checkForUpdateResult.channelToPropose
    val updatedChannel = checkForUpdateResult.updatedChannel
    var foundUpdate: Boolean = false

    if (updatedChannel != null) {
      foundUpdate = true
      val runnable = {
        UpdateInfoDialog(updatedChannel,
                         enableLink,
                         updateSettings.canUseSecureConnection(),
                         updatedPlugins,
                         incompatiblePlugins).show()
      }

      if (alwaysShowResults) {
        runnable.invoke()
      }
      else {
        val message = IdeBundle.message("updates.ready.message", ApplicationNamesInfo.getInstance().fullProductName)
        showNotification(project, message, runnable, NotificationUniqueType.UPDATE_IN_CHANNEL)
      }
    }
    else if (newChannelReady(channelToPropose)) {
      foundUpdate = true
      val runnable = {
        val dialog = NewChannelDialog(channelToPropose!!)
        dialog.show()
        // once we informed that new product is available (when new channel was detected), remember the fact
        if (dialog.exitCode == DialogWrapper.CANCEL_EXIT_CODE &&
            checkForUpdateResult.state == UpdateStrategy.State.LOADED &&
            !updateSettings.knownChannelsIds.contains(channelToPropose.getId())) {
          val newIds = ArrayList(updateSettings.knownChannelsIds)
          newIds.add(channelToPropose.getId())
          updateSettings.setKnownChannelIds(newIds)
        }
      }

      if (alwaysShowResults) {
        runnable.invoke()
      }
      else {
        val message = IdeBundle.message("updates.new.version.available", ApplicationNamesInfo.getInstance().fullProductName)
        showNotification(project, message, runnable, NotificationUniqueType.NEW_CHANNEL)
      }
    }
    else if (updatedPlugins != null && !updatedPlugins.isEmpty()) {
      foundUpdate = true
      val runnable = { PluginUpdateInfoDialog(updatedPlugins, enableLink).show() }

      if (alwaysShowResults) {
        runnable.invoke()
      }
      else {
        val plugins = updatedPlugins.joinToString { downloader -> downloader.pluginName }
        val message = IdeBundle.message("updates.plugins.ready.message", updatedPlugins.size, plugins)
        showNotification(project, message, runnable, NotificationUniqueType.PLUGINS_UPDATE)
      }
    }

    if (externalUpdates != null && !externalUpdates.isEmpty()) {
      foundUpdate = true
      for (update in externalUpdates) {
        val source: ExternalComponentSource = update.source
        val components: MutableCollection<UpdatableExternalComponent> = update.components
        val runnable = {
            source.installUpdates(components)
        }

        if (alwaysShowResults) {
          runnable.invoke()
        }
        else {
          val updates = StringUtil.join(components, ", ")
          val message = IdeBundle.message("updates.external.ready.message", components.size(), updates)
          showNotification(project, message, runnable, NotificationUniqueType.PLUGINS_UPDATE) // TODO: Which type to use?
        }
      }
    }
    if (!foundUpdate && alwaysShowResults) {
      NoUpdatesDialog(enableLink).show()
    }
  }

  private fun showNotification(project: Project?,
                               message: String,
                               runnable: (() -> Unit)?,
                               notificationType: NotificationUniqueType?) {
    if (notificationType != null) {
      if (!SHOWN_NOTIFICATION_TYPES.add(notificationType)) {
        return
      }
    }

    var listener: NotificationListener? = null
    if (runnable != null) {
      listener = NotificationListener { notification, event ->
        notification.expire()
        runnable.invoke()
      }
    }

    val title = IdeBundle.message("update.notifications.title")
    NOTIFICATIONS.createNotification(title, XmlStringUtil.wrapInHtml(message), NotificationType.INFORMATION, listener)
        .whenExpired { SHOWN_NOTIFICATION_TYPES.remove(notificationType) }
        .notify(project)
  }

  @JvmStatic
  fun addUpdateRequestParameter(name: String, value: String) {
    ourAdditionalRequestOptions.put(name, value)
  }

  private fun prepareUpdateCheckArgs(uriBuilder: URIBuilder) {
    addUpdateRequestParameter("build", ApplicationInfo.getInstance().build.asString())
    addUpdateRequestParameter("uid", getInstallationUID(PropertiesComponent.getInstance()))
    addUpdateRequestParameter("os", SystemInfo.OS_NAME + ' ' + SystemInfo.OS_VERSION)
    if (ApplicationInfoEx.getInstanceEx().isEAP) {
      addUpdateRequestParameter("eap", "")
    }

    for ((name, value) in ourAdditionalRequestOptions) {
      uriBuilder.addParameter(name, if (StringUtil.isEmpty(value)) null else value)
    }
  }

  @JvmStatic
  fun getInstallationUID(propertiesComponent: PropertiesComponent): String {
    // Android Studio: we'd like a single user id across various versions of Studio.
    // The existing IntelliJ implementation (see getIntelliJInstallationUID below) used a single location on Windows, but on Mac and Linux,
    // it stores the setting in a properties component, which varies with the system selector.

    // The following implementation attempts to always retrieve the file from $HOME/.android/uid.txt
    val home = findAndroidHome()

    if (home == null) {
      // fall back to old implementation
      return getIntelliJInstallationUID(propertiesComponent)
    }

    if (!home.exists()) {
      if (!home.mkdirs()) {
        // $HOME/.android didn't exist and we couldn't create it
        return getIntelliJInstallationUID(propertiesComponent)
      }
    }

    val uidFile = File(home, "uid.txt")
    if (uidFile.exists()) {
      try {
        return FileUtil.loadFile(uidFile).trim { it <= ' ' }
      }
      catch (e: IOException) {
        return getIntelliJInstallationUID(propertiesComponent)
      }
    }

    val uuid = getIntelliJInstallationUID(propertiesComponent)
    try {
      FileUtil.writeToFile(uidFile, uuid)
    }
    catch (e: IOException) {
      // fall through
    }
    return uuid
  }

  private fun findAndroidHome(): File? {
    var envVars = arrayOf("%UserProfile%", "HOME", "user.home");
    envVars.forEach { envVar ->
      val v = System.getenv(envVar)
      if (v != null && File(v).exists()) {
        return File(v, ".android");
      }
    }

    return null
  }

  @JvmStatic
  fun getIntelliJInstallationUID(propertiesComponent: PropertiesComponent): String {
    if (SystemInfo.isWindows) {
      val uid = getInstallationUIDOnWindows(propertiesComponent)
      if (uid != null) {
        return uid
      }
    }

    var uid = propertiesComponent.getValue(INSTALLATION_UID)
    if (uid == null) {
      uid = generateUUID()
      propertiesComponent.setValue(INSTALLATION_UID, uid)
    }
    return uid
  }

  private fun getInstallationUIDOnWindows(propertiesComponent: PropertiesComponent): String? {
    val appdata = System.getenv("APPDATA")
    if (appdata != null) {
      val jetBrainsDir = File(appdata, "JetBrains")
      if (jetBrainsDir.exists() || jetBrainsDir.mkdirs()) {
        val permanentIdFile = File(jetBrainsDir, "PermanentUserId")
        try {
          if (permanentIdFile.exists()) {
            return FileUtil.loadFile(permanentIdFile).trim { it <= ' ' }
          }

          var uuid = propertiesComponent.getValue(INSTALLATION_UID)
          if (uuid == null) {
            uuid = generateUUID()
          }
          FileUtil.writeToFile(permanentIdFile, uuid)
          return uuid
        }
        catch (ignored: IOException) {
        }

      }
    }

    return null
  }

  private fun generateUUID(): String {
    try {
      return UUID.randomUUID().toString()
    }
    catch (ignored: Exception) {
    }
    catch (ignored: InternalError) {
    }

    return ""
  }

  @JvmStatic
  @Throws(IOException::class)
  fun installPlatformUpdate(patch: PatchInfo, toBuild: BuildNumber, forceHttps: Boolean) {
    ProgressManager.getInstance().runProcessWithProgressSynchronously( {
      val indicator = ProgressManager.getInstance().progressIndicator
      downloadAndInstallPatch(patch, toBuild, forceHttps, indicator)
    }, IdeBundle.message("update.downloading.patch.progress.title"), true, null)
  }

  private fun downloadAndInstallPatch(patch: PatchInfo, toBuild: BuildNumber, forceHttps: Boolean, indicator: ProgressIndicator) {
    val productCode = ApplicationInfo.getInstance().build.productCode
    val fromBuildNumber = patch.fromBuild.asStringWithoutProductCode()
    val toBuildNumber = toBuild.asStringWithoutProductCode()

    var bundledJdk = ""
    val jdkRedist = System.getProperty("idea.java.redist")
    if (jdkRedist != null && jdkRedist.lastIndexOf("jdk-bundled") >= 0) {
      bundledJdk = if ("jdk-bundled" == jdkRedist) "-jdk-bundled" else "-custom-jdk-bundled"
    }

    val osSuffix = "-" + patch.osSuffix

    val fileName = "$productCode-$fromBuildNumber-$toBuildNumber-patch$bundledJdk$osSuffix.jar"

    val url = URL(URL(patchesUrl), fileName).toString()
    val tempFile = HttpRequests
        .request(url)
        .gzip(false)
        .forceHttps(forceHttps)
        .connect { request -> request.saveToFile(FileUtil.createTempFile("ij.platform.", ".patch", true), indicator) }

    val patchFileName = ("jetbrains.patch.jar." + PlatformUtils.getPlatformPrefix()).toLowerCase(Locale.ENGLISH)
    val patchFile = File(FileUtil.getTempDirectory(), patchFileName)
    FileUtil.copy(tempFile, patchFile)
    FileUtil.delete(tempFile)
  }

  @JvmStatic
  fun installPluginUpdates(downloaders: Collection<PluginDownloader>, indicator: ProgressIndicator): Boolean {
    var installed = false

    val disabledToUpdate = disabledToUpdatePlugins
    for (downloader in downloaders) {
      if (downloader.pluginId in disabledToUpdate) {
        continue
      }
      try {
        if (downloader.prepareToInstall(indicator)) {
          val descriptor = downloader.descriptor
          if (descriptor != null) {
            downloader.install()
            installed = true
          }
        }
      }
      catch (e: IOException) {
        LOG.info(e)
      }

    }

    return installed
  }

  @JvmStatic
  val disabledToUpdatePlugins: Set<String>
    get() {
      if (ourDisabledToUpdatePlugins == null) {
        ourDisabledToUpdatePlugins = TreeSet<String>()
        if (!ApplicationManager.getApplication().isUnitTestMode) {
          try {
            val file = File(PathManager.getConfigPath(), DISABLED_UPDATE)
            if (file.isFile) {
              FileUtil.loadFile(file)
                  .split("[\\s]".toRegex())
                  .map { it.trim() }
                  .filterTo(ourDisabledToUpdatePlugins!!) { it.isNotEmpty() }
            }
          }
          catch (e: IOException) {
            LOG.error(e)
          }

        }
      }
      return ourDisabledToUpdatePlugins!!
    }

  @JvmStatic
  fun saveDisabledToUpdatePlugins() {
    val plugins = File(PathManager.getConfigPath(), DISABLED_UPDATE)
    try {
      PluginManagerCore.savePluginsList(disabledToUpdatePlugins, false, plugins)
    }
    catch (e: IOException) {
      LOG.error(e)
    }

  }

  private var ourHasFailedPlugins = false

  @JvmStatic
  fun checkForUpdate(event: IdeaLoggingEvent) {
    if (!ourHasFailedPlugins && UpdateSettings.getInstance().isCheckNeeded) {
      val throwable = event.throwable
      val pluginDescriptor = PluginManager.getPlugin(IdeErrorsDialog.findPluginId(throwable))
      if (pluginDescriptor != null && !pluginDescriptor.isBundled) {
        ourHasFailedPlugins = true
        updateAndShowResult()
      }
    }
  }

  private enum class NotificationUniqueType {
    NEW_CHANNEL, UPDATE_IN_CHANNEL, PLUGINS_UPDATE
  }
}
