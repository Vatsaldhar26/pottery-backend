/*
 * pottery-backend - Backend API for testing programming exercises
 * Copyright © 2015 Andrew Rice (acr31@cam.ac.uk)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.ac.cam.cl.dtg.teaching.pottery.task;

import java.io.File;
import java.net.URI;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.cam.cl.dtg.teaching.docker.ApiUnavailableException;
import uk.ac.cam.cl.dtg.teaching.pottery.config.TaskConfig;
import uk.ac.cam.cl.dtg.teaching.pottery.containers.ContainerExecResponse;
import uk.ac.cam.cl.dtg.teaching.pottery.containers.ContainerManager;
import uk.ac.cam.cl.dtg.teaching.pottery.database.Database;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.InvalidTaskSpecificationException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.TaskCopyNotFoundException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.TaskStorageException;
import uk.ac.cam.cl.dtg.teaching.pottery.model.BuilderInfo;
import uk.ac.cam.cl.dtg.teaching.pottery.model.TaskInfo;
import uk.ac.cam.cl.dtg.teaching.pottery.repo.RepoFactory;
import uk.ac.cam.cl.dtg.teaching.pottery.worker.Job;
import uk.ac.cam.cl.dtg.teaching.pottery.worker.Worker;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.HarnessResponse;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.ValidatorResponse;

/**
 * Class for building a taskcopy. Responsible for copying files, compilation etc.
 *
 * @author acr31
 */
public class TaskCopyBuilder {

  protected static final Logger LOG = LoggerFactory.getLogger(TaskCopyBuilder.class);

  /** DTO with information about the current state of this copy. */
  private final BuilderInfo builderInfo;
  /** Worker object for copying files into this TaskCopy. */
  private final Job copyFiles;
  /** Worker object for compiling the tests in this TaskCopy. */
  private final Job compileTests;
  /**
   * The TaskCopy object that this builder is building. Starts off as null and then gets set
   * asynchronously.
   */
  private volatile TaskCopy taskCopy;

  /**
   * Instances of this class should be created by the Task object only. The task object has to
   * ensure that there is only one instance per copyId.
   */
  private TaskCopyBuilder(
      String sha1, String taskId, URI taskDefLocation, String copyId, TaskConfig taskConfig) {
    // We know that noone else will have access to the TaskCopy until we are done
    // 1) our copyId is unique and Task ensures that there is only one instance of TaskCopyBuilder
    // for each copyId
    // 2) we only give out a reference to the TaskCopy once the BuilderInfo status is set to
    // success. And this only happens after we finish
    // Therefore no locks are needed on this lot

    this.builderInfo = new BuilderInfo(sha1);
    this.taskCopy = null;
    this.copyFiles =
        new Job() {
          @Override
          public int execute(
              TaskIndex taskIndex,
              RepoFactory repoFactory,
              ContainerManager containerManager,
              Database database) {
            return copyFiles(sha1, taskId, copyId, taskConfig, taskDefLocation)
                ? Job.STATUS_OK
                : Job.STATUS_FAILED;
          }

          @Override
          public String getDescription() {
            return "Copy files into copy of task " + taskId;
          }
        };
    this.compileTests =
        new Job() {
          @Override
          public int execute(
              TaskIndex taskIndex,
              RepoFactory repoFactory,
              ContainerManager containerManager,
              Database database) {
            try {
              return compileFiles(taskConfig, containerManager) ? Job.STATUS_OK : Job.STATUS_FAILED;
            } catch (ApiUnavailableException e) {
              LOG.warn("Docker API unavailable. Retrying", e);
              return Job.STATUS_RETRY;
            }
          }

          @Override
          public String getDescription() {
            return "Compile tests for task " + taskId;
          }
        };
  }

  static TaskCopyBuilder createNew(
      String sha1, String taskId, URI taskDefLocation, String copyId, TaskConfig taskConfig) {
    return new TaskCopyBuilder(sha1, taskId, taskDefLocation, copyId, taskConfig);
  }

