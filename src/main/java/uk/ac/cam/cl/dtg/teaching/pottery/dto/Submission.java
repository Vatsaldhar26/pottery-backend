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
package uk.ac.cam.cl.dtg.teaching.pottery.dto;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import uk.ac.cam.cl.dtg.teaching.pottery.TransactionQueryRunner;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.HarnessPart;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.HarnessResponse;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.Interpretation;
import uk.ac.cam.cl.dtg.teaching.programmingtest.containerinterface.ValidatorResponse;

public class Submission {

	public static final String STATUS_PENDING = "PENDING";

	public static final String STATUS_COMPILATION_RUNNING = "COMPILATION_RUNNING";
	public static final String STATUS_COMPILATION_FAILED = "COMPILATION_FAILED";
	public static final String STATUS_COMPILATION_COMPLETE = "COMPILATION_COMPLETE";

	public static final String STATUS_HARNESS_RUNNING = "HARNESS_RUNNING";	
	public static final String STATUS_HARNESS_FAILED = "HARNESS_FAILED";
	public static final String STATUS_HARNESS_COMPLETE = "HARNESS_COMPLETE";
	
	public static final String STATUS_VALIDATOR_RUNNING = "VALIDATOR_RUNNING";
	public static final String STATUS_VALIDATOR_FAILED = "VALIDATOR_FAILED";
	public static final String STATUS_VALIDATOR_COMPLETE = "VALIDATOR_COMPLETE";

	
	public static final String STATUS_COMPLETE = "complete";
	
	private final String repoId;
	private final String tag;

	private String compilationOutput;

	private long compilationTimeMs;
	private long harnessTimeMs;
	private long validatorTimeMs;
	
	private long waitTimeMs;
	
	private List<TestStep> testSteps;
	
	private String summaryMessage;

	private String status;
	
	public Submission(String repoId, String tag, String compilationOutput, long compilationTimeMs, long harnessTimeMs,
			long validatorTimeMs, long waitTimeMs, List<TestStep> testSteps, String summaryMessage, String status) {
		super();
		this.repoId = repoId;
		this.tag = tag;
		this.compilationOutput = compilationOutput;
		this.compilationTimeMs = compilationTimeMs;
		this.harnessTimeMs = harnessTimeMs;
		this.validatorTimeMs = validatorTimeMs;
		this.waitTimeMs = waitTimeMs;
		this.testSteps = testSteps;
		this.summaryMessage = summaryMessage;
		this.status = status;
	}



	public String getRepoId() {
		return repoId;
	}



	public String getTag() {
		return tag;
	}



	public String getCompilationOutput() {
		return compilationOutput;
	}




	public long getCompilationTimeMs() {
		return compilationTimeMs;
	}



	public long getHarnessTimeMs() {
		return harnessTimeMs;
	}



	public long getValidatorTimeMs() {
		return validatorTimeMs;
	}



	public long getWaitTimeMs() {
		return waitTimeMs;
	}


	public List<TestStep> getTestSteps() {
		return testSteps;
	}



	public String getSummaryMessage() {
		return summaryMessage;
	}



	public String getStatus() {
		return status;
	}



	public static Builder builder(String repoId, String tag) {
		return new Builder(repoId,tag);
	}
	
	public static class Builder {
		
		private final String repoId;
		private final String tag;
		private String compilationOutput;
		private long compilationTimeMs = -1;
		private long harnessTimeMs = -1;
		private long validatorTimeMs = -1;
		private long waitTimeMs;
		private List<HarnessPart> harnessParts;
		private List<Interpretation> validatorInterpretations;
		private String summaryMessage;
		private String status;
		
		private Builder(String repoId, String tag) {
			this.repoId = repoId;
			this.tag = tag;
			this.status = STATUS_PENDING;
		}
		
		public Builder setStatus(String status) {
			this.status = status;
			return this;
		}
		
		public Builder setSummaryMessage(String summaryMessage) {
			this.summaryMessage = summaryMessage;
			return this;
		}
		
		public Builder setCompilationResponse(String compilationOutput, boolean success, long executionTimeMs) {
			this.status = success ?  STATUS_COMPILATION_COMPLETE : STATUS_COMPILATION_FAILED;
			this.compilationOutput = compilationOutput;
			this.compilationTimeMs = executionTimeMs;
			return this;
		}
		
		/**
		 * Update the builder with harness response. Note that this only takes a shallow copy of its arguments
		 * 
		 * @param h
		 * @param success
		 * @param executionTimeMs
		 * @return
		 */
		public Builder setHarnessResponse(HarnessResponse h, long executionTimeMs) {
			this.status = h.isCompleted() ?  STATUS_HARNESS_COMPLETE : STATUS_HARNESS_FAILED;
			this.harnessParts = h.getTestParts();
			this.harnessTimeMs = executionTimeMs;
			this.summaryMessage = h.getMessage();
			return this;
		}
		
