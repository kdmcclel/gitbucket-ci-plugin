package io.github.gitbucket.ci.controller

import java.io.ByteArrayOutputStream

import gitbucket.core.api.JsonFormat.{apiPathSerializer, jsonFormats}
import gitbucket.core.controller.ControllerBase
import gitbucket.core.service.{AccountService, RepositoryService}
import gitbucket.core.util.Directory.getRepositoryDir
import gitbucket.core.util.SyntaxSugars.using
import gitbucket.core.util.{JGitUtil, ReferrerAuthenticator, StringUtil, WritableUsersAuthenticator}
import gitbucket.core.util.Implicits._
import io.github.gitbucket.ci.service.{BuildSetting, SimpleCIService}
import org.eclipse.jgit.api.Git
import org.fusesource.jansi.HtmlAnsiOutputStream
import org.json4s.jackson.Serialization
import org.scalatra.Ok
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.json._

class SimpleCIController extends ControllerBase
  with SimpleCIService with AccountService with RepositoryService
  with ReferrerAuthenticator with WritableUsersAuthenticator {

  get("/:owner/:repository/build")(referrersOnly { repository =>
    gitbucket.ci.html.buildresults(repository,
      getBuildResults(repository.owner, repository.name).reverse,
      getRunningJob(repository.owner, repository.name),
      getQueuedJobs(repository.owner, repository.name),
      hasDeveloperRole(repository.owner, repository.name, context.loginAccount),
      None)
  })

  get("/:owner/:repository/build/:buildNumber")(referrersOnly { repository =>
    val buildNumber = params("buildNumber").toLong
    getBuildResult(repository.owner, repository.name, buildNumber).map { buildResult =>
      colorize(StringUtil.escapeHtml(buildResult.output))
    } getOrElse NotFound()
  })

  post("/:owner/:repository/build/run")(writableUsersOnly { repository =>
    using(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
      JGitUtil.getDefaultBranch(git, repository).map { case (objectId, revision) =>
        runBuild("root", "gitbucket", objectId.name, BuildSetting("root", "gitbucket", "sbt compile"))
      }
    }
    redirect(s"/${repository.owner}/${repository.name}/build")
  })

  ajaxGet("/:owner/:repository/build/status")(referrersOnly { repository =>
    import gitbucket.core.view.helpers._

    val queuedJobs = getQueuedJobs(repository.owner, repository.name).map { job =>
      JobStatus(
        buildNumber = job.buildNumber,
        status      = "waiting",
        sha         = job.sha,
        startTime   = "",
        endTime     = "" ,
        duration    = ""
      )
    }

    val runningJob = getRunningJob(repository.owner, repository.name).map { job =>
      JobStatus(
        buildNumber = job.buildNumber,
        status      = "running",
        sha         = job.sha,
        startTime   = job.startTime.map { startTime => datetime(new java.util.Date(startTime)) }.getOrElse(""),
        endTime     = "",
        duration    = ""
      )
    }.toSeq

    val finishedJobs = getBuildResults(repository.owner, repository.name).map { job =>
      JobStatus(
        buildNumber = job.buildNumber,
        status      = if(job.success) "success" else "failure",
        sha         = job.sha,
        startTime   = datetime(new java.util.Date(job.start)),
        endTime     = datetime(new java.util.Date(job.end)),
        duration    = ((job.end - job.start) / 1000) + "sec"
      )
    }

    contentType = formats("json")
    Serialization.write(queuedJobs ++ runningJob ++ finishedJobs)(jsonFormats)
  })

  case class JobStatus(buildNumber: Long, status: String, sha: String, startTime: String, endTime: String, duration: String)

  @throws[java.io.IOException]
  private def colorize(text: String) = {
    using(new ByteArrayOutputStream()){ os =>
      using(new HtmlAnsiOutputStream(os)){ hos =>
        hos.write(text.getBytes("UTF-8"))
      }
      new String(os.toByteArray, "UTF-8")
    }
  }

  get("/helloworld"){
    getRepository("root", "test").map { repository =>
      using(Git.open(getRepositoryDir(repository.owner, repository.name))) { git =>
        JGitUtil.getDefaultBranch(git, repository).map { case (objectId, revision) =>
          runBuild("root", "gitbucket", objectId.name, BuildSetting("root", "gitbucket", "sbt compile"))
        }
      }
    }
    Ok()
  }

}