  static TaskCopyBuilder createForExisting(
      String sha1, String taskId, URI taskDefLocation, String copyId, TaskConfig taskConfig)
      throws InvalidTaskSpecificationException, TaskCopyNotFoundException, TaskStorageException {
    TaskCopyBuilder result = new TaskCopyBuilder(sha1, taskId, taskDefLocation, copyId, taskConfig);
    if (taskConfig.getTaskCopyDir(copyId).exists()) {
      result.taskCopy = new TaskCopy(taskId, copyId, taskConfig);
      result.builderInfo.setStatus(BuilderInfo.STATUS_SUCCESS);
    } else {
      throw new TaskCopyNotFoundException(
          "Task copy " + copyId + " for task " + taskId + " not found");
    }
    return result;
  }

  /** Create a placeholder TaskCopyBuilder to represent that no TaskCopy has been built. */
  static TaskCopyBuilder createSuccessPlaceholder(String taskId, TaskConfig taskConfig) {
    TaskCopyBuilder result = new TaskCopyBuilder("HEAD", taskId, null, null, taskConfig);
    result.builderInfo.setStatus(BuilderInfo.STATUS_SUCCESS);
    return result;
  }

  /**
   * Create a placeholder TaskCopyBuilder to represent that something went wrong in intialising the
   * TaskCopy process.
   */
  static TaskCopyBuilder createFailurePlaceholder(
      String taskId, TaskConfig taskConfig, Exception e) {
    TaskCopyBuilder result = new TaskCopyBuilder("INVALID", taskId, null, null, taskConfig);
    result.builderInfo.setException(e);
    return result;
  }

  TaskCopy getTaskCopy() {
    // Don't give out TaskCopy objects until we've finished building
    if (builderInfo.getStatus().equals(BuilderInfo.STATUS_SUCCESS)) {
      return taskCopy;
    } else {
      return null;
    }
  }

  /** Schedule the copying and compilation for this copy. */
  void schedule(Worker w, Job continuation) {
    // synchronize here to eliminate the race between checking the status and marking us scheduled
    synchronized (builderInfo) {
      if (isReplacable()) {
        builderInfo.setStatus(BuilderInfo.STATUS_SCHEDULED);
        w.schedule(copyFiles, compileTests, continuation);
      }
    }
  }

  BuilderInfo getBuilderInfo() {
    return builderInfo;
  }

  boolean isReplacable() {
    String status = this.builderInfo.getStatus();
    return BuilderInfo.STATUS_NOT_STARTED.equals(status)
        || BuilderInfo.STATUS_SUCCESS.equals(status)
        || BuilderInfo.STATUS_FAILURE.equals(status);
  }

  private boolean copyFiles(
      String sha1, String taskId, String copyId, TaskConfig taskConfig, final URI taskDefLocation) {
    // We are the only object writing to this directory (we have a unique id)
    // As many threads as you like can read from the bare git repo
    // Assignments to builderInfo are atomic
    // => No locks needed

    builderInfo.setStatus(BuilderInfo.STATUS_COPYING_FILES);
    LOG.info("Copying files for {} into {}", taskDefLocation, copyId);
    File location = taskConfig.getTaskCopyDir(copyId);

    // copy the files from the repo
    try (Git g =
        Git.cloneRepository().setURI(taskDefLocation.toString()).setDirectory(location).call()) {
      g.reset().setMode(ResetType.HARD).setRef(sha1).call();
    } catch (GitAPIException e) {
      builderInfo.setException(
          new TaskStorageException(
              "Failed to create clone of " + taskDefLocation + " and reset to " + sha1, e));
      return false;
    }

    try {
      taskCopy = new TaskCopy(taskId, copyId, taskConfig);
    } catch (InvalidTaskSpecificationException | TaskStorageException e) {
      builderInfo.setException(e);
      return false;
    }

    return true;
  }

