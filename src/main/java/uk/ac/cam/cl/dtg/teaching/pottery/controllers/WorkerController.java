/**
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

import java.util.List;

import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.wordnik.swagger.annotations.Api;
import com.wordnik.swagger.annotations.ApiOperation;

import uk.ac.cam.cl.dtg.teaching.pottery.worker.JobStatus;
import uk.ac.cam.cl.dtg.teaching.pottery.worker.Worker;

@Produces("application/json")
@Path("/worker")
@Api(value = "/worker", description = "Manages the work queue.",position=0)
public class WorkerController {

	protected static final Logger LOG = LoggerFactory.getLogger(WorkerController.class);

	private Worker worker;

	
	@Inject
	public WorkerController(Worker worker) {
		super();
		this.worker = worker;
	}
		
	@GET
	@Path("/")
	@ApiOperation(value="Lists queue contents",response=JobStatus.class,responseContainer="List",position=0)
	public List<JobStatus> listQueue() {
		return worker.getQueue();
	}

	@POST
	@Path("/resize")
	@ApiOperation(value="Change number of worker threads",response=String.class,responseContainer="List")
	public Response resize(@FormParam("numThreads") int numThreads) {
		worker.rebuildThreadPool(numThreads);
		return Response.ok().entity("{ \"message\":\"Thread pool resized\" }").build();
	}
}