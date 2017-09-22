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

package uk.ac.cam.cl.dtg.teaching.pottery.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import org.apache.commons.io.Charsets;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.ac.cam.cl.dtg.teaching.docker.ApiUnavailableException;
import uk.ac.cam.cl.dtg.teaching.pottery.model.Criterion;
import uk.ac.cam.cl.dtg.teaching.pottery.FileUtil;
import uk.ac.cam.cl.dtg.teaching.pottery.config.ContainerEnvConfig;
import uk.ac.cam.cl.dtg.teaching.pottery.config.RepoConfig;
import uk.ac.cam.cl.dtg.teaching.pottery.config.TaskConfig;
import uk.ac.cam.cl.dtg.teaching.pottery.containers.ContainerManager;
import uk.ac.cam.cl.dtg.teaching.pottery.model.ContainerRestrictions;
import uk.ac.cam.cl.dtg.teaching.pottery.database.Database;
import uk.ac.cam.cl.dtg.teaching.pottery.database.InMemoryDatabase;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.CriterionNotFoundException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.RepoExpiredException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.RepoFileNotFoundException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.RepoNotFoundException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.RepoStorageException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.RepoTagNotFoundException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.RetiredTaskException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.TaskNotFoundException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.TaskStorageException;
import uk.ac.cam.cl.dtg.teaching.pottery.model.TaskInfo;
import uk.ac.cam.cl.dtg.teaching.pottery.repo.Repo;
import uk.ac.cam.cl.dtg.teaching.pottery.repo.RepoFactory;
import uk.ac.cam.cl.dtg.teaching.pottery.task.Task;
import uk.ac.cam.cl.dtg.teaching.pottery.task.TaskCopy;
import uk.ac.cam.cl.dtg.teaching.pottery.task.TaskFactory;
import uk.ac.cam.cl.dtg.teaching.pottery.task.TaskIndex;
import uk.ac.cam.cl.dtg.teaching.pottery.task.TaskInfos;
import uk.ac.cam.cl.dtg.teaching.pottery.worker.BlockingWorker;
import uk.ac.cam.cl.dtg.teaching.pottery.worker.Worker;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.HarnessPart;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.HarnessResponse;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.Measurement;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.ValidatorResponse;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

public class TestRepo {

  private File testRootDir;
  private Repo repo;

  private static String getScriptContents(Object toPrint) throws JsonProcessingException {
    return ImmutableList.of(
            "#!/bin/bash",
            "",
            "cat <<EOF",
            new ObjectMapper().writer().writeValueAsString(toPrint),
            "EOF")
        .stream()
        .collect(Collectors.joining("\n"));
  }