  private boolean compileFiles(TaskConfig taskConfig, ContainerManager containerManager)
      throws ApiUnavailableException {
    String copyId = taskCopy.getCopyId();
    TaskInfo taskInfo = taskCopy.getInfo();
    String image = taskInfo.getImage();

    LOG.info("Compiling tests for task {} in {}", taskInfo.getTaskId(), copyId);

    builderInfo.setStatus(BuilderInfo.STATUS_COMPILING_TEST);
    ContainerExecResponse<String> r =
        containerManager.execTaskCompilation(
            taskCopy.getLocation(), image, taskInfo.getTaskCompilationRestrictions());
    builderInfo.setTestCompileResponse(r.response());
    switch (r.status()) {
      case FAILED_UNKNOWN:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Failed to compile testing code in task. Compiler response was: "
                    + r.rawResponse()));
        break;
      case FAILED_DISK:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient disk quota to compile testing code in task. Compiler response was: "
                    + r.rawResponse()));
        break;
      case FAILED_OOM:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient memory quota to compile testing code in task. Compiler response was: "
                    + r.rawResponse()));
        break;
      case FAILED_TIMEOUT:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Timeout when compiling testing code in task. Compiler response was: "
                    + r.rawResponse()));
        break;
      case COMPLETED:
      default:
        // do nothing
    }
    if (!r.status().equals(ContainerExecResponse.Status.COMPLETED)) {
      return false;
    }

    builderInfo.setStatus(BuilderInfo.STATUS_COMPILING_SOLUTION);
    // Test it against the model answer
    ContainerExecResponse<String> r2 =
        containerManager.execCompilation(
            taskConfig.getSolutionDir(copyId),
            taskConfig.getCompileDir(copyId),
            image,
            taskInfo.getCompilationRestrictions());
    builderInfo.setSolutionCompileResponse(r2.response());
    switch (r2.status()) {
      case FAILED_UNKNOWN:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Failed to compile solution when testing task during registration. "
                    + "Compiler response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_DISK:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient disk quota to compile solution when testing task during "
                    + "registration. "
                    + "Compiler response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_OOM:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient memory to compile solution when testing task during registration. "
                    + "Compiler response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_TIMEOUT:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Timeout when compiling solution when testing task during registration. "
                    + "Compiler response was: "
                    + r2.rawResponse()));
        break;
      case COMPLETED:
      default:
        // do nothing
    }
    if (!r2.status().equals(ContainerExecResponse.Status.COMPLETED)) {
      return false;
    }

    builderInfo.setStatus(BuilderInfo.STATUS_TESTING_SOLUTION);
    ContainerExecResponse<HarnessResponse> r3 =
        containerManager.execHarness(
            taskConfig.getSolutionDir(copyId),
            taskConfig.getHarnessDir(copyId),
            image,
            taskInfo.getHarnessRestrictions());
    builderInfo.setHarnessResponse(r3.response());
    switch (r3.status()) {
      case FAILED_UNKNOWN:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Failed to run harness when testing task during registration. "
                    + "Harness response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_DISK:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient disk quota running harness when testing task during registration. "
                    + "Harness response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_OOM:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient memory running harness when testing task during registration. "
                    + "Harness response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_TIMEOUT:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Timeout running harness when testing task during registration. "
                    + "Harness response was: "
                    + r2.rawResponse()));
        break;
      case COMPLETED:
      default:
        if (!r3.response().isCompleted()) {
          builderInfo.setException(
              new InvalidTaskSpecificationException(
                  "Harness failed to run to completion during registration. Harness response was: "
                      + r3.rawResponse()));
        }
    }
    if (!r3.status().equals(ContainerExecResponse.Status.COMPLETED)
        || !r3.response().isCompleted()) {
      return false;
    }

    ContainerExecResponse<ValidatorResponse> r4 =
        containerManager.execValidator(
            taskConfig.getValidatorDir(copyId),
            r3.response(),
            image,
            taskInfo.getValidatorRestrictions());
    builderInfo.setValidatorResponse(r4.response());
    switch (r4.status()) {
      case FAILED_UNKNOWN:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Failed to run validator when testing task during registration. "
                    + "Validator response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_DISK:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient disk quota running validator when testing task during registration. "
                    + "Validator response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_OOM:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Insufficient memory running validator when testing task during registration. "
                    + "Validator response was: "
                    + r2.rawResponse()));
        break;
      case FAILED_TIMEOUT:
        builderInfo.setException(
            new InvalidTaskSpecificationException(
                "Timeout running validator when testing task during registration. "
                    + "Validator response was: "
                    + r2.rawResponse()));
        break;
      case COMPLETED:
      default:
        if (!r4.response().isCompleted()) {
          builderInfo.setException(
              new InvalidTaskSpecificationException(
                  "Validator failed to run to completion during registration. "
                      + "Validator response was: "
                      + r3.rawResponse()));
        }
    }
    if (!r4.status().equals(ContainerExecResponse.Status.COMPLETED)
        || !r4.response().isCompleted()) {
      return false;
    }

    builderInfo.setStatus(BuilderInfo.STATUS_SUCCESS);

    return true;
  }
}
