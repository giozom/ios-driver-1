/*
 * Copyright 2012-2013 eBay Software Foundation and ios-driver committers
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.uiautomation.ios.server;

import com.beust.jcommander.JCommander;

import org.apache.commons.io.FileUtils;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.openqa.selenium.WebDriverException;
import org.uiautomation.ios.IOSCapabilities;
import org.uiautomation.ios.inspector.IDEServlet;
import org.uiautomation.ios.server.application.APPIOSApplication;
import org.uiautomation.ios.server.configuration.Configuration;
import org.uiautomation.ios.server.grid.SelfRegisteringRemote;
import org.uiautomation.ios.server.instruments.communication.curl.CURLBasedCommunicationChannel;
import org.uiautomation.ios.server.servlet.ApplicationsServlet;
import org.uiautomation.ios.server.servlet.ArchiveServlet;
import org.uiautomation.ios.server.servlet.CapabilitiesServlet;
import org.uiautomation.ios.server.servlet.DeviceServlet;
import org.uiautomation.ios.server.servlet.IOSServlet;
import org.uiautomation.ios.server.servlet.ResourceServlet;
import org.uiautomation.ios.server.servlet.StaticResourceServlet;
import org.uiautomation.ios.server.utils.FolderMonitor;
import org.uiautomation.ios.server.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.Logger;

public class IOSServer {

  public static final String DRIVER = IOSServerManager.class.getName();
  private static final Logger log = Logger.getLogger(IOSServer.class.getName());

  private Server server;
  private IOSServerConfiguration options;
  private IOSServerManager driver;
  private FolderMonitor folderMonitor;
  private SelfRegisteringRemote selfRegisteringRemote;

  public IOSServer(IOSServerConfiguration options) {
    init(options);
  }

  public IOSServer(String[] args) {
    init(args);
  }

  public static void main(String[] args) throws Exception {
    final IOSServer server = new IOSServer(args);

    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        try {
          server.stop();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    });

    try {
      server.start();
    } catch (Exception e) {
      Runtime.getRuntime().exit(1);
    }
  }

  private void init(String[] args) {
    IOSServerConfiguration options = new IOSServerConfiguration();
    new JCommander(options, args);
    init(options);
  }

  private void init(IOSServerConfiguration options) {
    this.options = options;
    Configuration.BETA_FEATURE = options.isBeta();
    Configuration.SIMULATORS_ENABLED = options.hasSimulators();
    initDriver();
    initServer();
  }

  private void initDriver() {
    driver = new IOSServerManager(options);
    for (String app : this.options.getSupportedApps()) {
      File appFile = new File(app);
      if (Configuration.BETA_FEATURE && !appFile.exists()) {
        // if an url download and extract it
        try {
          appFile = ZipUtils.extractAppFromURL(app);
        } catch (IOException ignore) {
          log.fine("url: " + app + ": " + ignore);
        }
      }
      if (appFile == null || !appFile.exists()) {
        throw new WebDriverException(app + " isn't an IOS app.");
      }
      driver.addSupportedApplication(APPIOSApplication.createFrom(appFile));
    }

    StringBuilder b = new StringBuilder();
    b.append(String.format("\nBeta features enabled ( enabled by -beta flag ): %b", Configuration.BETA_FEATURE));
    b.append(String.format("\nSimulator enabled ( enabled by -simulators flag): %b", Configuration.SIMULATORS_ENABLED));
    b.append(String.format("\nInspector: http://0.0.0.0:%d/inspector/", options.getPort()));
    b.append(String.format("\nTests can access the server at http://0.0.0.0:%d/wd/hub", options.getPort()));
    b.append(String.format("\nServer status: http://0.0.0.0:%d/wd/hub/status", options.getPort()));
    b.append(String.format("\nConnected devices: http://0.0.0.0:%d/wd/hub/devices/all", options.getPort()));
    b.append(String.format("\nApplications: http://0.0.0.0:%d/wd/hub/applications/all", options.getPort()));
    b.append(String.format("\nCapabilities: http://0.0.0.0:%d/wd/hub/capabilities/all", options.getPort()));
    b.append(String.format("\nMonitoring '%s' for new applications", options.getAppFolderToMonitor()));
    b.append(String.format("\nArchived apps: %s", driver.getApplicationStore().getFolder().getAbsolutePath()));

    if (Configuration.SIMULATORS_ENABLED) {
      addSimulatorDetails(b);
    }

    b.append("\n\nApplications :\n--------------- \n");
    for (APPIOSApplication app : driver.getSupportedApplications()) {
      String name = app.getMetadata(IOSCapabilities.BUNDLE_NAME).isEmpty()
          ? app.getMetadata(IOSCapabilities.BUNDLE_DISPLAY_NAME)
          : app.getMetadata(IOSCapabilities.BUNDLE_NAME);
      b.append(String.format("\tCFBundleName=%s", name));
      String version = app.getMetadata(IOSCapabilities.BUNDLE_VERSION);
      if (version != null && !version.isEmpty()) {
        b.append(String.format(",CFBundleVersion=%s", version));
      }
      b.append("\n");
    }
    log.info(b.toString());
  }

  private void addSimulatorDetails(StringBuilder b) {
    File xcodeInstall = driver.getHostInfo().getXCodeInstall();
    b.append(String.format("\nusing xcode install : %s", driver.getHostInfo().getXCodeInstall().getPath()));
    b.append(String.format("\nusing IOS version %s", driver.getHostInfo().getSDK()));

    boolean safari = false;
    // automatically add safari for SDK 6.0 and above.
    for (String s : driver.getHostInfo().getInstalledSDKs()) {
      Float version = Float.parseFloat(s);
      if (version >= 6L) {
        safari = true;
//        driver.addSupportedApplication(copyOfSafari());APPIOSApplication.findSafariLocation(driver.getHostInfo().getXCodeInstall(), s));
        driver.addSupportedApplication(copyOfSafari(xcodeInstall, s));
      }
    }
    if (safari) {
      b.append("\nios >= 6.0. Safari and hybrid apps are supported.");
    } else {
      b.append("\nios < 6.0. Safari and hybrid apps are NOT supported.");
    }
  }

  private void initServer() {
    server = new Server(new InetSocketAddress("0.0.0.0", options.getPort()));

    ServletContextHandler wd = new ServletContextHandler(server, "/wd/hub", true, false);
    wd.addServlet(CURLBasedCommunicationChannel.UIAScriptServlet.class, "/uiascriptproxy/*");
    wd.addServlet(IOSServlet.class, "/*");
    wd.addServlet(ResourceServlet.class, "/resources/*");
    wd.addServlet(DeviceServlet.class, "/devices/*");
    wd.addServlet(ApplicationsServlet.class, "/applications/*");
    wd.addServlet(CapabilitiesServlet.class, "/capabilities/*");
    wd.addServlet(ArchiveServlet.class, "/archive/*");
    wd.getServletContext().getContextHandler().setMaxFormContentSize(500000);
    wd.setAttribute(DRIVER, driver);

    ServletContextHandler statics = new ServletContextHandler(server, "/static", true, false);
    statics.addServlet(StaticResourceServlet.class, "/*");

    ServletContextHandler extra = new ServletContextHandler(server, "/", true, false);
    extra.addServlet(IDEServlet.class, "/inspector/*");
    extra.setAttribute(DRIVER, driver);

    HandlerList handlers = new HandlerList();
    handlers.setHandlers(new Handler[]{wd, statics, extra});
    server.setHandler(handlers);
  }

  // TODO freynaud - if xcode change, the safari copy should be wiped out.
  private APPIOSApplication copyOfSafari(File xcodeInstall, String sdk) {
    File copy = new File(System.getProperty("user.home")+"/.ios-driver/safariCopies", "safari-"+sdk+".app");
    if (!copy.exists()) {
      APPIOSApplication safari = APPIOSApplication.findSafariLocation(xcodeInstall, sdk);
      File folderToCopy = safari.getApplicationPath();
      copy.mkdirs();
      try {
        FileUtils.copyDirectory(folderToCopy, copy);
      } catch (IOException e) {
        log.warning("Cannot create the copy of safari : " + e.getMessage());
      }
    }
    return new APPIOSApplication(copy.getAbsolutePath());
  }

  public void start() throws Exception {
    if (!server.isRunning()) {
      server.start();
    }
    startFolderMonitor();
    startHubRegistration();
  }

  private void startFolderMonitor() {
    if (options.getAppFolderToMonitor() != null) {
      try {
        folderMonitor = new FolderMonitor(options, driver);
        folderMonitor.start();
      } catch (IOException e) {
        log.warning("Couldn't monitor the given folder: " + options.getAppFolderToMonitor());
      }
    }
  }

  private void startHubRegistration() {
    if (options.getRegistrationURL() != null) {
      selfRegisteringRemote = new SelfRegisteringRemote(options, driver);
      selfRegisteringRemote.start();
    }
  }

  public void stop() throws Exception {
    if (selfRegisteringRemote != null) {
      selfRegisteringRemote.stop();
    }
    if (folderMonitor != null) {
      folderMonitor.stop();
    }
    driver.stop();
    server.stop();
  }
}
