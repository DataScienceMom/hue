package com.cloudera.hue.livy.yarn

import com.cloudera.hue.livy.Logging
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.yarn.api.ApplicationConstants
import org.apache.hadoop.yarn.api.records._
import org.apache.hadoop.yarn.client.api.YarnClient
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.hadoop.yarn.util.{ConverterUtils, Records}

import scala.collection.JavaConversions._

object Client extends Logging {

  def main(args: Array[String]): Unit = {
    val packagePath = new Path(args(0))
    val lang = args(1)

    val yarnConf = new YarnConfiguration()
    yarnConf.set("yarn.resourcemanager.am.max-attempts", "1")

    val client = new Client(yarnConf)

    try {
      val job = client.submitApplication(
        "livy " + lang,
        packagePath,
        List(
          "__package/bin/run-am.sh %s 1>%s/stdout 2>%s/stderr" format (
            lang,
            ApplicationConstants.LOG_DIR_EXPANSION_VAR,
            ApplicationConstants.LOG_DIR_EXPANSION_VAR
          )
        )
      )

      info("waiting for job to start")

      job.waitForStatus(Running(), 100000) match {
        case Some(Running()) => {
          info("job started successfully on %s:%s" format(job.getHost, job.getPort))
        }
        case Some(appStatus) => {
          warn("unable to start job successfully. job has status %s" format appStatus)
        }
        case None => {
          warn("timed out waiting for job to start")
        }
      }

    } finally {
      client.close()
    }
  }
}

class Client(yarnConf: YarnConfiguration) {

  import com.cloudera.hue.livy.yarn.Client._

  val yarnClient = YarnClient.createYarnClient()
  yarnClient.init(yarnConf)
  yarnClient.start()

  def submitApplication(name: String, packagePath: Path, cmds: List[String]): Job = {
    val app = yarnClient.createApplication()
    val newAppResponse = app.getNewApplicationResponse

    val appId = newAppResponse.getApplicationId

    info("preparing to submit %s" format appId)

    val appContext = app.getApplicationSubmissionContext
    appContext.setApplicationName(name)

    val containerCtx = Records.newRecord(classOf[ContainerLaunchContext])
    val resource = Records.newRecord(classOf[Resource])

    info("Copy app master jar from local filesystem and add to the local environment")

    val packageResource = Records.newRecord(classOf[LocalResource])

    val packageUrl = ConverterUtils.getYarnUrlFromPath(packagePath)
    val fileStatus = packagePath.getFileSystem(yarnConf).getFileStatus(packagePath)

    packageResource.setResource(packageUrl)
    info("set package url to %s for %s" format (packageUrl, appId))
    packageResource.setSize(fileStatus.getLen)
    info("set package size to %s for %s" format (fileStatus.getLen, appId))
    packageResource.setTimestamp(fileStatus.getModificationTime)
    packageResource.setType(LocalResourceType.ARCHIVE)
    packageResource.setVisibility(LocalResourceVisibility.APPLICATION)

    resource.setMemory(256)
    resource.setVirtualCores(1)
    appContext.setResource(resource)

    containerCtx.setCommands(cmds)
    containerCtx.setLocalResources(Map("__package" -> packageResource))

    // FIXME: Spark needs the `MASTER` environment passed through to run on YARN. This needs a better approach.
    val master = System.getenv("MASTER")
    if (master != null) {
      containerCtx.getEnvironment()("MASTER") = master
    }

    // FIXME: Spark needs the `SPARK_HOME` environment passed through to run on YARN. This needs a better approach.
    val spark_home = System.getenv("SPARK_HOME")
    if (spark_home != null) {
      containerCtx.getEnvironment()("SPARK_HOME") = spark_home
    }

    appContext.setApplicationId(appId)
    appContext.setAMContainerSpec(containerCtx)
    appContext.setApplicationType("livy")

    info("submitting application request for %s" format appId)

    yarnClient.submitApplication(appContext)

    new Job(yarnClient, appId)
  }

  def close(): Unit = {
    yarnClient.close()
  }

  private def addToLocalResources(fs: FileSystem, fileSrcPath: String, fileDstPath: String, appId: String): LocalResource = {
    val appName = "livy"
    val suffix = appName + "/" + appId + "/" + fileDstPath

    val dst = new Path(fs.getHomeDirectory, suffix)

    fs.copyFromLocalFile(new Path(fileSrcPath), dst)

    val srcFileStatus = fs.getFileStatus(dst)
    LocalResource.newInstance(
      ConverterUtils.getYarnUrlFromURI(dst.toUri),
      LocalResourceType.FILE,
      LocalResourceVisibility.APPLICATION,
      srcFileStatus.getLen,
      srcFileStatus.getModificationTime
    )
  }
}

class Job(client: YarnClient, appId: ApplicationId) {

  def waitForFinish(timeoutMs: Long): Option[ApplicationStatus] = {
    val startTimeMs = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTimeMs < timeoutMs) {
      val status = getStatus
      status match {
        case SuccessfulFinish() | UnsuccessfulFinish() => {
          return Some(status)
        }
        case _ =>
      }

      Thread.sleep(1000)
    }

    None
  }

  def waitForStatus(status: ApplicationStatus, timeoutMs: Long): Option[ApplicationStatus] = {
    val startTimeMs = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTimeMs < timeoutMs) {
      if (getStatus == status) {
        return Some(status)
      }

      Thread.sleep(1000)
    }

    None
  }

  def waitForRPC(timeoutMs: Long): Option[(String, Int)] = {
    waitForStatus(Running(), timeoutMs)

    val startTimeMs = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTimeMs < timeoutMs) {
      val statusResponse = client.getApplicationReport(appId)

      (statusResponse.getHost, statusResponse.getRpcPort) match {
        case ("N/A", _) | (_, -1) =>
        case (hostname, port) => return Some((hostname, port))
      }
    }

    None
  }

  def getHost: String = {
    val statusResponse = client.getApplicationReport(appId)
    statusResponse.getHost
  }

  def getPort: Int = {
    val statusResponse = client.getApplicationReport(appId)
    statusResponse.getRpcPort
  }

  private def getStatus: ApplicationStatus = {
    val statusResponse = client.getApplicationReport(appId)
    convertState(statusResponse.getYarnApplicationState, statusResponse.getFinalApplicationStatus)
  }

  private def convertState(state: YarnApplicationState, status: FinalApplicationStatus): ApplicationStatus = {
    (state, status) match {
      case (YarnApplicationState.FINISHED, FinalApplicationStatus.SUCCEEDED) => SuccessfulFinish()
      case (YarnApplicationState.FINISHED, _) |
           (YarnApplicationState.KILLED, _) |
           (YarnApplicationState.FAILED, _) => UnsuccessfulFinish()
      case (YarnApplicationState.NEW, _) |
           (YarnApplicationState.NEW_SAVING, _) |
           (YarnApplicationState.SUBMITTED, _) => New()
      case (YarnApplicationState.RUNNING, _) => Running()
      case (YarnApplicationState.ACCEPTED, _) => Accepted()
    }
  }
}

trait ApplicationStatus
case class New() extends ApplicationStatus
case class Accepted() extends ApplicationStatus
case class Running() extends ApplicationStatus
case class SuccessfulFinish() extends ApplicationStatus
case class UnsuccessfulFinish() extends ApplicationStatus