		public Builder setValidatorResponse(ValidatorResponse v, long executionTimeMs) {
			this.status = v.isCompleted() ?  STATUS_VALIDATOR_COMPLETE : STATUS_VALIDATOR_FAILED;
			this.validatorInterpretations = v.getInterpretations();
			this.validatorTimeMs = executionTimeMs;
			this.summaryMessage = v.getMessage();
			return this;
		}
		
		public Builder setWaitTimeMs(long waitTimeMs) {
			this.waitTimeMs = waitTimeMs;
			return this;
		}
		
		public Submission build() {
			Map<String, Interpretation> i =	
					validatorInterpretations == null ? 
							null :
							validatorInterpretations.stream().collect(Collectors.toMap(Interpretation::getId, Function.identity()));
			List<TestStep> testSteps = harnessParts == null ? null : harnessParts.stream().map(p->new TestStep(p,i)).collect(Collectors.toList());
			
			return new Submission(repoId, tag, compilationOutput, compilationTimeMs, harnessTimeMs, validatorTimeMs, waitTimeMs, testSteps, summaryMessage, status);
		}

		public void setComplete() {

			if (STATUS_PENDING.equals(status) ||
					STATUS_COMPILATION_RUNNING.equals(status)) {
				status = STATUS_COMPILATION_FAILED;
				return;
			}

			if (STATUS_COMPILATION_COMPLETE.equals(status) ||
					STATUS_HARNESS_RUNNING.equals(status)) {
				status = STATUS_HARNESS_FAILED;
				return;
			}

			if (STATUS_HARNESS_COMPLETE.equals(status) ||
					STATUS_VALIDATOR_RUNNING.equals(status)) {
				status = STATUS_VALIDATOR_FAILED;
				return;
			}

			status = STATUS_COMPLETE;
		}
	}
			
	
	public void insert(TransactionQueryRunner q) throws SQLException {
		ObjectMapper mapper = new ObjectMapper();

		try {		
			// missing fields
			q.update("INSERT into submissions ("
					+ "repoid,"
					+ "tag,"
					+ "status,"
					+ "compilationoutput,"
					+ "compilationTimeMs,"
					+ "harnessTimeMs,"
					+ "validatorTimeMs,"
					+ "waitTimeMs,"
					+ "summaryMessage,"
					+ "testSteps"
					+ ") VALUES (?,?,?,?,?,?,?,?,?,?)",
					repoId,
					tag,
					status,
					compilationOutput,
					compilationTimeMs,
					harnessTimeMs,
					validatorTimeMs,
					waitTimeMs,
					summaryMessage,
					testSteps == null ? null : mapper.writeValueAsString(testSteps));
		} catch (JsonProcessingException e) {
			throw new SQLException("Failed to serialise object",e);
		}
		q.commit();
	}
	
	public void update(TransactionQueryRunner q) throws SQLException {
		ObjectMapper mapper = new ObjectMapper();
		try {
			q.update("update submissions set "
					+ "status=?,"
					+ "compilationoutput=?,"
					+ "compilationTimeMs=?,"
					+ "harnessTimeMs=?,"
					+ "validatorTimeMs=?"
					+ "waitTimeMs=?,"
					+ "summaryMessage=?,"
					+ "testSteps=?"
					+ " where "
					+ "repoId=? and tag=?",
					status,
					compilationOutput,
					compilationTimeMs,
					harnessTimeMs,
					validatorTimeMs,
					waitTimeMs,
					summaryMessage,
					testSteps == null ? null : mapper.writeValueAsString(testSteps),
					repoId,
					tag);
		} catch (JsonProcessingException e) {
			throw new SQLException("Failed to serialise object",e);
		}
		q.commit();
	}
	
	
	private static Submission resultSetToSubmission(ResultSet rs) throws SQLException {
		try {					
			ObjectMapper o = new ObjectMapper();
			String testPartsString = rs.getString("testSteps");
			List<TestStep> testSteps = null;
			if (!rs.wasNull()) {
				testSteps = o.readValue(testPartsString,new TypeReference<List<TestStep>>() {});
			}
			
			return new Submission(
					rs.getString("repoId"),
					rs.getString("tag"),
					rs.getString("compilationOutput"),
					rs.getLong("compilationTimeMs"),
					rs.getLong("harnessTimeMs"),
					rs.getLong("validatorTimeMs"),
					rs.getLong("waitTimeMs"),
					testSteps,
					rs.getString("summaryMessage"),
					rs.getString("status"));
		} catch (IOException e) {
			throw new SQLException("Failed to deserialise json object",e);
		}
	}
	
	public static Submission getByRepoIdAndTag(String repoId, String tag, QueryRunner q) throws SQLException {
		return q.query("select * from submissions where repoid =? and tag = ?", new ResultSetHandler<Submission>() {
			@Override
			public Submission handle(ResultSet rs) throws SQLException {
				if (rs.next()) {
					return resultSetToSubmission(rs);
				}
				else {
					return null;
				}
			}
			
		},repoId,tag);
	}
}