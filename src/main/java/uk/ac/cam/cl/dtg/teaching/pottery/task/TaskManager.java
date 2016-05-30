package uk.ac.cam.cl.dtg.teaching.pottery.task;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import uk.ac.cam.cl.dtg.teaching.pottery.Database;
import uk.ac.cam.cl.dtg.teaching.pottery.TransactionQueryRunner;
import uk.ac.cam.cl.dtg.teaching.pottery.containers.ContainerManager;
import uk.ac.cam.cl.dtg.teaching.pottery.dto.TaskInfo;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.TaskCloneException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.TaskException;
import uk.ac.cam.cl.dtg.teaching.pottery.exceptions.TaskNotFoundException;

@Singleton
public class TaskManager {

	public static final Logger LOG = LoggerFactory.getLogger(TaskManager.class);
			
	private Map<String,Task> definedTasks;

	private TaskFactory taskFactory;
	
	@Inject
	public TaskManager(ContainerManager containerManager, TaskFactory taskFactory, Database database) throws GitAPIException, IOException, SQLException, TaskException {
		this.taskFactory = taskFactory;
		this.definedTasks = new HashMap<>();
		
		List<String> taskIds;
		try(TransactionQueryRunner q = database.getQueryRunner()) {
			taskIds = TaskDefInfo.getAllTaskIds(q);
		}
		
		for(String taskId : taskIds) {
			try {
				Task t = taskFactory.getInstance(taskId);
				definedTasks.put(taskId, t);
			}
			catch (TaskException e) {
				LOG.warn("Ignoring task "+taskId,e);
			}
		}
	}
	
	public TaskInfo createNewTask() throws TaskException {
		Task newTask = taskFactory.createInstance();
		definedTasks.put(newTask.getTaskId(), newTask);
		return newTask.getTestingClone().getInfo();
	}
	
	public Collection<TaskInfo> getTestingTasks() {
		return definedTasks.values().stream().map(t -> t.getTestingClone().getInfo()).collect(Collectors.toList());
	}
	
	public Collection<TaskInfo> getRegisteredTasks() {
		return definedTasks.values().stream()
				.filter(t -> t.getRegisteredClone() != null)
				.map(t -> t.getRegisteredClone().getInfo())
				.collect(Collectors.toList());
	}

	public Collection<TaskInfo> getRetiredTasks() {
		return definedTasks.values().stream()
				.filter(t -> t.isRetired())
				.map(t -> t.getTestingClone().getInfo())
				.collect(Collectors.toList());
	}

	public TaskInfo getTestingTask(String taskId) {
		return definedTasks.get(taskId).getTestingClone().getInfo();
	}

	public TaskInfo getRegisteredTaskInfo(String taskId) throws TaskNotFoundException {
		TaskClone c = definedTasks.get(taskId).getRegisteredClone();
		if (c == null) throw new TaskNotFoundException("Failed to find a registered task with ID "+taskId);
		return c.getInfo();
	}

	public Task getTask(String taskId) {
		return definedTasks.get(taskId);
	}
	
	public void updateTesting(String taskID) throws TaskCloneException, IOException {
		definedTasks.get(taskID).getTestingClone().update(Constants.HEAD,true);
	}

}