  private static void mkJsonPrintingScript(File root, String fileName, Object toPrint, Git git)
      throws IOException, GitAPIException {
    File file = new File(root, fileName);
    if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
      throw new IOException("Failed to create " + file.getParent());
    }
    try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
      w.print(getScriptContents(toPrint));
    }
    if (!file.setExecutable(true)) {
      throw new IOException("Failed to chmod " + fileName);
    }
    git.add().addFilepattern(fileName).call();
  }

  /** Configure the test environment. */
  @Before
  public void setup()
      throws IOException, GitAPIException, TaskStorageException, SQLException,
          TaskNotFoundException, CriterionNotFoundException, ApiUnavailableException,
          RetiredTaskException, RepoExpiredException, RepoNotFoundException, RepoStorageException {

    this.testRootDir = Files.createTempDir();
    Database database = new InMemoryDatabase();

    TaskConfig taskConfig = new TaskConfig(new File(testRootDir, "tasks"));
    TaskFactory taskFactory = new TaskFactory(taskConfig, database);
    Task task = taskFactory.createInstance();
    String taskId = task.getTaskId();

    File copyRoot = new File(testRootDir, taskId + "-clone");
    if (!copyRoot.mkdirs()) {
      throw new IOException("Failed to create " + copyRoot);
    }

    try (Git g =
        Git.cloneRepository()
            .setURI("file://" + taskConfig.getTaskDefinitionDir(taskId).getPath())
            .setDirectory(copyRoot)
            .call()) {

      mkJsonPrintingScript(copyRoot, "compile-test.sh", "Compiling test", g);

      mkJsonPrintingScript(copyRoot, "compile/compile-solution.sh", "Compiling solution", g);

      mkJsonPrintingScript(
          copyRoot,
          "harness/run-harness.sh",
          new HarnessResponse(
              new LinkedList<>(
                  ImmutableList.of(
                      new HarnessPart(
                          "A no-op task",
                          ImmutableList.of("Doing nothing"),
                          ImmutableList.of(new Measurement("correctness", "true", "id")),
                          null,
                          null))),
              true),
          g);

      mkJsonPrintingScript(copyRoot, "skeleton/skeleton.sh", "Skeleton", g);

      TaskInfo i =
          new TaskInfo(
              TaskInfo.TYPE_ALGORITHM,
              "Empty task",
              ImmutableSet.of(new Criterion("correctness")),
              "template:java",
              "easy",
              0,
              "java",
              "Empty task",
              ContainerRestrictions.candidateRestriction(null),
              ContainerRestrictions.candidateRestriction(null),
              ContainerRestrictions.candidateRestriction(null),
              ContainerRestrictions.authorRestriction(null),
              ImmutableList.of());
      TaskInfos.save(i, copyRoot);
      g.add().addFilepattern("task.json").call();

      mkJsonPrintingScript(
          copyRoot,
          "validator/run-validator.sh",
          new ValidatorResponse(true, null, ImmutableList.of(), null),
          g);

      g.commit().setMessage("Empty task").call();
      g.push().call();
    }

    ContainerManager containerManager = new ContainerManager(new ContainerEnvConfig());

    TaskIndex taskIndex = new TaskIndex(taskFactory, database);
    taskIndex.add(task);
    RepoFactory repoFactory =
        new RepoFactory(new RepoConfig(new File(testRootDir, "repos")), database);
    Worker w = new BlockingWorker(taskIndex, repoFactory, containerManager, database);
    task.scheduleBuildTestingCopy(w);

    Calendar calendar = Calendar.getInstance();
    calendar.add(Calendar.YEAR, 10);
    this.repo = repoFactory.createInstance(taskId, true, calendar.getTime());
    try (TaskCopy c = task.acquireTestingCopy()) {
      this.repo.copyFiles(c);
    }
  }

  @After
  public void tearDown() throws IOException {
    FileUtil.deleteRecursive(testRootDir);
  }

  @Test
  public void readFile_getsCorrectContents()
      throws TaskNotFoundException, RepoExpiredException, RepoNotFoundException,
          RetiredTaskException, RepoStorageException, TaskStorageException,
          RepoFileNotFoundException, RepoTagNotFoundException, JsonProcessingException {

    // ARRANGE
    String expectedContents = getScriptContents("Skeleton");

    // ACT
    byte[] fileContents = repo.readFile("HEAD", "skeleton.sh");

    // ASSERT
    assertThat(new String(fileContents)).isEqualTo(expectedContents);
  }

  @Test
  public void listFiles_findsSkeletonFile() throws RepoStorageException, RepoTagNotFoundException {

    // ARRANGE
    ImmutableList<String> expectedFiles = ImmutableList.of("skeleton.sh");

    // ACT
    List<String> files = repo.listFiles("HEAD");

    // ASSERT
    assertThat(files).containsExactlyElementsIn(expectedFiles);
  }

  @Test
  public void createTag_showsUpInListTags() throws RepoStorageException, RepoExpiredException {

    // ACT
    String tagName = repo.createNewTag();
    List<String> tags = repo.listTags();

    // ASSERT
    assertThat(tags).contains(tagName);
  }

  @Test
  public void updateFile_altersFileContents()
      throws RepoExpiredException, RepoFileNotFoundException, RepoStorageException,
          RepoTagNotFoundException {

    // ARRANGE
    byte[] updatedContents = "NEW CONTENTS".getBytes(Charsets.UTF_8);

    // ACT
    repo.updateFile("skeleton.sh", updatedContents);
    byte[] readContents = repo.readFile("HEAD", "skeleton.sh");

    // ASSERT
    assertThat(readContents).isEqualTo(updatedContents);
  }
}
